package mano.algo.caster.tv

import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicInteger

/** An input/port on a TV (e.g., Live TV, HDMI 1, HDMI 2). */
data class TvInput(
    val id: String,
    val label: String,
    val kind: InputKind = InputKind.HDMI,
    val connected: Boolean = true,
    val appId: String? = null,
) {
    enum class InputKind { LIVE_TV, STB, HDMI, AV, OTHER }
}

/** An app available to launch on the connected TV. */
data class InstalledTvApp(
    val id: String,
    val title: String,
    val fallbackApp: TvApp? = null,
)

/** A TV remote that talks over a WebSocket (Samsung and LG): what the phone's buttons and keyboard need from it. */
internal interface TvSocketRemote : Closeable {
    /** True when button [code] does something on this TV. */
    fun supports(code: Int): Boolean

    fun press(code: Int)

    /** The key held long enough to count as a long press (blocks for about a second where the TV has it; else a plain press). */
    fun longPress(code: Int)

    /** The text box on the phone changed from [old] to [new]: do the same on the TV. */
    fun typed(old: String, new: String)

    fun openUrl(url: String)

    fun launchApp(appId: String) {}

    fun switchInput(inputId: String) {}

    fun pointerMove(dx: Int, dy: Int, drag: Boolean = false) {}

    fun pointerClick() {}

    /** Why the connection ended, if it did. */
    val failure: Throwable?
}

/** One port of a TV's remote service, and whether it is TLS. */
internal class Endpoint(val port: Int, val secure: Boolean)

/**
 * True when a connection that failed with [e] is worth another try on the next port: an ordinary failure, not a "No" from the
 * TV's owner, not a wait for its owner that ran out, and not a certificate that isn't the one remembered.
 */
internal fun canTryNextPort(e: Throwable): Boolean =
    e is IOException && e !is TvRefusedException && e !is OwnerWaitTimeout && !isPinMismatch(e)

/** True when [e] failed because the TV's certificate isn't the one remembered from pairing. */
internal fun isPinMismatch(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it is CertificateException }

/** Reads and drops what a TV sends, for as long as the connection lives; reports when it ends. */
private fun startReader(
    name: String,
    socket: WebSocketClient,
    onMessage: ((String) -> Unit)? = null,
    ended: (Throwable) -> Unit,
) {
    Thread({
        try {
            while (true) {
                val text = socket.readTextKeepingAlive(20_000)
                onMessage?.invoke(text)
            }
        } catch (e: Throwable) {
            ended(e)
        }
    }, name).apply { isDaemon = true }.start()
}

// --- Samsung (Tizen) ------------------------------------------------------------------------------------------

/**
 * Samsung smart TVs from 2016 on. The phone opens a WebSocket to the TV's remote channel; the first time the TV asks on
 * screen to Allow it and gives the phone a token, which the phone keeps and shows next time.
 */
object SamsungTvCommands {
    const val SECURE_PORT = 8002
    const val PLAIN_PORT = 8001

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    /** The address of the remote channel. The name is what the TV shows in its "Allow?" question. */
    fun path(name: String, token: String?): String =
        "/api/v2/channels/samsung.remote.control?name=" + encode(Base64Lite.encode(name)) + (token?.let { "&token=" + encode(it) } ?: "")

