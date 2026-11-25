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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mano.algo.caster.server.NetworkUtils
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uses the phone as a remote for a Fire TV, an Android TV / Google TV, a Samsung TV or an LG TV.
 *
 * Fire TV: like the official remote app. The TV shows a 4-digit code, you type it once, and the phone remembers it.
 * Nothing has to be switched on in the TV's settings.
 *
 * Android TV / Google TV: like the Google TV app. The TV shows a 6-character code, you type it once, and the phone
 * remembers it. Nothing has to be switched on in the TV's settings either.
 *
 * Samsung (2016 and newer) and LG (webOS): the TV asks on its screen to allow this phone, once; the phone then keeps
 * the key the TV hands out.
 *
 * ADB (advanced, any Android TV): needs ADB debugging turned on in the TV's developer options; the TV asks once to
 * "Allow" this phone, which proves itself with its own key (made on first use, private to the app, left out of backups).
 */
/** The Android TV hung up right after connecting: it doesn't accept this phone (any more). */
private class AndroidTvDroppedUsException(cause: Throwable) : IOException("The TV hung up: ${cause.message ?: cause.javaClass.simpleName}", cause)

object TvRemote {
    /** How the phone talks to the TV. */
    enum class Link { FireTv, Adb, AndroidTv, Samsung, Lg }

    sealed interface State {
        data object Idle : State

        /** Includes waiting for the owner to tap Allow on the TV (ADB, or [needsAllow] on a Samsung or LG TV). */
        data class Connecting(val host: String, val viaAdb: Boolean = false, val needsAllow: Boolean = false) : State

        /** The TV is showing a code (4 digits; 6 letters and digits on an [androidTv]); the owner types it on the phone. [error] is set after a wrong code. */
        data class NeedsPin(val host: String, val error: String? = null, val androidTv: Boolean = false) : State
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

    /** The remote itself (Fire TV, Android TV, Samsung, LG) runs on every Android version the app does. */
    val supported: Boolean get() = true

    /** The ADB library needs Android 8 or newer (it uses file and Base64 classes added in that version). */
    val adbSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private const val PREFS = "caster_tv"
    private const val KEY_HOST = "host"
    private const val KEY_TOKEN_PREFIX = "fire_token_"
    private const val KEY_VOLUME_PREFIX = "volume_on_"
    private const val KEY_ATV_PREFIX = "atv_tv_" // the fingerprint of an Android TV this phone is paired with
    private const val KEY_SAMSUNG_TOKEN = "samsung_token_"
    private const val KEY_SAMSUNG_PIN = "samsung_pin_" // the fingerprint of a Samsung TV's certificate
    private const val KEY_LG_KEY = "lg_key_"
    private const val KEY_LG_PIN = "lg_pin_"
    private const val KEY_KIND_PREFIX = "kind_" // which kind of TV answered at an address last time
    private const val KEY_RECENT = "recent_tvs" // the addresses used before, the newest first
    private const val KEY_MAC_PREFIX = "mac_" // the MAC address of a TV, for switching it on
    private const val RECENT_MAX = 6

    /** A held direction is released by itself after this long, so a lost "key up" can't leave the TV scrolling for ever. */
    private const val HOLD_LIMIT_MS = 8_000L

    /** How long a long press is kept down on a Fire TV (Android counts 0.5 s as a long press). */
    private const val LONG_PRESS_MS = 900L
    private const val APPROVAL_WAIT_MS = 60_000L

    /** How long to ask an address whether it is an Android TV (its remote port answers), and how long it may take to get ready. */
    private const val ATV_PROBE_MS = 1_500
    private const val ATV_READY_MS = 8_000L
    private const val ALLOW_HINT_MS = 2_500L
    private const val VOICE_BEGIN_MS = 3_000L
    private const val HINT_CHECKS = 20

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

    /** A TV that was connected to before: its address as it was typed, the kind of TV, and its MAC address when known. */
    data class SavedTv(val address: String, val kind: String?, val mac: String?)

    private val _saved = MutableStateFlow<List<SavedTv>>(emptyList())

    /** The TVs used before, the newest first, so one tap connects again. */
    val saved: StateFlow<List<SavedTv>> = _saved.asStateFlow()

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
    @Volatile private var atv: AndroidTvLink? = null
    @Volatile private var atvOpening: AndroidTvLink? = null
    @Volatile private var pairingAtv: AndroidTvPairing? = null
    @Volatile private var socketRemote: TvSocketRemote? = null // a Samsung or LG TV
    @Volatile private var openingSocket: Closeable? = null // the connection being made to one, so Cancel can close it

    /**
     * The directions currently held down on a Fire TV (key code to the number of that hold; two fingers can hold two
     * directions), and whether this TV has shown it understands held keys.
     */
    private val holding = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val holdIds = AtomicInteger(0)
    @Volatile private var holdWorks = true

