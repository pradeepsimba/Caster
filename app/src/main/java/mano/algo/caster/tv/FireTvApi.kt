package mano.algo.caster.tv

import android.annotation.SuppressLint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * What the Fire TV's own remote service understands. Amazon doesn't publish this: it is what the community found by
 * watching the official remote app, so it can change with a Fire OS update. Pairing works like the official app:
 * the TV shows a 4-digit code, the phone sends it back and gets a token it keeps for next time.
 */
object FireTvCommands {
    /** The fixed key the remote service expects on every request (it is not a secret; the token is what pairs). */
    const val API_KEY = "0987654321"
    const val PORT = 8080

    /** Plain-HTTP port that starts the remote service, also while the TV is asleep. */
    const val WAKE_PORT = 8009
    const val WAKE_PATH = "/apps/FireTVRemote"
    const val BROWSER_PACKAGE = "com.amazon.cloud9" // Silk

    /** A request to the TV: [path] with an optional JSON [body]. */
    data class Request(val path: String, val body: String? = null)

    private fun nav(action: String) = Request("/v1/FireTV?action=$action")

    private fun scan(direction: String) =
        Request("/v1/media?action=scan", """{"direction":"$direction","keyAction":{"keyActionType":"keyDown"}}""")

    /** The request for an Android key code, or null for a button the Fire TV service has no command for. */
    fun forKey(code: Int): Request? = when (code) {
        TvKey.UP -> nav("dpad_up")
        TvKey.DOWN -> nav("dpad_down")
        TvKey.LEFT -> nav("dpad_left")
        TvKey.RIGHT -> nav("dpad_right")
        TvKey.OK -> nav("select")
        TvKey.BACK -> nav("back")
        TvKey.HOME -> nav("home")
        TvKey.MENU -> nav("menu")
        TvKey.PLAY_PAUSE -> Request("/v1/media?action=play")
        TvKey.FAST_FORWARD -> scan("forward")
        TvKey.REWIND -> scan("back")
        else -> null
    }

    fun supports(code: Int) = forKey(code) != null

    /**
     * Key down or key up for a direction. Holding a direction between the two makes the TV repeat it by itself, which is
     * far smoother than sending one press after another over the network. Only the four directions have it.
     */
    fun hold(code: Int, down: Boolean): Request? {
        val action = when (code) {
            TvKey.UP -> "dpad_up"
            TvKey.DOWN -> "dpad_down"
            TvKey.LEFT -> "dpad_left"
            TvKey.RIGHT -> "dpad_right"
            else -> return null
        }
        return Request("/v1/FireTV?action=$action", """{"keyActionType":"${if (down) "keyDown" else "keyUp"}"}""")
    }

    /** Puts [text] into the text field that has focus on the TV (the whole text, so it can be sent again safely). */
    fun text(text: String) = Request("/v1/FireTV/keyboard", """{"text":${jsonString(text)}}""")

    fun openBrowser() = Request("/v1/FireTV/app/$BROWSER_PACKAGE")

    /** Asks the TV to show a pairing code. */
    fun requestPin(deviceName: String) = Request("/v1/FireTV/pin/display", """{"friendlyName":${jsonString(deviceName)}}""")

    /** Sends the code the TV shows. The answer carries the token. */
    fun verifyPin(pin: String) = Request("/v1/FireTV/pin/verify", """{"pin":${jsonString(pin)}}""")

    fun isPin(input: String) = Regex("""\d{4}""").matches(input)

    /** The token in the answer to [verifyPin] (`{"description":"..."}`), only if it is made of safe header characters. */
    fun tokenFrom(response: String): String? =
        Regex(""""description"\s*:\s*"([A-Za-z0-9._~+/=-]{1,200})"""").find(response)?.groupValues?.get(1)

    /** [value] as a JSON string, escaped so it can't break out of the document. */
    fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}

/** The TV answered, but with an error status (as opposed to not answering at all). */
class FireTvHttpException(val status: Int) : IOException("The Fire TV answered $status")

/** The TV refused the token (it was reset, or this phone was removed): the owner has to pair again. */
class FireTvAuthException : IOException("The Fire TV no longer accepts this phone")

/**
 * One connection target: the Fire TV at [host] (a home-network address only). The TV's certificate is self-signed, so
 * it can't be checked the usual way; this client accepts it, but only ever talks to the address it was made for, and
 * that address must be on the home network.
 */
class FireTvLink(val host: String, var token: String?) {
    init {
        require(TvCommands.isLocalIpv4(host)) { "Not a home-network address" }
    }

    /** Starts the TV's remote service. Best effort: it also works as a no-op when the service is already up. */
    fun wake() {
        runCatching {
            val c = URL("http://$host:${FireTvCommands.WAKE_PORT}${FireTvCommands.WAKE_PATH}").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = CONNECT_MS
                c.readTimeout = READ_MS
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
                c.responseCode
            } finally {
                c.disconnect()
            }
        }
    }

    /** Sends [request] and returns the TV's answer. Throws [FireTvAuthException] when the TV doesn't accept the token. */
    fun send(request: FireTvCommands.Request): String {
        val c = URL("https://$host:${FireTvCommands.PORT}${request.path}").openConnection() as HttpsURLConnection
        try {
            c.sslSocketFactory = trustingContext.socketFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            c.requestMethod = "POST"
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.setRequestProperty("X-Api-Key", FireTvCommands.API_KEY)
            c.setRequestProperty("User-Agent", "okhttp/4.10.0")
            token?.let { c.setRequestProperty("X-Client-Token", it) }
            c.doOutput = true
            val bytes = request.body?.toByteArray(Charsets.UTF_8)
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setFixedLengthStreamingMode(bytes?.size ?: 0)
            c.outputStream.use { out -> bytes?.let(out::write) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val answer = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code == 401 || code == 403) throw FireTvAuthException()
            if (code !in 200..299) throw FireTvHttpException(code)
            return answer
        } catch (e: Exception) {
            c.disconnect() // after a failure drop the connection; after a success it stays open for the next press
            throw e
        }
    }

    // The TV's certificate is self-signed, so it can't be checked; see the class note for why that is acceptable here.
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private companion object {
        const val CONNECT_MS = 3_000
        const val READ_MS = 5_000

        /** Accepts the TV's self-signed certificate. Used only for the one connection made above, never installed globally. */
        val trustingContext: SSLContext by lazy {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        }
    }
}
