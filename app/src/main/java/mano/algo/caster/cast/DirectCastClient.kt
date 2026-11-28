package mano.algo.caster.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mano.algo.caster.R
import mano.algo.caster.server.NetworkUtils
import mano.algo.caster.tv.LanNetwork
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** A Chromecast discovered directly on the local subnet (used when Google Play services can't see a hotspot). */
data class DirectCastDevice(
    val id: String,
    val name: String,
    val host: String,
)

/** Current playback snapshot from a [DirectCastSession]. */
data class DirectCastStatus(
    val title: String? = null,
    val playing: Boolean = false,
    val error: String? = null,
    val muted: Boolean = false,
    val volume: Double = 1.0,
    val contentId: String? = null,
    val positionSec: Int = 0,
    val durationSec: Int = 0,
    val recordResume: Boolean = false,
)

/**
 * Minimal Cast v2 protobuf wire codec (`extensions.api.cast_channel.CastMessage`).
 *
 * Fields:
 *  1: protocol_version (varint, 0 = CASTV2_1_0)
 *  2: source_id (string)
 *  3: destination_id (string)
 *  4: namespace (string)
 *  5: payload_type (varint, 0 = STRING, 1 = BINARY)
 *  6: payload_utf8 (string)
 */
internal object CastMessageCodec {
    private const val WIRE_VARINT = 0
    private const val WIRE_BYTES = 2

    data class CastMessage(
        val sourceId: String,
        val destinationId: String,
        val namespace: String,
        val payloadUtf8: String,
    )

    fun encode(msg: CastMessage): ByteArray {
        val body = ByteArrayOutputStream()
        writeVarintField(body, 1, 0) // CASTV2_1_0
        writeStringField(body, 2, msg.sourceId)
        writeStringField(body, 3, msg.destinationId)
        writeStringField(body, 4, msg.namespace)
        writeVarintField(body, 5, 0) // STRING
        writeStringField(body, 6, msg.payloadUtf8)
        val payload = body.toByteArray()
        val framed = ByteArray(4 + payload.size)
        val len = payload.size
        framed[0] = ((len ushr 24) and 0xFF).toByte()
        framed[1] = ((len ushr 16) and 0xFF).toByte()
        framed[2] = ((len ushr 8) and 0xFF).toByte()
        framed[3] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, framed, 4, len)
        return framed
    }

    fun readFrame(input: InputStream): CastMessage {
        val data = DataInputStream(input)
        val len = data.readInt()
        if (len <= 0 || len > 1024 * 1024) throw IOException("Invalid Cast frame length: $len")
        val buf = ByteArray(len)
        data.readFully(buf)
        return decode(buf)
    }

    fun decode(bytes: ByteArray): CastMessage {
        var pos = 0
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payloadUtf8 = ""
        while (pos < bytes.size) {
            val (tag, nextPos) = readVarint(bytes, pos)
            pos = nextPos
            val field = tag ushr 3
            val wire = tag and 0x07
            when (wire) {
                WIRE_VARINT -> {
                    val (_, after) = readVarint(bytes, pos)
                    pos = after
                }
                WIRE_BYTES -> {
                    val (len, start) = readVarint(bytes, pos)
                    if (len < 0 || start + len > bytes.size) throw IOException("Truncated Cast field")
                    val value = String(bytes, start, len, Charsets.UTF_8)
                    when (field) {
                        2 -> sourceId = value
                        3 -> destinationId = value
                        4 -> namespace = value
                        6 -> payloadUtf8 = value
                    }
                    pos = start + len
                }
                else -> throw IOException("Unsupported wire type: $wire")
            }
        }
        return CastMessage(sourceId, destinationId, namespace, payloadUtf8)
    }

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Int) {
        writeVarint(out, (field shl 3) or WIRE_VARINT)
        writeVarint(out, value)
    }

    private fun writeStringField(out: ByteArrayOutputStream, field: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, (field shl 3) or WIRE_BYTES)
        writeVarint(out, bytes.size)
        out.write(bytes)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var pos = start
        var result = 0
        var shift = 0
        while (pos < bytes.size) {
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result to pos
            shift += 7
            if (shift >= 35) throw IOException("Malformed varint")
        }
        throw EOFException("Unexpected end of Cast varint")
    }
}

/**
 * Discovers Chromecasts directly on the local subnet (port 8009 + `/setup/eureka_info`) and talks Cast v2
 * over TLS on port 8009. Used when Google Play services' MediaRouter doesn't scan the phone's own hotspot.
 */
internal object DirectCastDiscovery {
    private const val CAST_PORT = 8009
    private const val EUREKA_HTTP_PORT = 8008
    private const val EUREKA_HTTPS_PORT = 8443