    fun key(name: String, cmd: String = "Click"): String =
        """{"method":"ms.remote.control","params":{"Cmd":"$cmd","DataOfCmd":"$name","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""

    /** The whole text of the box (the TV replaces its own text with it). */
    fun text(text: String): String =
        """{"method":"ms.remote.control","params":{"Cmd":"${Base64Lite.encode(text)}","DataOfCmd":"base64","TypeOfRemote":"SendInputString"}}"""

    /** Some TVs want this once before they take text. */
    fun textReceived(): String = """{"method":"ms.channel.emit","params":{"event":"custom.remote.textReceived","to":"broadcast"}}"""

    fun openBrowser(url: String): String =
        """{"method":"ms.channel.emit","params":{"event":"ed.apps.launch","to":"host","data":{"appId":"org.tizen.browser","action_type":"NATIVE_LAUNCH","metaTag":${FireTvCommands.jsonString(url)}}}}"""

    fun launchApp(appId: String): String =
        """{"method":"ms.channel.emit","params":{"event":"ed.apps.launch","to":"host","data":{"appId":"$appId","action_type":"DEEP_LINKING"}}}"""

    fun event(message: String): String? = Regex(""""event"\s*:\s*"([^"]{1,60})"""").find(message)?.groupValues?.get(1)

    /** The token in the TV's "connected" message, only if it is made of safe characters. */
    fun token(message: String): String? =
        Regex(""""token"\s*:\s*"([A-Za-z0-9._~+/=-]{1,200})"""").find(message)?.groupValues?.get(1)

    fun keyName(code: Int): String? = when (code) {
        TvKey.UP -> "KEY_UP"
        TvKey.DOWN -> "KEY_DOWN"
        TvKey.LEFT -> "KEY_LEFT"
        TvKey.RIGHT -> "KEY_RIGHT"
        TvKey.OK, TvKey.ENTER -> "KEY_ENTER"
        TvKey.BACK -> "KEY_RETURN"
        TvKey.HOME -> "KEY_HOME"
        TvKey.MENU -> "KEY_MENU"
        TvKey.REWIND -> "KEY_REWIND"
        TvKey.FAST_FORWARD -> "KEY_FF"
        TvKey.VOLUME_UP -> "KEY_VOLUP"
        TvKey.VOLUME_DOWN -> "KEY_VOLDOWN"
        TvKey.MUTE -> "KEY_MUTE"
        TvKey.POWER -> "KEY_POWER"
        TvKey.CHANNEL_UP -> "KEY_CHUP"
        TvKey.CHANNEL_DOWN -> "KEY_CHDOWN"
        TvKey.GUIDE -> "KEY_GUIDE"
        TvKey.INFO -> "KEY_INFO"
        TvKey.TV_INPUT -> "KEY_SOURCE"
        TvKey.DIGIT_0 -> "KEY_0"
        TvKey.DIGIT_1 -> "KEY_1"
        TvKey.DIGIT_2 -> "KEY_2"
        TvKey.DIGIT_3 -> "KEY_3"
        TvKey.DIGIT_4 -> "KEY_4"
        TvKey.DIGIT_5 -> "KEY_5"
        TvKey.DIGIT_6 -> "KEY_6"
        TvKey.DIGIT_7 -> "KEY_7"
        TvKey.DIGIT_8 -> "KEY_8"
        TvKey.DIGIT_9 -> "KEY_9"
        TvKey.PROG_RED -> "KEY_RED"
        TvKey.PROG_GREEN -> "KEY_GREEN"
        TvKey.PROG_YELLOW -> "KEY_YELLOW"
        TvKey.PROG_BLUE -> "KEY_BLUE"
        else -> null
    }
}

internal class SamsungTvLink private constructor(
    private val socket: WebSocketClient,
    private val onClosed: (Throwable) -> Unit,
) : TvSocketRemote {
    /** A TV that was connected to: the link, and what is to be remembered about the TV. */
    class Opened(val link: SamsungTvLink, val token: String?, val fingerprint: String?)

    @Volatile private var closed = false
    @Volatile override var failure: Throwable? = null
        private set
    private var playing = true // there is no play/pause toggle key: pause first, then play, and so on
    private var textStarted = false

    override fun supports(code: Int) = code == TvKey.PLAY_PAUSE || SamsungTvCommands.keyName(code) != null

    override fun press(code: Int) {
        val name = if (code == TvKey.PLAY_PAUSE) {
            playing = !playing
            if (playing) "KEY_PLAY" else "KEY_PAUSE"
        } else {
            SamsungTvCommands.keyName(code) ?: return
        }
        socket.sendText(SamsungTvCommands.key(name))
    }

    override fun longPress(code: Int) {
        val name = SamsungTvCommands.keyName(code)
        if (name == null) {
            press(code)
            return
        }
        socket.sendText(SamsungTvCommands.key(name, "Press"))
        Thread.sleep(LONG_PRESS_MS) // the sender runs on its own thread; later buttons wait this one second
        socket.sendText(SamsungTvCommands.key(name, "Release"))
    }

    override fun typed(old: String, new: String) {
        if (!textStarted) {
            socket.sendText(SamsungTvCommands.textReceived())
            textStarted = true
        }
        socket.sendText(SamsungTvCommands.text(new))
    }

    override fun openUrl(url: String) = socket.sendText(SamsungTvCommands.openBrowser(url))

    override fun launchApp(appId: String) {
        socket.sendText(SamsungTvCommands.launchApp(appId))
    }

    override fun close() {
        closed = true
        socket.close()
    }

    private fun ended(error: Throwable) {
        if (closed) return
        closed = true
        failure = error
        socket.close()
        onClosed(error)
    }

    companion object {
        /** The TLS port first (newer TVs), then the plain one (older ones). */
        val ENDPOINTS = listOf(Endpoint(SamsungTvCommands.SECURE_PORT, true), Endpoint(SamsungTvCommands.PLAIN_PORT, false))
        private const val LONG_PRESS_MS = 900L
        private const val ALLOW_WAIT_MS = 60_000

        /**
         * Connects, and waits for the TV to say so. With no [token] the TV asks on screen to Allow this phone, which can take a
         * while ([ALLOW_WAIT_MS]). Throws [TvRefusedException] when the answer is No (or the TV doesn't know the token).
         */
        fun open(
            host: String,
            name: String,
            token: String?,
            pinned: String?,
            endpoints: List<Endpoint>,
            onSocket: (Closeable) -> Unit,
            onClosed: (Throwable) -> Unit,
        ): Opened {
            var last: IOException? = null
            for (endpoint in endpoints) {
                val path = SamsungTvCommands.path(name, if (endpoint.secure) token else null) // the token goes with TLS only
                val ws = try {
                    WebSocketClient.connect(host, endpoint.port, path, endpoint.secure, if (endpoint.secure) pinned else null, onSocket)
                } catch (e: IOException) {
                    if (isPinMismatch(e)) throw e
                    last = e
                    continue
                }
                try {
                    while (true) {
                        val message = try {
                            ws.readText(ALLOW_WAIT_MS)
                        } catch (e: SocketTimeoutException) {
                            throw OwnerWaitTimeout() // the TV is waiting for its owner
                        }
                        when (SamsungTvCommands.event(message)) {
                            "ms.channel.connect" -> {
                                val link = SamsungTvLink(ws, onClosed)
                                startReader("caster-samsung-reader", ws, null, link::ended)
                                return Opened(link, SamsungTvCommands.token(message) ?: token.takeIf { endpoint.secure }, ws.serverFingerprint)
                            }
                            "ms.channel.unauthorized", "ms.channel.timeOut" -> throw TvRefusedException("The TV didn't allow this phone")
                        }
                    }
                } catch (e: Throwable) {
                    ws.close()
                    if (canTryNextPort(e)) { // e.g. the secure port took the connection but then hung up: try the plain one
                        last = e as IOException
                        continue
                    }
                    throw e
                }
            }
            throw last ?: IOException("No connection")
        }
    }
}

// --- LG (webOS) ------------------------------------------------------------------------------------------------

/**
 * LG smart TVs with webOS. The phone registers with the TV over a WebSocket; the first time the TV asks on screen to
 * accept it and gives the phone a key, which the phone keeps. Buttons then go through a second socket the TV hands out.
 */
object LgTvCommands {
    const val SECURE_PORT = 3001
    const val PLAIN_PORT = 3000
    const val INPUT_SOCKET = "com.webos.service.networkinput/getPointerInputSocket"

    fun hello(): String = """{"id":"hello","type":"hello","payload":{}}"""

    /** Newer webOS wants this asked before registering. */
    fun systemInfo(): String = """{"id":"get_sys_info","type":"request","uri":"ssap://system/getSystemInfo","payload":{}}"""

    /** The request to register this phone. [signed] is the manifest LG's own sample app uses; newer TVs refuse it, and get the unsigned one. */
    fun register(clientKey: String?, signed: Boolean): String {
        val key = clientKey?.let { "\"client-key\":${FireTvCommands.jsonString(it)}," } ?: ""
        val manifest = if (signed) MANIFEST_SIGNED else MANIFEST_UNSIGNED
        return """{"type":"register","id":"register_0","payload":{$key"forcePairing":false,"manifest":$manifest,"pairingType":"PROMPT"}}"""
    }

    fun request(id: Int, uri: String, payload: String = "{}"): String = """{"id":$id,"type":"request","uri":"ssap://$uri","payload":$payload}"""

    /** A button on the second socket. */
    fun button(name: String): String = "type:button\nname:$name\n\n"

    fun type(message: String): String? = Regex(""""type"\s*:\s*"([A-Za-z]+)"""").find(message)?.groupValues?.get(1)

    /** True for the TV's "waiting for the owner to accept" answer to a registration. */
    fun isPrompt(message: String): Boolean = Regex(""""pairingType"\s*:\s*"PROMPT"""").containsMatchIn(message)

    fun clientKey(message: String): String? =
        Regex(""""client-key"\s*:\s*"([A-Za-z0-9._~+/=-]{1,200})"""").find(message)?.groupValues?.get(1)

    fun socketPath(message: String): String? =
        Regex(""""socketPath"\s*:\s*"([^"]{1,300})"""").find(message)?.groupValues?.get(1)?.replace("\\/", "/")

    fun deleteCharacters(count: Int) = "com.webos.service.ime/deleteCharacters" to """{"count":$count}"""

    fun insertText(text: String) = "com.webos.service.ime/insertText" to """{"text":${FireTvCommands.jsonString(text)},"replace":0}"""

    fun openUrl(url: String) = "system.launcher/open" to """{"target":${FireTvCommands.jsonString(url)}}"""

    fun launchApp(appId: String) = "system.launcher/launch" to """{"id":${FireTvCommands.jsonString(appId)}}"""

    fun switchInput(inputId: String) = "tv/switchInput" to """{"inputId":${FireTvCommands.jsonString(inputId)}}"""

    const val GET_EXTERNAL_INPUTS = "tv/getExternalInputList"
    const val GET_LAUNCH_POINTS = "com.webos.applicationManager/listLaunchPoints"
    const val GET_FOREGROUND_APP = "com.webos.applicationManager/getForegroundAppInfo"

    fun move(dx: Int, dy: Int, drag: Boolean = false) = "type:move\ndx:$dx\ndy:$dy\ndown:${if (drag) 1 else 0}\n\n"

    fun click() = "type:click\n\n"

    const val ENTER_KEY = "com.webos.service.ime/sendEnterKey"

    fun buttonName(code: Int): String? = when (code) {
        TvKey.UP -> "UP"
        TvKey.DOWN -> "DOWN"
        TvKey.LEFT -> "LEFT"
        TvKey.RIGHT -> "RIGHT"
        TvKey.OK -> "ENTER"
        TvKey.BACK -> "BACK"
        TvKey.HOME -> "HOME"
        TvKey.MENU -> "MENU"
        TvKey.REWIND -> "REWIND"
        TvKey.FAST_FORWARD -> "FASTFORWARD"
        TvKey.VOLUME_UP -> "VOLUMEUP"
        TvKey.VOLUME_DOWN -> "VOLUMEDOWN"
        TvKey.MUTE -> "MUTE"
        TvKey.CHANNEL_UP -> "CHANNELUP"
        TvKey.CHANNEL_DOWN -> "CHANNELDOWN"
        TvKey.GUIDE -> "GUIDE"
        TvKey.INFO -> "INFO"
        TvKey.DIGIT_0 -> "0"
        TvKey.DIGIT_1 -> "1"
        TvKey.DIGIT_2 -> "2"
        TvKey.DIGIT_3 -> "3"
        TvKey.DIGIT_4 -> "4"
        TvKey.DIGIT_5 -> "5"
        TvKey.DIGIT_6 -> "6"
        TvKey.DIGIT_7 -> "7"
        TvKey.DIGIT_8 -> "8"
        TvKey.DIGIT_9 -> "9"
        TvKey.PROG_RED -> "RED"
        TvKey.PROG_GREEN -> "GREEN"
        TvKey.PROG_YELLOW -> "YELLOW"
        TvKey.PROG_BLUE -> "BLUE"
        else -> null
    }

    /** Extracts JSON object slices inside an array field such as `"devices":[{...},{...}]`. */
    private fun extractArrayObjects(json: String, arrayKey: String): List<String> {
        val keyIdx = json.indexOf("\"$arrayKey\"")
        if (keyIdx < 0) return emptyList()
        val bracketStart = json.indexOf('[', keyIdx)
        if (bracketStart < 0) return emptyList()
        val items = mutableListOf<String>()
        var depth = 0
        var objStart = -1
        var inString = false
        var escape = false
        for (i in bracketStart until json.length) {
            val c = json[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> {
                    if (depth == 1) objStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 1 && objStart >= 0) {
                        items += json.substring(objStart, i + 1)
                        objStart = -1
                    }
                }
                '[' -> if (depth == 0) depth = 1
                ']' -> if (depth == 1) break
            }
        }
        return items
    }

    private fun jsonStr(obj: String, key: String): String? =
        Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(obj)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")?.replace("\\/", "/")

    private fun jsonBool(obj: String, key: String): Boolean? =
        Regex(""""$key"\s*:\s*(true|false)""").find(obj)?.groupValues?.get(1)?.toBooleanStrictOrNull()

    fun parseExternalInputs(message: String): List<TvInput> {
        val objs = extractArrayObjects(message, "devices")
        if (objs.isEmpty()) return emptyList()
        return objs.mapNotNull { obj ->
            val id = jsonStr(obj, "id") ?: return@mapNotNull null
            val label = jsonStr(obj, "label")?.takeIf { it.isNotBlank() } ?: id
            val connected = jsonBool(obj, "connected") ?: false
            val appId = jsonStr(obj, "appId")
            val lower = (label + " " + id).lowercase()
            val kind = when {
                "stb" in lower || "set-top" in lower || "settop" in lower || "cable" in lower -> TvInput.InputKind.STB
                "live tv" in lower || "antenna" in lower || id.equals("tv", ignoreCase = true) -> TvInput.InputKind.LIVE_TV
                "hdmi" in lower -> TvInput.InputKind.HDMI
                "av" in lower || "comp" in lower -> TvInput.InputKind.AV
                else -> TvInput.InputKind.OTHER
            }
            TvInput(id = id, label = label, kind = kind, connected = connected, appId = appId)
        }
    }

    fun parseLaunchPoints(message: String): List<InstalledTvApp> {
        val objs = extractArrayObjects(message, "launchPoints")
        if (objs.isEmpty()) return emptyList()
        return objs.mapNotNull { obj ->
            val id = jsonStr(obj, "id") ?: return@mapNotNull null
            val title = jsonStr(obj, "title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (jsonBool(obj, "systemApp") == true && title.startsWith("com.")) return@mapNotNull null
            InstalledTvApp(id = id, title = title)
        }
    }

    fun parseForegroundAppId(message: String): String? {
        if (!message.contains("\"appId\"")) return null
        return jsonStr(message, "appId")
    }

    // LG's sample registration (the one every open-source LG remote uses).
    private const val MANIFEST_SIGNED = """{"appVersion":"1.1","manifestVersion":1,"permissions":["LAUNCH","LAUNCH_WEBAPP","APP_TO_APP","CLOSE","TEST_OPEN","TEST_PROTECTED","CONTROL_AUDIO","CONTROL_DISPLAY","CONTROL_INPUT_JOYSTICK","CONTROL_INPUT_MEDIA_RECORDING","CONTROL_INPUT_MEDIA_PLAYBACK","CONTROL_INPUT_TV","CONTROL_POWER","CONTROL_TV_SCREEN","READ_APP_STATUS","READ_CURRENT_CHANNEL","READ_INPUT_DEVICE_LIST","READ_NETWORK_STATE","READ_RUNNING_APPS","READ_TV_CHANNEL_LIST","WRITE_NOTIFICATION_TOAST","READ_POWER_STATE","READ_COUNTRY_INFO","CONTROL_INPUT_TEXT","CONTROL_MOUSE_AND_KEYBOARD","READ_INSTALLED_APPS","READ_SETTINGS"],"signatures":[{"signature":"eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw==","signatureVersion":1}],"signed":{"appId":"com.lge.test","created":"20140509","localizedAppNames":{"":"LG Remote App","ko-KR":"\ub9ac\ubaa8\ucee8 \uc571","zxx-XX":"\u041b\u0413 R\u044d\u043cot\u044d A\u041f\u041f"},"localizedVendorNames":{"":"LG Electronics"},"permissions":["TEST_SECURE","CONTROL_INPUT_TEXT","CONTROL_MOUSE_AND_KEYBOARD","READ_INSTALLED_APPS","READ_LGE_SDX","READ_NOTIFICATIONS","SEARCH","WRITE_SETTINGS","WRITE_NOTIFICATION_ALERT","CONTROL_POWER","READ_CURRENT_CHANNEL","READ_RUNNING_APPS","READ_UPDATE_INFO","UPDATE_FROM_REMOTE_APP","READ_LGE_TV_INPUT_EVENTS","READ_TV_CURRENT_TIME"],"serial":"2f930e2d2cfe083771f68e4fe7bb07","vendorId":"com.lge"}}"""
    private const val MANIFEST_UNSIGNED = """{"appVersion":"1.1","manifestVersion":1,"permissions":["APP_TO_APP","CLOSE","CONTROL_AUDIO","CONTROL_DISPLAY","CONTROL_INPUT_JOYSTICK","CONTROL_INPUT_MEDIA_PLAYBACK","CONTROL_INPUT_MEDIA_RECORDING","CONTROL_INPUT_TEXT","CONTROL_INPUT_TV","CONTROL_MOUSE_AND_KEYBOARD","CONTROL_POWER","CONTROL_TV_SCREEN","LAUNCH","LAUNCH_WEBAPP","READ_APP_STATUS","READ_COUNTRY_INFO","READ_CURRENT_CHANNEL","READ_INPUT_DEVICE_LIST","READ_INSTALLED_APPS","READ_LGE_SDX","READ_LGE_TV_INPUT_EVENTS","READ_NETWORK_STATE","READ_NOTIFICATIONS","READ_POWER_STATE","READ_RUNNING_APPS","READ_SETTINGS","READ_TV_CHANNEL_LIST","READ_TV_CURRENT_TIME","READ_UPDATE_INFO","SEARCH","TEST_OPEN","TEST_PROTECTED","TEST_SECURE","UPDATE_FROM_REMOTE_APP","WRITE_NOTIFICATION_ALERT","WRITE_NOTIFICATION_TOAST","WRITE_SETTINGS"]}"""
}

internal class LgTvLink private constructor(
    private val main: WebSocketClient,
    private val input: WebSocketClient,
    private val onClosed: (Throwable) -> Unit,
) : TvSocketRemote {
    class Opened(val link: LgTvLink, val clientKey: String, val fingerprint: String?)

    @Volatile private var closed = false
    @Volatile override var failure: Throwable? = null
        private set
    private val ids = AtomicInteger(1)
    private var playing = true // the pause and play buttons are separate: pause first, then play, and so on
    @Volatile var onInputsLoaded: ((List<TvInput>) -> Unit)? = null
    @Volatile var onAppsLoaded: ((List<InstalledTvApp>) -> Unit)? = null
    @Volatile var onForegroundApp: ((String) -> Unit)? = null

    override fun supports(code: Int) =
        LgTvCommands.buttonName(code) != null || code == TvKey.PLAY_PAUSE || code == TvKey.ENTER || code == TvKey.DELETE || code == TvKey.POWER || code == TvKey.TV_INPUT

    override fun press(code: Int) {
        when (code) {
            TvKey.ENTER -> request(LgTvCommands.ENTER_KEY, "{}")
            TvKey.DELETE -> request(LgTvCommands.deleteCharacters(1))
            TvKey.POWER -> request("system/turnOff", "{}")
            TvKey.TV_INPUT -> request("system.launcher/launch", """{"id":"com.webos.app.connectionmanager"}""")
            TvKey.PLAY_PAUSE -> {
                playing = !playing
                input.sendText(LgTvCommands.button(if (playing) "PLAY" else "PAUSE"))
            }
            else -> input.sendText(LgTvCommands.button(LgTvCommands.buttonName(code) ?: return))
        }
    }

    override fun longPress(code: Int) = press(code) // the TV's buttons have no "held"

    override fun typed(old: String, new: String) {
        var same = 0
        while (same < old.length && same < new.length && old[same] == new[same]) same++
        val removed = old.length - same
        if (removed > 0) request(LgTvCommands.deleteCharacters(removed))
        if (new.length > same) request(LgTvCommands.insertText(new.substring(same)))
    }

    override fun openUrl(url: String) = request(LgTvCommands.openUrl(url))

    override fun launchApp(appId: String) {
        request(LgTvCommands.launchApp(appId))
    }

    override fun switchInput(inputId: String) {
        request(LgTvCommands.switchInput(inputId))
    }

    fun refreshMetadata() {
        request(LgTvCommands.GET_EXTERNAL_INPUTS, "{}")
        request(LgTvCommands.GET_LAUNCH_POINTS, "{}")
        request(LgTvCommands.GET_FOREGROUND_APP, "{}")
    }

    internal fun handleMainMessage(message: String) {
        if (LgTvCommands.type(message) != "response") return
        when {
            message.contains("\"devices\"") -> {
                val inputs = LgTvCommands.parseExternalInputs(message)
                if (inputs.isNotEmpty()) onInputsLoaded?.invoke(inputs)
            }
            message.contains("\"launchPoints\"") -> {
                val apps = LgTvCommands.parseLaunchPoints(message)
                if (apps.isNotEmpty()) onAppsLoaded?.invoke(apps)
            }
            message.contains("\"appId\"") -> {
                LgTvCommands.parseForegroundAppId(message)?.let { onForegroundApp?.invoke(it) }
            }
        }
    }

    override fun pointerMove(dx: Int, dy: Int, drag: Boolean) {
        input.sendText(LgTvCommands.move(dx, dy, drag))
    }

    override fun pointerClick() {
        input.sendText(LgTvCommands.click())
    }

    private fun request(call: Pair<String, String>) = request(call.first, call.second)

    private fun request(uri: String, payload: String) = main.sendText(LgTvCommands.request(ids.getAndIncrement(), uri, payload))

    override fun close() {
        closed = true
        main.close()
        input.close()
    }

    private fun ended(error: Throwable) {
        if (closed) return
        closed = true
        failure = error
        main.close()
        input.close()
        onClosed(error)
    }

    companion object {
        val ENDPOINTS = listOf(Endpoint(LgTvCommands.SECURE_PORT, true), Endpoint(LgTvCommands.PLAIN_PORT, false))
        private const val STEP_MS = 10_000
        private const val ACCEPT_WAIT_MS = 60_000

        /**
         * Connects and registers. With no [clientKey] the TV asks on screen to accept this phone, which can take a while.
         * Throws [TvRefusedException] when the answer is No.
         */
        fun open(
            host: String,
            clientKey: String?,
            pinned: String?,
            endpoints: List<Endpoint>,
            onSocket: (Closeable) -> Unit,
            onClosed: (Throwable) -> Unit,
        ): Opened {
            var last: IOException? = null
            val tried = mutableListOf<String>() // one line per port, so a failure says where each attempt stopped
            var key = clientKey // once the TV has given a key, the next port reuses it instead of asking the owner again
            for (endpoint in endpoints) {
                // A TV that was paired over TLS is never talked to without it: the key would go out in the clear.
                if (pinned != null && !endpoint.secure) continue
                val main = try {
                    // (LG's own apps and every known remote library send no Origin header, so none is sent here.)
                    WebSocketClient.connect(host, endpoint.port, "/", endpoint.secure, if (endpoint.secure) pinned else null, onSocket, sendOrigin = false)
                } catch (e: IOException) {
                    if (isPinMismatch(e)) throw e
                    tried += "port ${endpoint.port}: couldn't connect (${e.message ?: e.javaClass.simpleName})"
                    last = e
                    continue
                }
                var input: WebSocketClient? = null
                var stage = "registering"
                try {
                    val registered = register(main, key)
                    key = registered
                    stage = "opening the button channel"
                    input = openInput(main, host, endpoint, pinned, onSocket)
                    val link = LgTvLink(main, input, onClosed)
                    startReader("caster-lg-reader", main, link::handleMainMessage, link::ended)
                    startReader("caster-lg-input", input, null, link::ended)
                    return Opened(link, registered, main.serverFingerprint)
                } catch (e: Throwable) {
                    main.close()
                    input?.close()
                    if (canTryNextPort(e)) { // older webOS only has the plain port, and a TV may take the secure one and then hang up
                        tried += "port ${endpoint.port}: stopped while $stage (${e.message ?: e.javaClass.simpleName})"
                        last = e as IOException
                        continue
                    }
                    throw e
                }
            }
            val reason = last ?: throw IOException("No connection")
            // With one port tried the error stays as it is (a timeout stays a timeout); with two, the text tells what happened on each.
            throw if (tried.size > 1) IOException(tried.joinToString("; "), reason) else reason
        }

        /** The next message of one of the wanted [types] (others, such as notices, are skipped). */
        private fun await(ws: WebSocketClient, timeoutMs: Int, vararg types: String): String {
            var complaint: String? = null
            repeat(MAX_SKIPPED) {
                val message = ws.readText(timeoutMs)
                val type = LgTvCommands.type(message)
                if (type in types) return message
                if (type == "error") complaint = message.take(120) // what the TV said it didn't like, for the record
            }
            throw IOException(complaint?.let { "The TV answered with an error: $it" } ?: "The TV didn't answer as expected")
        }

        private const val MAX_SKIPPED = 10

        private fun register(main: WebSocketClient, clientKey: String?): String {
            main.sendText(LgTvCommands.hello())
            // Older webOS TVs respond to hello immediately; newer ones (webOS 4+, 2020+) skip it entirely and
            // expect the register request directly. Use a short timeout so new TVs don't wait 10 s doing nothing.
            try { await(main, 3_000, "hello") } catch (_: SocketTimeoutException) {}
            main.sendText(LgTvCommands.systemInfo())
            await(main, STEP_MS, "response", "error")
            main.sendText(LgTvCommands.register(clientKey, signed = true))
            var answer = await(main, STEP_MS, "response", "registered", "error")
            if (LgTvCommands.type(answer) == "error") { // newer TVs refuse the signed sample: try the plain one
                main.sendText(LgTvCommands.register(clientKey, signed = false))
                answer = await(main, STEP_MS, "response", "registered", "error")
            }
            if (LgTvCommands.type(answer) == "response" && LgTvCommands.isPrompt(answer)) {
                answer = try {
                    await(main, ACCEPT_WAIT_MS, "registered", "error") // the TV is asking its owner
                } catch (e: SocketTimeoutException) {
                    throw OwnerWaitTimeout()
                }
            }
            if (LgTvCommands.type(answer) != "registered") throw TvRefusedException("The TV didn't accept this phone (${answer.take(120)})")
            return LgTvCommands.clientKey(answer) ?: clientKey ?: throw TvRefusedException("The TV gave no key")
        }

        /** Asks for the second socket (the one that takes buttons) and opens it. It always goes to the TV that was connected to. */
        private fun openInput(main: WebSocketClient, host: String, endpoint: Endpoint, pinned: String?, onSocket: (Closeable) -> Unit): WebSocketClient {
            main.sendText(LgTvCommands.request(0, LgTvCommands.INPUT_SOCKET))
            var path: String? = null
            repeat(MAX_SKIPPED) {
                if (path == null) path = LgTvCommands.socketPath(main.readText(STEP_MS))
            }
            val match = Regex("""^(wss?)://[^/:]+(?::(\d+))?(/.*)$""").matchEntire(path ?: throw IOException("The TV gave no button socket"))
                ?: throw IOException("The TV's button socket address wasn't understood")
            // Whatever kind of connection the TV names is used: only button names go over it (the key was sent on the main one),
            // and it can only ever lead to the TV that was connected to, because only the port and the path are taken from it.
            val secure = match.groupValues[1] == "wss"
            val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65_535 } ?: endpoint.port
            return WebSocketClient.connect(
                host, port, match.groupValues[3], secure, if (secure) main.serverFingerprint ?: pinned else null, onSocket, sendOrigin = false,
            )
        }
    }
}
