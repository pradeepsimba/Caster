package mano.algo.caster.tv

import android.content.Context
import android.os.Build
import dadb.AdbAuthException
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Uses the phone as a remote for a Fire TV (or any Android TV). The TV has to have ADB debugging turned on; the phone
 * then sends it button presses, text and "open this page" over the Wi-Fi, the same way a computer would.
 *
 * The first time, the TV shows an "Allow this device?" prompt. The phone proves itself with its own key (made on first
 * use, kept in the app's private storage and left out of backups), so after "Always allow" it connects without asking.
 */
object TvRemote {
    sealed interface State {
        data object Idle : State

        /** Includes waiting for the owner to tap Allow on the TV. */
        data class Connecting(val host: String) : State
        data class Connected(val host: String) : State
        data class Failed(val message: String) : State
    }

    /** The ADB library needs Android 8 or newer (it uses file and Base64 classes added in that version). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private const val SCAN_PORT = TvCommands.DEFAULT_PORT
    private const val PREFS = "caster_tv"
    private const val KEY_HOST = "host"
    private const val APPROVAL_WAIT_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _savedHost = MutableStateFlow("")

    /** The TV address used last, so it doesn't have to be typed again. */
    val savedHost: StateFlow<String> = _savedHost.asStateFlow()

    private val _found = MutableStateFlow<List<String>>(emptyList())

    /** Addresses on this network that answered on the ADB port, after a search. */
    val found: StateFlow<List<String>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // Read from the button handlers (main thread) and written from the connect and sender jobs.
    @Volatile private var connection: Dadb? = null
    @Volatile private var opening: Dadb? = null
    private var connectJob: Job? = null
    private var sender: Job? = null

    // Commands go through one queue, in order, and none is dropped (every typed letter matters). A held button is the
    // exception: it skips a press while the TV is still working through earlier ones, so it can't build up a lag.
    private val commands = Channel<String>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)

    fun init(context: Context) {
        if (_savedHost.value.isEmpty()) {
            _savedHost.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, "").orEmpty()
        }
    }

    fun connect(context: Context, rawHost: String) {
        val target = TvCommands.parseTarget(rawHost)
        val host = rawHost.trim()
        if (!supported) {
            _state.value = State.Failed("The TV remote needs Android 8 or newer.")
            return
        }
        if (target == null) {
            _state.value = State.Failed("Enter the TV's address on your Wi-Fi, like 192.168.0.50.")
            return
        }
        val app = context.applicationContext
        disconnect()
        _state.value = State.Connecting(host)
        _savedHost.value = host
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HOST, host).apply()

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
                if (!isActive) {
                    // Cancelled while the answer was arriving: don't come back to life after the owner pressed Cancel.
                    connection = null
                    opened.close()
                    return@launch
                }
                _state.value = State.Connected(host)
                startSender(opened)
            } catch (e: Throwable) {
                runCatching { adb?.close() }
                if (opening === adb) opening = null
                // After Cancel or a newer Connect this job is stale: stay quiet, the new state is already shown.
                if (!isActive) return@launch
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

    fun disconnect() {
        connectJob?.cancel()
        sender?.cancel()
        runCatching { opening?.close() }
        opening = null
        runCatching { connection?.close() }
        connection = null
        while (commands.tryReceive().isSuccess) { /* drop anything still queued for the old connection */ }
        pending.set(0)
        _state.value = State.Idle
    }

    private fun startSender(adb: Dadb) {
        sender?.cancel()
        sender = scope.launch {
            for (command in commands) {
                try {
                    adb.shell(command)
                    pending.decrementAndGet()
                } catch (e: Throwable) {
                    // The TV went to sleep, left the network, or turned ADB off. (If the owner pressed Disconnect, this job
                    // was cancelled first and the screen already says so.)
                    runCatching { adb.close() }
                    if (isActive && connection === adb) {
                        connection = null
                        _state.value = State.Failed("Lost the connection to the TV. Connect again.")
                    }
                    break
                }
            }
        }
    }

    private fun enqueue(command: String) {
        pending.incrementAndGet()
        commands.trySend(command)
    }

    /** One press of [code]. With [repeating] (a held button) a press is skipped while the TV is still catching up. */
    fun key(code: Int, repeating: Boolean = false) {
        if (connection == null || (repeating && pending.get() >= 3)) return
        enqueue(TvCommands.key(code))
    }

    /** The text box on the phone changed from [old] to [new]: do the same edit on the TV, as it is typed. */
    fun typed(old: String, new: String) {
        if (connection == null) return
        TvCommands.edit(old, new).forEach(::enqueue)
    }

    /** Opens [url] in the TV's browser, so Caster's page can be reached without typing an address on the TV. */
    fun openUrl(url: String) {
        if (connection != null) enqueue(TvCommands.openUrl(url))
    }

    /** Looks for other devices on this phone's network that have ADB switched on (usually only the TV). */
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
                        if (host != own) gate.withPermit { if (reachable(host, SCAN_PORT, 500)) host else null } else null
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