    suspend fun scan(context: Context): List<DirectCastDevice> = coroutineScope {
        val own = NetworkUtils.resolveLocalIpv4(context.applicationContext)?.hostAddress ?: return@coroutineScope emptyList()
        val prefix = own.substringBeforeLast('.')
        val gate = Semaphore(48)
        (1..254).mapNotNull { n ->
            val host = "$prefix.$n"
            if (host == own) null else async(Dispatchers.IO) {
                gate.withPermit {
                    if (!isPortOpen(host, CAST_PORT, 250)) return@withPermit null
                    fetchEurekaInfo(host) ?: DirectCastDevice("direct:$host", "Chromecast ($host)", host)
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                LanNetwork.bind(socket)
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
        }.isSuccess

    internal fun parseEurekaInfo(host: String, json: String): DirectCastDevice? = runCatching {
        val obj = JSONObject(json)
        val name = obj.optString("name").trim().ifEmpty { "Chromecast ($host)" }
        val udn = obj.optString("ssdp_udn").trim().ifEmpty { "direct:$host" }
        DirectCastDevice(id = udn, name = name, host = host)
    }.getOrNull()

    private fun fetchEurekaInfo(host: String): DirectCastDevice? {
        val http = fetchUrl("http://$host:$EUREKA_HTTP_PORT/setup/eureka_info?options=detail", https = false)
        if (http != null) return parseEurekaInfo(host, http)
        val https = fetchUrl("https://$host:$EUREKA_HTTPS_PORT/setup/eureka_info?options=detail", https = true)
        return https?.let { parseEurekaInfo(host, it) }
    }

    private fun fetchUrl(urlStr: String, https: Boolean): String? = runCatching {
        val conn = (LanNetwork.open(URL(urlStr)) as HttpURLConnection).apply {
            connectTimeout = 1_500
            readTimeout = 1_500
            if (https && this is HttpsURLConnection) {
                sslSocketFactory = trustAllSslContext().socketFactory
                setHostnameVerifier { _, _ -> true }
            }
        }
        try {
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

private fun trustAllSslContext(): SSLContext {
    val trustAll = arrayOf<TrustManager>(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        },
    )
    return SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
}

/**
 * Direct Cast v2 client over TLS port 8009.
 */
internal class DirectCastSession(
    val device: DirectCastDevice,
    private val appId: String,
    private val onStatus: (DirectCastStatus) -> Unit,
    private val onClosed: (String?) -> Unit,
) {
    companion object {
        private const val SENDER_ID = "sender-0"
        private const val RECEIVER_ID = "receiver-0"
        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        private const val CAST_PORT = 8009

        fun receiverAppId(context: Context): String {
            val own = context.getString(R.string.cast_receiver_app_id).trim()
            return if (Regex("[A-Za-z0-9]{8}").matches(own)) own else CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        }
    }

    private val closed = AtomicBoolean(false)
    private val requestIds = AtomicInteger(1)
    private val writeLock = Any()

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var transportId: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var mediaSessionId: Long? = null
    @Volatile private var currentVolume: Double = 1.0
    @Volatile private var currentMuted: Boolean = false
    @Volatile private var currentTitle: String? = null
    @Volatile private var currentDurationSec: Int = 0
    @Volatile private var lastPositionSec: Double = 0.0
    @Volatile private var lastPositionAtMs: Long = 0L
    @Volatile private var isPlayingNow: Boolean = false

    private val launchLock = Object()

    fun connect(): Boolean {
        return try {
            val plain = Socket()
            LanNetwork.bind(plain)
            plain.connect(InetSocketAddress(device.host, CAST_PORT), 4_000)
            val ssl = trustAllSslContext().socketFactory.createSocket(plain, device.host, CAST_PORT, true) as SSLSocket
            ssl.soTimeout = 0
            ssl.startHandshake()
            socket = ssl
            output = ssl.outputStream

            send(RECEIVER_ID, NS_CONNECTION, """{"type":"CONNECT"}""")
            startReader(ssl.inputStream)
            startHeartbeat()

            val tid = launchReceiver()
            if (tid == null) {
                close("Couldn't start the receiver on ${device.name}.")
                return false
            }
            true
        } catch (e: Exception) {
            close("Couldn't connect to ${device.name}.")
            false
        }
    }

    private fun launchReceiver(): String? {
        transportId?.let { return it }
        if (closed.get()) return null
        val launchReq = JSONObject()
            .put("type", "LAUNCH")
            .put("appId", appId)
            .put("requestId", requestIds.getAndIncrement())
        send(RECEIVER_ID, NS_RECEIVER, launchReq.toString())

        val deadline = System.currentTimeMillis() + 10_000L
        synchronized(launchLock) {
            while (transportId == null && !closed.get()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                launchLock.wait(left)
            }
        }
        val tid = transportId ?: return null
        send(tid, NS_CONNECTION, """{"type":"CONNECT"}""")
        return tid
    }

    fun load(media: JSONObject, autoplay: Boolean = true, currentTimeSec: Double? = null, activeTrackIds: LongArray? = null) {
        val tid = launchReceiver() ?: return
        val req = JSONObject()
            .put("type", "LOAD")
            .put("requestId", requestIds.getAndIncrement())
            .put("media", media)
            .put("autoplay", autoplay)
        if (currentTimeSec != null && currentTimeSec > 0) req.put("currentTime", currentTimeSec)
        if (activeTrackIds != null && activeTrackIds.isNotEmpty()) {
            req.put("activeTrackIds", JSONArray().apply { activeTrackIds.forEach { put(it) } })
        }
        send(tid, NS_MEDIA, req.toString())
    }

    fun queueLoad(items: JSONArray, startIndex: Int) {
        val tid = launchReceiver() ?: return
        val req = JSONObject()
            .put("type", "QUEUE_LOAD")
            .put("requestId", requestIds.getAndIncrement())
            .put("items", items)
            .put("startIndex", startIndex)
            .put("repeatMode", "REPEAT_OFF")
        send(tid, NS_MEDIA, req.toString())
    }

    fun hasActiveMedia(): Boolean = mediaSessionId != null && transportId != null

    fun play() = mediaCommand("PLAY")
    fun pause() = mediaCommand("PAUSE")
    fun queueNext() = mediaCommand("QUEUE_NEXT")
    fun queuePrev() = mediaCommand("QUEUE_PREV")

    fun togglePlayback() {
        if (isPlayingNow) pause() else play()
    }

    fun approximatePositionSec(): Double {
        if (!isPlayingNow || lastPositionAtMs == 0L) return lastPositionSec
        return lastPositionSec + (System.currentTimeMillis() - lastPositionAtMs) / 1000.0
    }

    fun seek(positionSec: Double) {
        val tid = transportId ?: return
        val msid = mediaSessionId ?: return
        val req = JSONObject()
            .put("type", "SEEK")
            .put("requestId", requestIds.getAndIncrement())
            .put("mediaSessionId", msid)
            .put("currentTime", positionSec.coerceAtLeast(0.0))
        send(tid, NS_MEDIA, req.toString())
    }

    fun adjustVolume(delta: Double) {
        val next = (currentVolume + delta).coerceIn(0.0, 1.0)
        currentVolume = next
        val req = JSONObject()
            .put("type", "SET_VOLUME")
            .put("requestId", requestIds.getAndIncrement())
            .put("volume", JSONObject().put("level", next))
        send(RECEIVER_ID, NS_RECEIVER, req.toString())
    }

    fun toggleMute(): Boolean {
        val next = !currentMuted
        currentMuted = next
        val req = JSONObject()
            .put("type", "SET_VOLUME")
            .put("requestId", requestIds.getAndIncrement())
            .put("volume", JSONObject().put("muted", next))
        send(RECEIVER_ID, NS_RECEIVER, req.toString())
        return next
    }

    fun stopAndClose() {
        runCatching {
            sessionId?.let { sid ->
                val req = JSONObject()
                    .put("type", "STOP")
                    .put("requestId", requestIds.getAndIncrement())
                    .put("sessionId", sid)
                send(RECEIVER_ID, NS_RECEIVER, req.toString())
            }
        }
        close(null)
    }

    private fun mediaCommand(type: String) {
        val tid = transportId ?: return
        val msid = mediaSessionId ?: return
        val req = JSONObject()
            .put("type", type)
            .put("requestId", requestIds.getAndIncrement())
            .put("mediaSessionId", msid)
        send(tid, NS_MEDIA, req.toString())
    }

    private fun send(destinationId: String, namespace: String, payloadJson: String) {
        if (closed.get()) return
        val frame = CastMessageCodec.encode(
            CastMessageCodec.CastMessage(
                sourceId = SENDER_ID,
                destinationId = destinationId,
                namespace = namespace,
                payloadUtf8 = payloadJson,
            ),
        )
        synchronized(writeLock) {
            output?.let {
                it.write(frame)
                it.flush()
            }
        }
    }

    private fun startHeartbeat() {
        Thread({
            try {
                while (!closed.get()) {
                    Thread.sleep(5_000L)
                    if (closed.get()) break
                    send(RECEIVER_ID, NS_HEARTBEAT, """{"type":"PING"}""")
                }
            } catch (_: Throwable) {
            }
        }, "cast-heartbeat").apply { isDaemon = true }.start()
    }

    private fun startReader(input: InputStream) {
        Thread({
            try {
                while (!closed.get()) {
                    val msg = CastMessageCodec.readFrame(input)
                    handleMessage(msg)
                }
            } catch (_: Throwable) {
                close(null)
            }
        }, "cast-reader").apply { isDaemon = true }.start()
    }

    private fun handleMessage(msg: CastMessageCodec.CastMessage) {
        val json = runCatching { JSONObject(msg.payloadUtf8) }.getOrNull() ?: return
        val type = json.optString("type")
        when (msg.namespace) {
            NS_HEARTBEAT -> if (type == "PING") send(msg.sourceId, NS_HEARTBEAT, """{"type":"PONG"}""")
            NS_CONNECTION -> if (type == "CLOSE" && msg.sourceId == transportId) {
                clearReceiverSession()
            }
            NS_RECEIVER -> if (type == "RECEIVER_STATUS") {
                val status = json.optJSONObject("status") ?: return
                status.optJSONObject("volume")?.let { vol ->
                    if (vol.has("level")) currentVolume = vol.optDouble("level", currentVolume)
                    if (vol.has("muted")) currentMuted = vol.optBoolean("muted", currentMuted)
                }
                val apps = status.optJSONArray("applications")
                var chosen: JSONObject? = null
                if (apps != null) {
                    for (i in 0 until apps.length()) {
                        val a = apps.optJSONObject(i) ?: continue
                        if (a.optString("appId") == appId) {
                            chosen = a
                            break
                        }
                    }
                }
                val tid = chosen?.optString("transportId")?.takeIf { it.isNotEmpty() }
                val sid = chosen?.optString("sessionId")?.takeIf { it.isNotEmpty() }
                if (tid != null) {
                    synchronized(launchLock) {
                        transportId = tid
                        sessionId = sid
                        launchLock.notifyAll()
                    }
                } else if (transportId != null) {
                    clearReceiverSession()
                }
            }
            NS_MEDIA -> if (type == "MEDIA_STATUS") {
                val arr = json.optJSONArray("status") ?: return
                if (arr.length() == 0) return
                val st = arr.optJSONObject(0) ?: return
                if (st.has("mediaSessionId")) mediaSessionId = st.optLong("mediaSessionId")
                val playerState = st.optString("playerState")
                if (playerState == "IDLE") mediaSessionId = null
                val idleReason = st.optString("idleReason")
                val failed = playerState == "IDLE" && idleReason == "ERROR"
                val mediaObj = st.optJSONObject("media")
                val title = mediaObj?.optJSONObject("metadata")?.optString("title")?.takeIf { it.isNotEmpty() }
                if (title != null) currentTitle = title
                val contentId = mediaObj?.optString("contentId")?.takeIf { it.isNotEmpty() }
                val reportedDur = mediaObj?.optDouble("duration", 0.0)?.toInt()?.takeIf { it > 0 }
                if (reportedDur != null) currentDurationSec = reportedDur
                val durationSec = currentDurationSec
                val posExact = st.optDouble("currentTime", lastPositionSec)
                val positionSec = posExact.toInt()
                val playing = playerState == "PLAYING" || playerState == "BUFFERING" || playerState == "LOADING"
                lastPositionSec = posExact
                lastPositionAtMs = System.currentTimeMillis()
                isPlayingNow = playing
                val recordResume = durationSec > 0 && (playerState == "PLAYING" || playerState == "PAUSED")
                onStatus(
                    DirectCastStatus(
                        title = currentTitle,
                        playing = playing,
                        error = if (failed) "The Chromecast couldn't play this media. Its format may not be supported." else null,
                        muted = currentMuted,
                        volume = currentVolume,
                        contentId = contentId,
                        positionSec = positionSec,
                        durationSec = durationSec,
                        recordResume = recordResume,
                    ),
                )
            }
        }
    }

    private fun clearReceiverSession() {
        transportId = null
        sessionId = null
        mediaSessionId = null
        isPlayingNow = false
        onStatus(
            DirectCastStatus(
                title = currentTitle,
                playing = false,
                muted = currentMuted,
                volume = currentVolume,
                positionSec = lastPositionSec.toInt(),
                durationSec = currentDurationSec,
            ),
        )
    }

    private fun close(error: String?) {
        if (!closed.compareAndSet(false, true)) return
        synchronized(launchLock) { launchLock.notifyAll() }
        runCatching { transportId?.let { send(it, NS_CONNECTION, """{"type":"CLOSE"}""") } }
        runCatching { send(RECEIVER_ID, NS_CONNECTION, """{"type":"CLOSE"}""") }
        runCatching { socket?.close() }
        socket = null
        output = null
        onClosed(error)
    }
}