    // Voice on an Android TV: held while the owner holds the microphone button.
    @Volatile private var voiceHeld = false
    @Volatile private var voiceEnding = false // the last session is being closed: a new press starts a new one
    @Volatile private var voiceSerial = 0
    private var voiceJob: Job? = null
    private val _voiceError = MutableStateFlow<String?>(null)

    /** Why the last voice try didn't work, for the screen; null when it did (or none was tried). */
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()
    private var volumeJob: Job? = null

    /** Bumped by every disconnect, so a job that was running for an older connection can tell and stay quiet. */
    @Volatile private var generation = 0
    private var connectJob: Job? = null
    private var sender: Job? = null

    // Commands go through one queue, in order, and none is dropped (every typed letter matters). A held button is the
    // exception: it skips a press while the TV is still working through earlier ones, so it can't build up a lag.
    // Every connection has its own queue: a sender still busy with the old one can't pick up the new one's buttons.
    @Volatile private var commands = Channel<() -> Unit>(Channel.UNLIMITED)
    private val transition = Any() // taken while a connection is made or ended, so the two can't interleave
    private val pending = AtomicInteger(0)

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (_savedHost.value.isEmpty()) {
            _savedHost.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, "").orEmpty()
        }
        if (_saved.value.isEmpty()) refreshSaved(context)
    }

    private fun recentAddresses(p: android.content.SharedPreferences): List<String> =
        p.getString(KEY_RECENT, "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun kindLabel(name: String?) = when (name) {
        Kind.Fire.name -> "Fire TV"
        Kind.AndroidTv.name -> "Android TV"
        Kind.Samsung.name -> "Samsung"
        Kind.Lg.name -> "LG"
        else -> null
    }

    private fun refreshSaved(context: Context) {
        val p = prefs(context)
        _saved.value = recentAddresses(p).map { address ->
            val host = TvCommands.parseTarget(address)?.host ?: address
            SavedTv(address, kindLabel(p.getString(KEY_KIND_PREFIX + host, null)), p.getString(KEY_MAC_PREFIX + host, null))
        }
    }

    /** Puts [address] first in the list of TVs used before. */
    private fun remember(context: Context, address: String) {
        val p = prefs(context)
        val updated = (listOf(address) + recentAddresses(p).filter { it != address }).take(RECENT_MAX)
        p.edit().putString(KEY_RECENT, updated.joinToString("\n")).apply()
        refreshSaved(context)
    }

    /** Forgets a TV from the list (and what is remembered about it). */
    fun forget(context: Context, address: String) {
        val p = prefs(context)
        val host = TvCommands.parseTarget(address)?.host
        val edit = p.edit().putString(KEY_RECENT, recentAddresses(p).filter { it != address }.joinToString("\n"))
        if (host != null && recentAddresses(p).none { it != address && TvCommands.parseTarget(it)?.host == host }) {
            edit.remove(KEY_MAC_PREFIX + host)
        }
        edit.apply()
        refreshSaved(context)
    }

    /**
     * Switches a TV on from standby with a Wake-on-LAN packet. [mac] is typed once and remembered; without it the one remembered
     * (or, on older Android, read from the phone's neighbour table) is used. False when no MAC address is known yet.
     */
    fun wake(context: Context, address: String, mac: String? = null): Boolean {
        val app = context.applicationContext
        val host = TvCommands.parseTarget(address)?.host ?: return false
        val p = prefs(app)
        if (mac != null && WakeOnLan.normalize(mac) == null) return false
        val known = mac?.let(WakeOnLan::normalize) ?: p.getString(KEY_MAC_PREFIX + host, null) ?: WakeOnLan.fromNeighbours(host) ?: return false
        p.edit().putString(KEY_MAC_PREFIX + host, known).apply()
        refreshSaved(app)
        LanNetwork.refresh(app)
        scope.launch { runCatching { WakeOnLan.send(known, host) } }
        return true
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Common start of both connect methods; returns the parsed address, or null after showing why it can't go on. */
    private fun begin(context: Context, rawHost: String): TvCommands.Target? {
        appContext = context.applicationContext
        LanNetwork.refresh(context)
        val target = TvCommands.parseTarget(rawHost)
        if (target == null) {
            _state.value = State.Failed("Enter the TV's address on your Wi-Fi, like 192.168.0.50.")
            return null
        }
        disconnect()
        _savedHost.value = rawHost.trim()
        prefs(context).edit().putString(KEY_HOST, rawHost.trim()).apply()
        remember(context, rawHost.trim())
        // The TV has just been talked to, so the phone may know its MAC address by now (for switching it on later).
        val app = context.applicationContext
        scope.launch {
            delay(4_000)
            val p = prefs(app)
            if (p.getString(KEY_MAC_PREFIX + target.host, null) == null) {
                WakeOnLan.fromNeighbours(target.host)?.let { p.edit().putString(KEY_MAC_PREFIX + target.host, it).apply() }
            }
            refreshSaved(app)
        }
        return target
    }

    // --- The Connect button: Android TV or Fire TV --------------------------------------------------------------

    private enum class Kind { Fire, AndroidTv, Samsung, Lg }

    /** Remembers which kind of TV is at [host], for the next time (a TV in standby doesn't answer on its remote port). */
    private fun rememberKind(host: String, kind: Kind) {
        appContext?.let { prefs(it).edit().putString(KEY_KIND_PREFIX + host, kind.name).apply(); refreshSaved(it) }
    }

    /** Which kind of TV is at [host]: each kind has its own remote port that answers; a Fire TV is what is left. */
    private suspend fun detect(host: String): Kind = coroutineScope {
        val androidTv = async { reachable(host, AndroidTvCommands.PAIR_PORT, ATV_PROBE_MS) }
        val samsung = async { reachable(host, SamsungTvCommands.SECURE_PORT, ATV_PROBE_MS) || reachable(host, SamsungTvCommands.PLAIN_PORT, ATV_PROBE_MS) }
        val lg = async { reachable(host, LgTvCommands.SECURE_PORT, ATV_PROBE_MS) || reachable(host, LgTvCommands.PLAIN_PORT, ATV_PROBE_MS) }
        when {
            androidTv.await() -> Kind.AndroidTv
            samsung.await() -> Kind.Samsung
            lg.await() -> Kind.Lg
            else -> Kind.Fire
        }
    }

    /**
     * Connects to the TV at [rawHost], whatever kind it is: an Android TV / Google TV, a Samsung or an LG TV answer on their
     * own remote ports; anything else is tried as a Fire TV (and a Fire TV that was paired before goes straight there).
     */
    fun connect(context: Context, rawHost: String) {
        val target = begin(context, rawHost) ?: return
        val host = target.host
        val app = context.applicationContext
        val store = prefs(app)
        _state.value = State.Connecting(host)
        val mine = generation

        connectJob = scope.launch {
            try {
                // (A TV that was replaced by another kind at the same address doesn't answer like a Fire TV.)
                val pairedFire = store.getString(KEY_TOKEN_PREFIX + host, null) != null &&
                    (reachable(host, FireTvCommands.PORT, ATV_PROBE_MS) || looksLikeFireTv(host))
                val before = store.getString(KEY_KIND_PREFIX + host, null)?.let { runCatching { Kind.valueOf(it) }.getOrNull() }
                // Nothing answering usually means a TV in standby: it is then treated as the kind it was last time.
                val detected = if (pairedFire) Kind.Fire else detect(host)
                val guessed = detected == Kind.Fire && before != null && before != Kind.Fire
                val kind = if (guessed) before!! else detected
                when (kind) {
                    Kind.AndroidTv -> androidTvFlow(app, host, mine)
                    Kind.Samsung -> samsungFlow(app, host, mine)
                    Kind.Lg -> lgFlow(app, host, mine)
                    Kind.Fire -> fireFlow(store, host, mine)
                }
                // A guess that didn't work (the TV is off, or it is another TV now): forget it, so the next try looks at the TV again.
                if (guessed && mine == generation && _state.value is State.Failed) store.edit().remove(KEY_KIND_PREFIX + host).apply()
            } catch (e: Throwable) {
                if (isActive && mine == generation) _state.value = State.Failed("Couldn't connect to the TV: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // --- Fire TV: pairing code ---------------------------------------------------------------------------------

    private suspend fun fireFlow(store: android.content.SharedPreferences, host: String, mine: Int) {
        val link = FireTvLink(host, store.getString(KEY_TOKEN_PREFIX + host, null))
        link.wake() // the TV's remote service may be asleep until it is poked
        delay(WAKE_SETTLE_MS)
        if (link.token != null) {
            // Paired before: the first button press will tell if the TV has since forgotten this phone.
            if (currentCoroutineContext().isActive) {
                ifCurrent(mine) {
                    fire = link
                    rememberKind(host, Kind.Fire)
                    _state.value = State.Connected(host, Link.FireTv)
                    startSender(mine)
                    autoEnableVolume(host)
                }
            }
        } else {
            askForPin(link, mine)
        }
    }

    /** Asks the TV to show its code. The service can take a moment to start after waking, so it tries a few times. */
    private suspend fun askForPin(link: FireTvLink, mine: Int) {
        repeat(PIN_TRIES) {
            try {
                link.send(FireTvCommands.requestPin(DEVICE_NAME))
                ifCurrent(mine) {
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
                "The TV didn't answer on ${link.host}. Check that it's on (fully, not in standby), on the same Wi-Fi as this phone, " +
                    "and not using a VPN. On an LG TV, turn on \"LG Connect Apps\" (Settings > Network); on a Samsung TV, allow " +
                    "new devices (Settings > General > External Device Manager). If it still fails, use \"Connect with ADB\" below.",
            )
        }
    }

    /** The owner typed the code the TV shows. */
    fun submitPin(context: Context, pin: String) {
        if (_state.value !is State.NeedsPin) return // a second tap (or Done) while the first code is being checked
        pairingAtv?.let { session ->
            submitAndroidTvCode(context.applicationContext, session, pin)
            return
        }
        val link = pairing ?: return
        val host = link.host
        if (!FireTvCommands.isPin(pin.trim())) {
            _state.value = State.NeedsPin(host, "The code is 4 digits.")
            return
        }
        val store = prefs(context)
        val mine = generation
        _state.value = State.Connecting(host)
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
                ifCurrent(mine) {
                    pairing = null
                    fire = link
                    rememberKind(host, Kind.Fire)
                    _state.value = State.Connected(host, Link.FireTv)
                    startSender(mine)
                    autoEnableVolume(host)
                }
            } catch (e: Throwable) {
                if (isActive && mine == generation) {
                    _state.value = State.NeedsPin(host, "That code didn't work. Check the TV and try again.")
                }
            }
        }
    }

    // --- Android TV / Google TV: pairing code ------------------------------------------------------------------

    private suspend fun androidTvFlow(app: Context, host: String, mine: Int) {
        val identity = AndroidTvIdentity.loadOrCreate(app.filesDir)
        val store = prefs(app)
        val pinned = store.getString(KEY_ATV_PREFIX + host, null)
        if (pinned != null) {
            try {
                openAndroidTv(host, identity, pinned, mine)
                return
            } catch (e: IOException) {
                if (mine != generation) return
                // A TV that is only slow or away isn't forgotten; one that refuses this phone's certificate (it was reset,
                // or this phone was removed from it) hangs up on it: pair again, which asks for a new code.
                if (e !is SSLException && e !is EOFException && e !is AndroidTvDroppedUsException) {
                    _state.value = State.Failed("Couldn't connect to the TV: ${e.message ?: e.javaClass.simpleName}")
                    return
                }
                // (The saved pairing stays until a new one replaces it: a hiccup mustn't cost a good pairing.)
            }
        }
        startAndroidTvPairing(host, identity, mine, null)
    }

    /** Asks the TV to show its code, then waits for the owner to type it. */
    private fun startAndroidTvPairing(host: String, identity: AndroidTvIdentity, mine: Int, note: String?) {
        val session = try {
            AndroidTvPairing.begin(host, identity, DEVICE_NAME)
        } catch (e: IOException) {
            if (mine == generation) {
                _state.value = State.Failed("The TV didn't start pairing: ${e.message ?: e.javaClass.simpleName}. Turn the TV on and try again.")
            }
            return
        }
        val current = ifCurrent(mine) {
            pairingAtv = session
            _state.value = State.NeedsPin(host, note, androidTv = true)
        }
        if (!current) session.close()
    }

    /** The owner typed the 6-character code the Android TV shows. */
    private fun submitAndroidTvCode(app: Context, session: AndroidTvPairing, pin: String) {
        val host = session.host
        val code = pin.trim()
        if (!AndroidTvCommands.isCode(code)) {
            _state.value = State.NeedsPin(host, "The code has 6 characters: numbers and the letters A to F.", androidTv = true)
            return
        }
        val mine = generation
        _state.value = State.Connecting(host) // the Pair button is gone, so a second tap can't send the code twice
        connectJob = scope.launch {
            try {
                submitAndroidTvCodeNow(app, session, host, code, mine)
            } catch (e: Throwable) {
                if (isActive && mine == generation) _state.value = State.Failed("Couldn't pair with the TV: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun submitAndroidTvCodeNow(app: Context, session: AndroidTvPairing, host: String, code: String, mine: Int) {
        try {
            session.finish(code)
        } catch (e: AndroidTvBadCodeException) {
            if (mine == generation) {
                _state.value = State.NeedsPin(host, "That code isn't right. Check the TV and try again.", androidTv = true)
            }
            return
        } catch (e: IOException) {
            // The TV ended this pairing (wrong code, or it waited too long). It shows a new code after a fresh start.
            session.close()
            if (mine == generation) {
                pairingAtv = null
                startAndroidTvPairing(host, session.identity, mine, "That code didn't work. The TV shows a new code: type it here.")
            }
            return
        }
        if (!currentCoroutineContext().isActive || mine != generation) return
        val pinned = session.serverFingerprint
        prefs(app).edit().putString(KEY_ATV_PREFIX + host, pinned).apply()
        pairingAtv = null
        // The TV can take a moment before its remote service accepts the newly paired phone.
        var last: IOException? = null
        repeat(3) {
            try {
                openAndroidTv(host, session.identity, pinned, mine)
                return
            } catch (e: IOException) {
                last = e
                if (!currentCoroutineContext().isActive || mine != generation) return
                delay(1_000)
            }
        }
        _state.value = State.Failed("Paired, but couldn't connect: ${last?.message ?: "the TV didn't answer"}. Tap Connect again.")
    }

    /** Opens the remote connection to a paired Android TV and waits until the TV is ready for keys. Throws if it can't. */
    private suspend fun openAndroidTv(host: String, identity: AndroidTvIdentity, pinned: String, mine: Int) {
        val link = AndroidTvLink.open(host, identity, pinned, { error -> linkClosed(mine, error) })
        var done = false
        try {
            if (mine != generation) throw IOException("Cancelled") // another Connect (or Cancel) came while this one was opening
            atvOpening = link // so Cancel can close it and end the wait
            val ready = link.awaitReady(ATV_READY_MS)
            if (mine == generation) atvOpening = null
            if (!ready) {
                val why = link.failure
                // Hung up on right after connecting (not just silent): the TV doesn't accept this phone.
                if (why != null && why !is java.net.SocketTimeoutException) throw AndroidTvDroppedUsException(why)
                throw IOException("The TV didn't get ready")
            }
            if (!currentCoroutineContext().isActive) return
            val current = ifCurrent(mine) {
                atv = link
                rememberKind(host, Kind.AndroidTv)
                _state.value = State.Connected(host, Link.AndroidTv)
                startSender(mine)
            }
            if (!current) return
            done = true
            link.failure?.let { linkClosed(mine, it) } // it may have dropped just before it became the current one
        } finally {
            if (!done) link.close()
        }
    }

    /** The reader thread saw the connection end (the TV went off, left the network, or forgot this phone). */
    private fun linkClosed(mine: Int, error: Throwable) {
        lost(mine, error)
    }

    // --- Samsung and LG: the TV asks on its screen to allow this phone ---------------------------------------

    /** Keeps hold of a connection being made so Cancel can close it; one made after Cancel is closed at once. */
    private fun track(mine: Int): (Closeable) -> Unit = { socket -> if (mine != generation) socket.close() else openingSocket = socket }

    private suspend fun becomeConnected(link: TvSocketRemote, host: String, kind: Link, mine: Int) {
        val current = currentCoroutineContext().isActive && ifCurrent(mine) {
            openingSocket = null
            socketRemote = link
            rememberKind(host, if (kind == Link.Samsung) Kind.Samsung else Kind.Lg)
            _state.value = State.Connected(host, kind)
            startSender(mine)
        }
        if (!current) link.close() else link.failure?.let { linkClosed(mine, it) }
    }

    private fun failedToConnect(e: IOException, hint: String = ""): State.Failed =
        State.Failed(
            (if (e is OwnerWaitTimeout) {
                "The TV didn't answer in time. If it asked to allow this phone, choose Allow and tap Connect again."
            } else if (e is java.net.SocketTimeoutException) {
                "The TV didn't answer. Check that it is on, and on the same Wi-Fi as this phone."
            } else {
                "Couldn't connect to the TV: ${e.message ?: e.javaClass.simpleName}"
            }) + hint,
        )

    /** One line in the activity record (Settings > Diagnostics shows it), so a TV that won't connect can be traced. */
    private fun note(text: String) {
        appContext?.let { mano.algo.caster.CrashLog.note(it, text) }
    }

    /**
     * A TV that already knows this phone connects at once. One that takes longer is most likely asking its owner (it forgot
     * the phone), so the screen then says what to do on the TV.
     */
    private fun hintAllowSoon(host: String, mine: Int): Job = scope.launch {
        delay(ALLOW_HINT_MS)
        // Only a TV that has taken the connection can be asking its owner; one that is off or away hasn't. (The connection
        // may only be taken after the first port timed out, so it is looked at again for a while.)
        repeat(HINT_CHECKS) {
            if (mine != generation || _state.value != State.Connecting(host)) return@launch
            val socket = openingSocket as? java.net.Socket
            if (socket != null && socket.isConnected && !socket.isClosed) {
                _state.compareAndSet(State.Connecting(host), State.Connecting(host, needsAllow = true)) // only if still just connecting
                return@launch
            }
            delay(500)
        }
    }

    private suspend fun samsungFlow(app: Context, host: String, mine: Int) {
        val store = prefs(app)
        for (attempt in 0..1) {
            val token = store.getString(KEY_SAMSUNG_TOKEN + host, null)
            val pinned = store.getString(KEY_SAMSUNG_PIN + host, null)
            if (token == null) _state.value = State.Connecting(host, needsAllow = true)
            val hint = if (token != null) hintAllowSoon(host, mine) else null
            try {
                val opened = SamsungTvLink.open(host, DEVICE_NAME, token, pinned, SamsungTvLink.ENDPOINTS, track(mine)) { e -> linkClosed(mine, e) }
                store.edit().apply {
                    opened.token?.let { putString(KEY_SAMSUNG_TOKEN + host, it) }
                    opened.fingerprint?.let { putString(KEY_SAMSUNG_PIN + host, it) }
                }.apply()
                becomeConnected(opened.link, host, Link.Samsung, mine)
                return
            } catch (e: TvRefusedException) {
                if (mine != generation) return
                openingSocket = null
                if (token != null && attempt == 0) { // the TV no longer knows this token: ask again
                    store.edit().remove(KEY_SAMSUNG_TOKEN + host).apply()
                    continue
                }
                _state.value = State.Failed("The TV didn't allow this phone. When it asks, choose Allow, then tap Connect again.")
                return
            } catch (e: IOException) {
                if (mine != generation) return
                openingSocket = null
                if (isPinMismatch(e) && attempt == 0) { // a different TV than the one paired (or it was reset): start over
                    store.edit().remove(KEY_SAMSUNG_TOKEN + host).remove(KEY_SAMSUNG_PIN + host).apply()
                    continue
                }
                _state.value = failedToConnect(e)
                return
            } finally {
                hint?.cancel()
            }
        }
    }

    private suspend fun lgFlow(app: Context, host: String, mine: Int) {
        val store = prefs(app)
        for (attempt in 0..1) {
            val key = store.getString(KEY_LG_KEY + host, null)
            val pinned = store.getString(KEY_LG_PIN + host, null)
            if (key == null) _state.value = State.Connecting(host, needsAllow = true)
            val hint = if (key != null) hintAllowSoon(host, mine) else null
            try {
                note("LG: connecting to the TV")
                val opened = LgTvLink.open(host, key, pinned, LgTvLink.ENDPOINTS, track(mine)) { e -> linkClosed(mine, e) }
                note("LG: connected and registered")
                store.edit().apply {
                    putString(KEY_LG_KEY + host, opened.clientKey)
                    opened.fingerprint?.let { putString(KEY_LG_PIN + host, it) }
                }.apply()
                becomeConnected(opened.link, host, Link.Lg, mine)
                return
            } catch (e: TvRefusedException) {
                if (mine != generation) return
                openingSocket = null
                if (key != null && attempt == 0) { // the TV no longer knows this key: ask again
                    store.edit().remove(KEY_LG_KEY + host).apply()
                    continue
                }
                note("LG: the TV did not accept this phone (${e.message ?: ""})")
                _state.value = State.Failed("The TV didn't accept this phone. When it asks, choose Yes or Allow, then tap Connect again.")
                return
            } catch (e: IOException) {
                if (mine != generation) return
                openingSocket = null
                if (isPinMismatch(e) && attempt == 0) {
                    store.edit().remove(KEY_LG_KEY + host).remove(KEY_LG_PIN + host).apply()
                    continue
                }
                note("LG: failed - ${e.message ?: e.javaClass.simpleName}")
                _state.value = failedToConnect(e, " On an LG TV, make sure \"LG Connect Apps\" is on (Settings > Network).")
                return
            } finally {
                hint?.cancel()
            }
        }
    }

    // --- ADB -------------------------------------------------------------------------------------------------

    fun connectAdb(context: Context, rawHost: String) {
        if (!adbSupported) {
            _state.value = State.Failed("ADB needs Android 8 or newer. The normal Connect works on this phone.")
            return
        }
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
                    // The library connects lazily and can't be interrupted while it waits for Allow, so the screen gives up
                    // here; if the TV is allowed after all, the blocked call finds this out and is ignored.
                    ifCurrent(mine) {
                        if (_state.value is State.Connecting) {
                            _state.value = State.Failed("The TV didn't answer the \"Allow\" prompt in time. Try again and choose Allow on the TV.")
                        }
                    }
                }
                opened.shell("echo caster")
                patience.cancel()
                val current = isActive && !gaveUp.get() && ifCurrent(mine) {
                    opening = null
                    connection = opened
                    _state.value = State.Connected(host, Link.Adb)
                    startSender(mine)
                }
                if (!current) {
                    // Cancelled while the answer was arriving: don't come back to life after the owner pressed Cancel.
                    opened.close()
                    return@launch
                }
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
        synchronized(transition) { disconnectLocked() }
    }

    private fun disconnectLocked() {
        generation++
        voiceHeld = false
        voiceJob?.cancel()
        voiceJob = null
        _voiceError.value = null
        holdWorks = true
        connectJob?.cancel()
        sender?.cancel()
        runCatching { opening?.close() }
        opening = null
        runCatching { connection?.close() }
        connection = null
        fire = null
        pairing = null
        runCatching { pairingAtv?.close() }
        pairingAtv = null
        runCatching { atvOpening?.close() }
        atvOpening = null
        runCatching { atv?.close() }
        atv = null
        runCatching { openingSocket?.close() }
        openingSocket = null
        runCatching { socketRemote?.close() }
        socketRemote = null
        holding.clear()
        closeVolume()
        newQueue()
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
        if (!adbSupported) {
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
                    ifCurrent(mine) { // see connectAdb: the wait can't be interrupted, so the screen gives up here
                        if (_volume.value == VolumeState.Waiting) {
                            _volume.value = VolumeState.Failed("The TV didn't answer the \"Allow\" prompt in time. Try again and choose Allow on the TV.")
                        }
                    }
                }
                opened.shell("echo caster")
                patience.cancel()
                if (!isActive || mine != generation || gaveUp.get()) {
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
        pending.set(0)
        val queue = commands
        sender = scope.launch {
            for (task in queue) {
                try {
                    task()
                    if (mine == generation) donePending() // a finished task of an old connection isn't counted
                } catch (e: FireTvHttpException) {
                    // The Fire TV answered, just not with a yes (typing with no text box, play with nothing to play): it is still there.
                    if (mine == generation) donePending()
                } catch (e: Throwable) {
                    // The TV went to sleep, left the network, turned ADB off or forgot this phone. (If the owner pressed
                    // Disconnect, this job was cancelled first and the screen already says so.)
                    if (isActive) lost(mine, e)
                    break
                }
            }
        }
    }

    /** The connection ended by itself. Does nothing if it isn't the current one any more, or was already reported. */
    private fun lost(mine: Int, error: Throwable) {
        synchronized(transition) {
            if (mine != generation || _state.value !is State.Connected) return
            lostLocked(error)
        }
    }

    private fun lostLocked(error: Throwable) {
        val link = fire
        // A TV that refuses the token has forgotten this phone: drop the saved pairing so the next connect asks for a code.
        if (error is FireTvAuthException && link != null) {
            appContext?.let { prefs(it).edit().remove(KEY_TOKEN_PREFIX + link.host).apply() }
        }
        runCatching { connection?.close() }
        connection = null
        fire = null
        runCatching { atv?.close() }
        atv = null
        runCatching { socketRemote?.close() }
        socketRemote = null
        sender?.cancel() // when the connection ended on the reader's side, the sender is still waiting for buttons
        closeVolume()
        newQueue()
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
        if (!commands.trySend(task).isSuccess) donePending() // the connection just ended
    }

    private fun donePending() {
        pending.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /** Throws away what is still queued and starts a fresh queue for the next connection. */
    private fun newQueue() {
        commands.cancel()
        commands = Channel(Channel.UNLIMITED)
    }

    /** Runs [block] (the moment a connection becomes the current one) unless it was cancelled meanwhile. Returns whether it ran. */
    private inline fun ifCurrent(mine: Int, block: () -> Unit): Boolean = synchronized(transition) {
        if (mine != generation) return false
        block()
        true
    }

    /** True when button [code] does something on the connected TV (the Fire TV service has no volume or Enter, for example). */
    fun supportsKey(link: Link, code: Int) = link != Link.FireTv || FireTvCommands.supports(code)

    /** One press of [code]. With [repeating] (a held button) a press is skipped while the TV is still catching up. */
    fun key(code: Int, repeating: Boolean = false) {
        if (repeating && pending.get() >= 1) return // one at a time: an even pace instead of bursts and gaps
        val link = fire
        val androidTv = atv
        val remote = socketRemote
        // A held fast-forward would keep asking the TV to speed up; only a single press is sent there.
        if (repeating && (link != null || androidTv != null || remote != null) && (code == TvKey.FAST_FORWARD || code == TvKey.REWIND)) return
        val adb = connection
        if (link != null && (code == TvKey.VOLUME_UP || code == TvKey.VOLUME_DOWN || code == TvKey.MUTE)) {
            sendVolume(code)
            return
        }
        when {
            link != null -> if (FireTvCommands.supports(code)) enqueue { sendPress(link, code) }
            androidTv != null -> enqueue { androidTv.key(code) }
            remote != null -> if (remote.supports(code)) enqueue { remote.press(code) }
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
        val androidTv = atv
        val remote = socketRemote
        val adb = connection
        when {
            remote != null -> if (remote.supports(code)) enqueue { remote.longPress(code) }
            androidTv != null -> enqueue {
                androidTv.key(code, AndroidTvCommands.START_LONG)
                Thread.sleep(LONG_PRESS_MS) // the sender runs on its own IO thread; later buttons wait this one second
                androidTv.key(code, AndroidTvCommands.END_LONG)
            }
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

    /**
     * Android TV: the owner is holding the microphone button. The TV's voice assistant is opened (the search key) and the
     * phone's microphone is streamed to it until [voiceEnd]. Returns false when this can't start (not an Android TV, or the
     * microphone isn't allowed yet).
     */
    fun voiceStart(): Boolean {
        val link = atv ?: return false
        val app = appContext ?: return false
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        voiceHeld = true
        // The session of the last press is still open and taking sound: this press carries on with it. One that is already
        // being closed can't be joined, so it gets a session of its own.
        if (voiceJob?.isActive == true && !voiceEnding) return true
        _voiceError.value = null
        voiceEnding = false
        val serial = ++voiceSerial
        val mine = generation
        voiceJob = scope.launch {
            val latest = { serial == voiceSerial && mine == generation }
            try {
                val session = link.startVoice(VOICE_BEGIN_MS)
                if (session == null) {
                    if (latest()) _voiceError.value = "This TV didn't open its voice assistant."
                    return@launch
                }
                try {
                    val keepGoing = {
                        val go = voiceHeld && mine == generation
                        if (!go && serial == voiceSerial) voiceEnding = true // from here a new press starts its own session
                        go
                    }
                    if (keepGoing()) VoiceRecorder.record(keepGoing) { link.sendVoice(session, it) }
                } finally {
                    if (serial == voiceSerial) voiceEnding = true
                    runCatching { link.endVoice(session) }
                }
            } catch (e: Throwable) {
                // Only the voice ends: the rest of the remote keeps working.
                if (latest()) _voiceError.value = "Voice stopped: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return true
    }

    /** The microphone button was let go. */
    fun voiceEnd() {
        voiceHeld = false
    }

    /** The text box on the phone changed from [old] to [new]: do the same edit on the TV, as it is typed. */
    fun typed(old: String, new: String) {
        val link = fire
        val androidTv = atv
        val remote = socketRemote
        val adb = connection
        when {
            remote != null -> enqueue { remote.typed(old, new) }
            androidTv != null -> AndroidTvKeys.edit(old, new).forEach { code -> enqueue { androidTv.key(code) } }
            // The Fire TV takes the whole text, so what is sent is always the full box (and a backspace is just a shorter text).
            link != null -> enqueue { link.send(FireTvCommands.text(new)) }
            adb != null -> TvCommands.edit(old, new).forEach { command -> enqueue { shellGuarded(adb, command) } }
        }
    }

    /** Opens [url] on the TV (ADB, Android TV, Samsung or LG). */
    fun openUrl(url: String) {
        val remote = socketRemote
        if (remote != null) {
            enqueue { remote.openUrl(url) }
            return
        }
        val androidTv = atv
        if (androidTv != null) {
            enqueue { androidTv.openLink(url) }
            return
        }
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
        LanNetwork.refresh(context)
        val own = NetworkUtils.resolveLocalIpv4(context.applicationContext)?.hostAddress ?: run {
            _state.value = State.Failed("Connect the phone to Wi-Fi first.")
            return
        }
        _scanning.value = true
        _found.value = emptyList()
        scope.launch {
            try {
                val prefix = own.substringBeforeLast('.')
                val gate = Semaphore(48) // (the IO threads are 64: this leaves room for everything else the app does)
                // Every address is asked on each kind of TV's remote port; one that answers on any of them is listed.
                val ports = listOf(
                    AndroidTvCommands.PAIR_PORT, FireTvCommands.WAKE_PORT, SamsungTvCommands.SECURE_PORT,
                    LgTvCommands.SECURE_PORT, LgTvCommands.PLAIN_PORT, TvCommands.DEFAULT_PORT,
                )
                val hits = (1..254).flatMap { n ->
                    val host = "$prefix.$n"
                    if (host == own) emptyList() else ports.map { port ->
                        async {
                            val found = gate.withPermit {
                                reachable(host, port, 300) && (port != FireTvCommands.WAKE_PORT || answersPlainHttp(host, port))
                            }
                            if (found) host else null
                        }
                    }
                }.awaitAll().filterNotNull().distinct()
                _found.value = hits
            } finally {
                _scanning.value = false
            }
        }
    }

    /** A Fire TV answers plain HTTP on its wake-up port; a Chromecast has the same port open but speaks TLS there. */
    private fun answersPlainHttp(host: String, port: Int): Boolean = try {
        val connection = LanNetwork.open(URL("http://$host:$port/")) as HttpURLConnection
        try {
            connection.connectTimeout = 1_000
            connection.readTimeout = 1_000
            connection.responseCode // any answer will do, even an error page
            true
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        false
    }

    private fun looksLikeFireTv(host: String): Boolean =
        reachable(host, FireTvCommands.WAKE_PORT, ATV_PROBE_MS) && answersPlainHttp(host, FireTvCommands.WAKE_PORT)

    private fun reachable(host: String, port: Int, timeoutMs: Int = 2_000): Boolean =
        runCatching { Socket().use { LanNetwork.bind(it); it.connect(InetSocketAddress(host, port), timeoutMs) } }.isSuccess

    /** This phone's identity for ADB: made once, then reused so the TV's "Always allow" keeps working. */
    private fun keyPair(context: Context): AdbKeyPair = synchronized(keyLock) { // one at a time: two writers could leave a mismatched pair
        val privateKey = File(context.filesDir, "adb_key")
        val publicKey = File(context.filesDir, "adb_key.pub")
        if (privateKey.exists() && publicKey.exists()) {
            runCatching { AdbKeyPair.read(privateKey, publicKey) }.getOrNull()?.let { return it }
        }
        // Missing or unreadable (half-written, damaged): make a new one. The TV will ask to Allow again.
        AdbKeyPair.generate(privateKey, publicKey)
        AdbKeyPair.read(privateKey, publicKey)
    }

    private val keyLock = Any()
}
