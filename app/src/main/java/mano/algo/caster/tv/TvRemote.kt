package mano.algo.caster.tv

import android.content.Context
import android.os.Build
import dadb.AdbAuthException
import dadb.AdbKeyPair
import dadb.AdbShellResponse
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mano.algo.caster.server.NetworkUtils
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Uses the phone as a remote for a Fire TV (or another Android TV).
 *
 * Fire TV: like the official remote app. The TV shows a 4-digit code, you type it once, and the phone remembers it.
 * Nothing has to be switched on in the TV's settings.
 *
 * ADB (advanced, any Android TV): needs ADB debugging turned on in the TV's developer options; the TV asks once to
 * "Allow" this phone, which proves itself with its own key (made on first use, private to the app, left out of backups).
 */
object TvRemote {
    /** How the phone talks to the TV. */
    enum class Link { FireTv, Adb }

    sealed interface State {
        data object Idle : State

        /** Includes waiting for the owner to tap Allow on the TV (ADB). */
        data class Connecting(val host: String, val viaAdb: Boolean = false) : State

        /** The TV is showing a code; the owner types it on the phone. [error] is set after a wrong code. */
        data class NeedsPin(val host: String, val error: String? = null) : State
        data class Connected(val host: String, val link: Link) : State
        data class Failed(val message: String) : State
    }

    /**
     * Volume on a Fire TV. The Fire TV's own remote service has no volume buttons (Amazon's app can't change it either),
     * so volume goes through ADB, which has to be switched on once in the TV's developer options.
     */
    sealed interface VolumeState {
        data object Off : VolumeState

        /** Waiting for the owner to tap Allow on the TV. */
        data object Waiting : VolumeState
        data object Ready : VolumeState
        data class Failed(val message: String) : VolumeState
    }

    /** The ADB library needs Android 8 or newer (it uses file and Base64 classes added in that version). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private const val PREFS = "caster_tv"
    private const val KEY_HOST = "host"
    private const val KEY_TOKEN_PREFIX = "fire_token_"
    private const val KEY_VOLUME_PREFIX = "volume_on_"

    /** A held direction is released by itself after this long, so a lost "key up" can't leave the TV scrolling for ever. */
    private const val HOLD_LIMIT_MS = 8_000L

    /** How long a long press is kept down on a Fire TV (Android counts 0.5 s as a long press). */
    private const val LONG_PRESS_MS = 900L
    private const val APPROVAL_WAIT_MS = 60_000L

    /** An ADB command that takes longer than this is treated as a dead connection (a sleeping TV never answers). */
    private const val SHELL_DEADLINE_MS = 10_000L
    private const val WAKE_SETTLE_MS = 1_500L
    private const val PIN_TRIES = 4
    private const val DEVICE_NAME = "Caster"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _volume = MutableStateFlow<VolumeState>(VolumeState.Off)
    val volume: StateFlow<VolumeState> = _volume.asStateFlow()

    private val _savedHost = MutableStateFlow("")

    /** The TV address used last, so it doesn't have to be typed again. */
    val savedHost: StateFlow<String> = _savedHost.asStateFlow()

    private val _found = MutableStateFlow<List<String>>(emptyList())

    /** Addresses on this network that answered like a TV remote service, after a search. */
    val found: StateFlow<List<String>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // Read from the button handlers (main thread) and written from the connect and sender jobs.
    @Volatile private var connection: Dadb? = null
    @Volatile private var opening: Dadb? = null
    @Volatile private var fire: FireTvLink? = null
    @Volatile private var pairing: FireTvLink? = null
    @Volatile private var volumeAdb: Dadb? = null

    /**
     * The directions currently held down on a Fire TV (key code to the number of that hold; two fingers can hold two
     * directions), and whether this TV has shown it understands held keys.
     */
    private val holding = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val holdIds = AtomicInteger(0)
    @Volatile private var holdWorks = true
    private var volumeJob: Job? = null

    /** Bumped by every disconnect, so a job that was running for an older connection can tell and stay quiet. */
    @Volatile private var generation = 0
    private var connectJob: Job? = null
    private var sender: Job? = null

    // Commands go through one queue, in order, and none is dropped (every typed letter matters). A held button is the
    // exception: it skips a press while the TV is still working through earlier ones, so it can't build up a lag.
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (_savedHost.value.isEmpty()) {
            _savedHost.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, "").orEmpty()
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Common start of both connect methods; returns the parsed address, or null after showing why it can't go on. */
    private fun begin(context: Context, rawHost: String): TvCommands.Target? {
        if (!supported) {
            _state.value = State.Failed("The TV remote needs Android 8 or newer.")
            return null
        }
        appContext = context.applicationContext
        val target = TvCommands.parseTarget(rawHost)
        if (target == null) {
            _state.value = State.Failed("Enter the TV's address on your Wi-Fi, like 192.168.0.50.")
            return null
        }
        disconnect()
        _savedHost.value = rawHost.trim()
        prefs(context).edit().putString(KEY_HOST, rawHost.trim()).apply()
        return target
    }

    // --- Fire TV: pairing code ---------------------------------------------------------------------------------

    fun connectFire(context: Context, rawHost: String) {
        val target = begin(context, rawHost) ?: return
        val host = target.host
        val store = prefs(context)
        _state.value = State.Connecting(host)
        val mine = generation

        connectJob = scope.launch {
            try {
                val link = FireTvLink(host, store.getString(KEY_TOKEN_PREFIX + host, null))
                link.wake() // the TV's remote service may be asleep until it is poked
                delay(WAKE_SETTLE_MS)
                if (link.token != null) {
                    // Paired before: the first button press will tell if the TV has since forgotten this phone.
                    if (isActive && mine == generation) {
                        fire = link
                        _state.value = State.Connected(host, Link.FireTv)
                        startSender(mine)
                        autoEnableVolume(host)
                    }
                } else {
                    askForPin(link, mine)
                }
            } catch (e: Throwable) {
                if (isActive && mine == generation) _state.value = State.Failed("Couldn't connect to the TV: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Asks the TV to show its code. The service can take a moment to start after waking, so it tries a few times. */
    private suspend fun askForPin(link: FireTvLink, mine: Int) {
        repeat(PIN_TRIES) {
            try {
                link.send(FireTvCommands.requestPin(DEVICE_NAME))
                if (mine == generation) {
                    pairing = link
                    _state.value = State.NeedsPin(link.host)
                }
                return
            } catch (e: Exception) {
                if (mine != generation) return
                delay(1_500)
            }
        }
        if (mine == generation) {
            _state.value = State.Failed(
                "The TV didn't answer on ${link.host}. Check that it's on, on the same Wi-Fi as this phone, " +
                    "and not using a VPN. If it still fails, use \"Connect with ADB\" below.",
            )
        }
    }

    /** The owner typed the code the TV shows. */
    fun submitPin(context: Context, pin: String) {
        val link = pairing ?: return
        val host = link.host
        if (!FireTvCommands.isPin(pin.trim())) {
            _state.value = State.NeedsPin(host, "The code is 4 digits.")
            return
        }
        val store = prefs(context)
        val mine = generation
        connectJob = scope.launch {
            try {
                val token = FireTvCommands.tokenFrom(link.send(FireTvCommands.verifyPin(pin.trim())))
                if (!isActive || mine != generation) return@launch
                if (token == null) {
                    _state.value = State.NeedsPin(host, "That code didn't work. Check the TV and try again.")
                    return@launch
                }
                link.token = token
                store.edit().putString(KEY_TOKEN_PREFIX + host, token).apply()
                pairing = null
                fire = link
                _state.value = State.Connected(host, Link.FireTv)
                startSender(mine)
                autoEnableVolume(host)
            } catch (e: Throwable) {
                if (isActive && mine == generation) {
                    _state.value = State.NeedsPin(host, "That code didn't work. Check the TV and try again.")
                }
            }
        }
    }

    // --- ADB -------------------------------------------------------------------------------------------------

    fun connectAdb(context: Context, rawHost: String) {
        val target = begin(context, rawHost) ?: return
        val host = rawHost.trim()
        val app = context.applicationContext
        _state.value = State.Connecting(host, viaAdb = true)
        val mine = generation

        connectJob = scope.launch {
            var adb: Dadb? = null
            val gaveUp = AtomicBoolean(false)
            try {
                if (!reachable(target.host, target.port)) {
                    if (isActive) _state.value = State.Failed("Couldn't reach $host. Is the TV on, with ADB debugging turned on?")
                    return@launch
                }
                // Socket timeout 0: the connection may sit idle for a long time between button presses.
                val opened = Dadb.create(target.host, target.port, keyPair(app), 3_000, 0)
                adb = opened
                opening = opened // so Cancel can close it and end the wait for Allow
                // The first command waits for the owner to tap Allow on the TV; closing the connection ends that wait.
                val patience = launch {
                    delay(APPROVAL_WAIT_MS)
                    gaveUp.set(true)
                    opened.close()
                }
                opened.shell("echo caster")
                patience.cancel()
                opening = null
                connection = opened
                if (!isActive || mine != generation) {
                    // Cancelled while the answer was arriving: don't come back to life after the owner pressed Cancel.
                    connection = null
                    opened.close()
                    return@launch
                }
                _state.value = State.Connected(host, Link.Adb)
                startSender(mine)
            } catch (e: Throwable) {
                runCatching { adb?.close() }
                if (opening === adb) opening = null
                // After Cancel or a newer Connect this job is stale: stay quiet, the new state is already shown.
                if (!isActive || mine != generation) return@launch
                _state.value = State.Failed(
                    when {
                        gaveUp.get() -> "The TV didn't answer the \"Allow\" prompt in time. Try again and choose Allow on the TV."
                        e is AdbAuthException -> "The TV didn't accept this phone. Choose Allow when the TV asks."
                        else -> "Couldn't connect to the TV: ${e.message ?: e.javaClass.simpleName}"
                    },
                )
            }
        }
    }

    // --- Shared ----------------------------------------------------------------------------------------------

    fun disconnect() {
        generation++
        connectJob?.cancel()
        sender?.cancel()
        runCatching { opening?.close() }
        opening = null
        runCatching { connection?.close() }
        connection = null
        fire = null
        pairing = null
        holding.clear()
        closeVolume()
        while (commands.tryReceive().isSuccess) { /* drop anything still queued for the old connection */ }
        pending.set(0)
        _state.value = State.Idle
    }

    private fun closeVolume() {
        volumeJob?.cancel()
        runCatching { volumeAdb?.close() }
        volumeAdb = null
        _volume.value = VolumeState.Off
    }

    /** After connecting to a Fire TV that had volume turned on before, bring it back without another tap. */
    private fun autoEnableVolume(host: String) {
        val app = appContext ?: return
        if (prefs(app).getBoolean(KEY_VOLUME_PREFIX + host, false)) enableVolume(app)
    }

    /** Fire TV: turns on volume by opening ADB to the same TV. The TV asks once to Allow this phone. */
    fun enableVolume(context: Context) {
        val link = fire ?: return
        if (!supported) {
            _volume.value = VolumeState.Failed("Volume needs Android 8 or newer.")
            return
        }
        val app = context.applicationContext
        val mine = generation
        closeVolume()
        _volume.value = VolumeState.Waiting
        volumeJob = scope.launch {
            var adb: Dadb? = null
            val gaveUp = AtomicBoolean(false)
            try {
                if (!reachable(link.host, TvCommands.DEFAULT_PORT)) {
                    if (isActive && mine == generation) {
                        _volume.value = VolumeState.Failed(
                            "Couldn't reach ADB on the TV. Turn on ADB debugging in the TV's developer options first.",
                        )
                    }
                    return@launch
                }
                val opened = Dadb.create(link.host, TvCommands.DEFAULT_PORT, keyPair(app), 3_000, 0)
                adb = opened
                volumeAdb = opened // so Disconnect can close it while it waits for Allow
                val patience = launch {
                    delay(APPROVAL_WAIT_MS)
                    gaveUp.set(true)
                    opened.close()
                }
                opened.shell("echo caster")
                patience.cancel()
                if (!isActive || mine != generation) {
                    opened.close()
                    if (volumeAdb === opened) volumeAdb = null
                    return@launch
                }
                prefs(app).edit().putBoolean(KEY_VOLUME_PREFIX + link.host, true).apply()
                _volume.value = VolumeState.Ready
            } catch (e: Throwable) {
                runCatching { adb?.close() }
                if (volumeAdb === adb) volumeAdb = null
                if (!isActive || mine != generation) return@launch
                _volume.value = VolumeState.Failed(
                    when {
                        gaveUp.get() -> "The TV didn't answer the \"Allow\" prompt in time. Try again and choose Allow on the TV."
                        e is AdbAuthException -> "The TV didn't accept this phone. Choose Allow when the TV asks."
                        else -> "Couldn't turn on volume: ${e.message ?: e.javaClass.simpleName}"
                    },
                )
            }
        }
    }

    private fun sendVolume(code: Int) {
        val adb = volumeAdb ?: return
        if (_volume.value != VolumeState.Ready) return
        enqueue {
            try {
                shellGuarded(adb, TvCommands.key(code))
            } catch (e: Exception) {
                // Only volume is affected: the rest of the remote keeps working.
                runCatching { adb.close() }
                if (volumeAdb === adb) volumeAdb = null
                _volume.value = VolumeState.Failed("Volume stopped working. Turn it on again.")
            }
        }
    }

    private fun startSender(mine: Int) {
        sender?.cancel()
        sender = scope.launch {
            for (task in commands) {
                try {
                    task()
                    if (mine == generation) pending.decrementAndGet() // a finished task of an old connection isn't counted
                } catch (e: Throwable) {
                    // The TV went to sleep, left the network, turned ADB off or forgot this phone. (If the owner pressed
                    // Disconnect, this job was cancelled first and the screen already says so.)
                    if (isActive && mine == generation) lost(e)
                    break
                }
            }
        }
    }

    private fun lost(error: Throwable) {
        val link = fire
        // A TV that refuses the token has forgotten this phone: drop the saved pairing so the next connect asks for a code.
        if (error is FireTvAuthException && link != null) {
            appContext?.let { prefs(it).edit().remove(KEY_TOKEN_PREFIX + link.host).apply() }
        }
        runCatching { connection?.close() }
        connection = null
        fire = null
        closeVolume()
        while (commands.tryReceive().isSuccess) { /* nothing more can be sent */ }
        pending.set(0)
        _state.value = State.Failed(
            if (error is FireTvAuthException && link != null) "The TV has forgotten this phone. Connect again and enter the new code."
            else "Lost the connection to the TV. Connect again.",
        )
    }

    /**
     * Runs [command] on the TV. The ADB connection has no read timeout (it sits idle between presses), so a TV that went
     * to sleep or left the network would make this wait forever and freeze every later button. A watchdog closes the
     * connection after [SHELL_DEADLINE_MS], which turns the wait into an error that is handled like any lost connection.
     */
    private fun shellGuarded(adb: Dadb, command: String): AdbShellResponse {
        val guard = scope.launch {
            delay(SHELL_DEADLINE_MS)
            runCatching { adb.close() }
        }
        try {
            return adb.shell(command)
        } finally {
            guard.cancel()
        }
    }

    private fun enqueue(task: () -> Unit) {
        pending.incrementAndGet()
        commands.trySend(task)
    }

    /** True when button [code] does something on the connected TV (the Fire TV service has no volume or Enter, for example). */
    fun supportsKey(link: Link, code: Int) = link == Link.Adb || FireTvCommands.supports(code)

    /** One press of [code]. With [repeating] (a held button) a press is skipped while the TV is still catching up. */
    fun key(code: Int, repeating: Boolean = false) {
        if (repeating && pending.get() >= 1) return // one at a time: an even pace instead of bursts and gaps
        val link = fire
        // A held fast-forward would keep asking the Fire TV to speed up; only a single press is sent there.
        if (repeating && link != null && (code == TvKey.FAST_FORWARD || code == TvKey.REWIND)) return
        val adb = connection
        if (link != null && (code == TvKey.VOLUME_UP || code == TvKey.VOLUME_DOWN || code == TvKey.MUTE)) {
            sendVolume(code)
            return
        }
        when {
            link != null -> if (FireTvCommands.supports(code)) enqueue { sendPress(link, code) }
            adb != null -> enqueue { shellGuarded(adb, TvCommands.key(code)) }
        }
    }

    /**
     * One complete press of [code] on a Fire TV. Fast-forward and rewind are sent as down and then up: a "down" alone is a
     * button that is never let go, which makes the TV scan faster and faster.
     */
    private fun sendPress(link: FireTvLink, code: Int) {
        val press = FireTvCommands.forKey(code) ?: return
        link.send(press)
        if (code == TvKey.FAST_FORWARD || code == TvKey.REWIND) {
            try {
                FireTvCommands.hold(code, down = false)?.let { link.send(it) }
            } catch (e: FireTvHttpException) {
                // This TV takes no key up; the press itself already went through.
            }
        }
    }

    /**
     * [code] held down, like a hold on the real remote (Home opens the quick menu, OK on an app shows its options). With ADB this is one command; on a
     * Fire TV it is key down, a pause, then key up. A TV that doesn't take key down/up gets an ordinary press instead.
     */
    fun longPress(code: Int) {
        val link = fire
        val adb = connection
        when {
            link != null -> {
                val down = FireTvCommands.longPress(code, down = true)
                val up = FireTvCommands.longPress(code, down = false)
                if (down == null || up == null) {
                    key(code)
                    return
                }
                enqueue {
                    try {
                        link.send(down)
                    } catch (e: FireTvHttpException) {
                        sendPress(link, code) // the best this TV allows
                        return@enqueue
                    }
                    Thread.sleep(LONG_PRESS_MS) // the sender runs on its own IO thread; later buttons wait this one second
                    try {
                        link.send(up)
                    } catch (e: FireTvHttpException) {
                        // Only the release failed; the key is let go by the TV's own timeout.
                    }
                }
            }
            adb != null -> enqueue {
                val answer = shellGuarded(adb, TvCommands.longPress(code))
                // Older Fire OS doesn't know the "hold" option and prints its usage instead: press the key once then.
                if (answer.exitCode != 0 || answer.allOutput.contains("Usage", ignoreCase = true)) {
                    shellGuarded(adb, TvCommands.key(code))
                }
            }
        }
    }

    /**
     * A direction pressed and kept down, on a Fire TV: sends "key down" now and "key up" from [holdEnd], and the TV repeats
     * the move by itself. Returns false when that isn't possible (not a Fire TV, not a direction, or this TV didn't take
     * it), and the caller then repeats single presses instead.
     */
    fun holdStart(code: Int): Boolean {
        val link = fire ?: return false
        if (!holdWorks) return false
        val down = FireTvCommands.hold(code, down = true) ?: return false
        val id = holdIds.incrementAndGet()
        holding[code] = id
        enqueue {
            try {
                link.send(down)
            } catch (e: FireTvHttpException) {
                // The TV answered but doesn't take held keys: remember it, and move once the ordinary way.
                // (A TV that doesn't answer at all is a lost connection and is handled as one.)
                holdWorks = false
                holding.remove(code, id)
                sendPress(link, code)
            }
        }
        val mine = generation
        scope.launch {
            delay(HOLD_LIMIT_MS)
            // Only this very hold: a newer press of the same direction has its own timer.
            if (mine == generation && holding[code] == id) holdEnd(code)
        }
        return true
    }

    /** The held direction was let go: tells the TV to stop. Does nothing if [code] isn't the one held. */
    fun holdEnd(code: Int) {
        if (holding.remove(code) == null) return
        val link = fire ?: return
        val up = FireTvCommands.hold(code, down = false) ?: return
        enqueue {
            try {
                link.send(up)
            } catch (e: FireTvHttpException) {
                // The TV answered with an error to the stop only; nothing more to do.
            }
        }
    }

    /** The text box on the phone changed from [old] to [new]: do the same edit on the TV, as it is typed. */
    fun typed(old: String, new: String) {
        val link = fire
        val adb = connection
        when {
            // The Fire TV takes the whole text, so what is sent is always the full box (and a backspace is just a shorter text).
            link != null -> enqueue { link.send(FireTvCommands.text(new)) }
            adb != null -> TvCommands.edit(old, new).forEach { command -> enqueue { shellGuarded(adb, command) } }
        }
    }

    /** Opens [url] in the TV's browser (ADB only). */
    fun openUrl(url: String) {
        val adb = connection ?: return
        enqueue { shellGuarded(adb, TvCommands.openUrl(url)) }
    }

    /** Fire TV: opens the TV's browser; the address then has to be typed with the keyboard. */
    fun openBrowser() {
        val link = fire ?: return
        enqueue { link.send(FireTvCommands.openBrowser()) }
    }

    /** Looks for other devices on this phone's network that answer like a TV remote service (usually only the TV). */
    fun scan(context: Context) {
        if (_scanning.value) return
        val own = NetworkUtils.resolveLocalIpv4(context.applicationContext)?.hostAddress ?: run {
            _state.value = State.Failed("Connect the phone to Wi-Fi first.")
            return
        }
        _scanning.value = true
        _found.value = emptyList()
        scope.launch {
            try {
                val prefix = own.substringBeforeLast('.')
                val gate = Semaphore(64)
                val hits = (1..254).map { n ->
                    async {
                        val host = "$prefix.$n"
                        if (host != own) {
                            gate.withPermit {
                                if (reachable(host, FireTvCommands.WAKE_PORT, 500) || reachable(host, TvCommands.DEFAULT_PORT, 500)) host else null
                            }
                        } else null
                    }
                }.awaitAll().filterNotNull()
                _found.value = hits
            } finally {
                _scanning.value = false
            }
        }
    }

    private fun reachable(host: String, port: Int, timeoutMs: Int = 2_000): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) } }.isSuccess

    /** This phone's identity for ADB: made once, then reused so the TV's "Always allow" keeps working. */
    private fun keyPair(context: Context): AdbKeyPair {
        val privateKey = File(context.filesDir, "adb_key")
        val publicKey = File(context.filesDir, "adb_key.pub")
        if (privateKey.exists() && publicKey.exists()) {
            runCatching { AdbKeyPair.read(privateKey, publicKey) }.getOrNull()?.let { return it }
        }
        // Missing or unreadable (half-written, damaged): make a new one. The TV will ask to Allow again.
        AdbKeyPair.generate(privateKey, publicKey)
        return AdbKeyPair.read(privateKey, publicKey)
    }
}
