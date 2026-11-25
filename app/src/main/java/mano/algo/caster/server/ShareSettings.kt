package mano.algo.caster.server

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ShareMode { ALL, SELECTED, FOLDERS }

/**
 * The owner's sharing settings. [ids] are MediaStore video ids and only matter in [ShareMode.SELECTED].
 * [port] is the port the owner prefers; if it is busy the server moves to the next free one.
 * [hostname] is the `<name>.local` the phone announces; if another device uses it, a `-2`, `-3`... is added.
 */
data class ShareSelection(
    val mode: ShareMode = ShareMode.ALL,
    val ids: Set<Long> = emptySet(),
    /** MediaStore bucket (folder) ids, for [ShareMode.FOLDERS]: every video in them is shared, new ones included. */
    val folders: Set<Long> = emptySet(),
    val port: Int = ShareSettings.DEFAULT_PORT,
    val hostname: String = ShareSettings.DEFAULT_HOSTNAME,
    /** Also list the phone's photos and music. */
    val shareExtras: Boolean = false,
    val photoMode: ShareMode = ShareMode.FOLDERS,
    val photoFolders: Set<Long> = emptySet(),
    val photoIds: Set<Long> = emptySet(),
    val musicMode: ShareMode = ShareMode.FOLDERS,
    val musicFolders: Set<Long> = emptySet(),
    val musicIds: Set<Long> = emptySet(),
    /** Sharing stops by itself this many minutes after it starts; 0 means never. */
    val autoStopMinutes: Int = 0,
    /** Offer to start sharing (a notification) when the phone joins a Wi-Fi network. */
    val wifiOffer: Boolean = false,
    /** Stop sharing when the Wi-Fi is lost, so nothing keeps running away from home. */
    val stopOnWifiLost: Boolean = false,
    /** Optional password a connecting screen must send before the pairing code is shown; empty means no password. */
    val password: String = "",
    /** Whether connected screens/browsers are permitted to download media files. */
    val allowDownload: Boolean = true,
)

/**
 * The owner's choice between sharing the whole gallery or only picked videos, remembered across launches.
 * The server asks [isAllowed] on every request, so a change applies immediately and a client can never
 * reach a video outside the selection by guessing its id.
 */
object ShareSettings {
    private const val PREFS = "caster_share"
    private const val KEY_MODE = "mode"
    private const val KEY_PHOTO_MODE = "photo_mode"
    private const val KEY_PHOTO_FOLDERS = "photo_folders"
    private const val KEY_PHOTO_IDS = "photo_ids"
    private const val KEY_MUSIC_MODE = "music_mode"
    private const val KEY_MUSIC_FOLDERS = "music_folders"
    private const val KEY_MUSIC_IDS = "music_ids"
    private const val KEY_IDS = "ids"
    private const val KEY_FOLDERS = "folders"
    private const val KEY_PORT = "port"
    private const val KEY_HOSTNAME = "hostname"
    private const val KEY_EXTRAS = "extras"
    private const val KEY_AUTO_STOP = "auto_stop"
    private const val KEY_WIFI_OFFER = "wifi_offer"
    private const val KEY_WIFI_STOP = "wifi_stop"
    private const val KEY_INSTANCE_ID = "instance_id"
    private const val KEY_PASSWORD = "password"
    private const val KEY_ALLOW_DOWNLOAD = "allow_download"

    const val DEFAULT_HOSTNAME = MdnsResponder.DEFAULT_HOSTNAME
    private val HOSTNAME = Regex("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$")

    /** A single DNS label: lowercase letters, digits and hyphens, not starting or ending with a hyphen. */
    fun isValidHostname(name: String) = HOSTNAME.matches(name)

    const val DEFAULT_PORT = 8080
    /** Android apps can't bind ports below 1024. */
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    fun isValidPort(port: Int) = port in MIN_PORT..MAX_PORT

    private val _state = MutableStateFlow(ShareSelection())
    val state: StateFlow<ShareSelection> = _state.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null

    /**
     * A random id that stays the same for this install. Pages use it to tell that two addresses (an IP and a
     * name, or an old name and a new one) are the same phone, so it is never listed twice.
     */
    @Volatile var instanceId: String = ""
        private set

    /** Loads the saved choice; safe to call repeatedly from any thread. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val mode = runCatching { ShareMode.valueOf(p.getString(KEY_MODE, null).orEmpty()) }.getOrDefault(ShareMode.ALL)
            val ids = p.getStringSet(KEY_IDS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
            val port = p.getInt(KEY_PORT, DEFAULT_PORT).takeIf(::isValidPort) ?: DEFAULT_PORT
            val hostname = p.getString(KEY_HOSTNAME, null)?.takeIf(::isValidHostname) ?: DEFAULT_HOSTNAME
            val folders = p.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
            val photoMode = runCatching { ShareMode.valueOf(p.getString(KEY_PHOTO_MODE, null).orEmpty()) }.getOrDefault(ShareMode.FOLDERS)
            val photoFolders = p.getStringSet(KEY_PHOTO_FOLDERS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
            val photoIds = p.getStringSet(KEY_PHOTO_IDS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
            val musicMode = runCatching { ShareMode.valueOf(p.getString(KEY_MUSIC_MODE, null).orEmpty()) }.getOrDefault(ShareMode.FOLDERS)
            val musicFolders = p.getStringSet(KEY_MUSIC_FOLDERS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
            val musicIds = p.getStringSet(KEY_MUSIC_IDS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
            appContext = context.applicationContext
            _state.value = ShareSelection(
                mode, ids, folders, port, hostname,
                shareExtras = p.getBoolean(KEY_EXTRAS, false),
                photoMode = photoMode,
                photoFolders = photoFolders,
                photoIds = photoIds,
                musicMode = musicMode,
                musicFolders = musicFolders,
                musicIds = musicIds,
                autoStopMinutes = p.getInt(KEY_AUTO_STOP, 0).coerceAtLeast(0),
                wifiOffer = p.getBoolean(KEY_WIFI_OFFER, false),
                stopOnWifiLost = p.getBoolean(KEY_WIFI_STOP, false),
                password = p.getString(KEY_PASSWORD, "").orEmpty(),
                allowDownload = p.getBoolean(KEY_ALLOW_DOWNLOAD, true),
            )
            instanceId = p.getString(KEY_INSTANCE_ID, null) ?: ByteArray(6).also(java.security.SecureRandom()::nextBytes)
                .joinToString("") { "%02x".format(it) }
                .also { p.edit().putString(KEY_INSTANCE_ID, it).apply() }
            prefs = p
        }
    }

    fun setMode(mode: ShareMode) {
        _state.update { it.copy(mode = mode) }
        persist()
    }

    fun setFolders(folders: Set<Long>) {
        _state.update { it.copy(folders = folders) }
        persist()
    }

    fun setSelected(ids: Set<Long>) {
        _state.update { it.copy(ids = ids) }
        persist()
    }

    fun setPhotoMode(mode: ShareMode) {
        _state.update { it.copy(photoMode = mode) }
        persist()
    }

    fun setPhotoFolders(folders: Set<Long>) {
        _state.update { it.copy(photoFolders = folders) }
        persist()
    }

    fun setPhotoSelected(ids: Set<Long>) {
        _state.update { it.copy(photoIds = ids) }
        persist()
    }

    fun setMusicMode(mode: ShareMode) {
        _state.update { it.copy(musicMode = mode) }
        persist()
    }

    fun setMusicFolders(folders: Set<Long>) {
        _state.update { it.copy(musicFolders = folders) }
        persist()
    }

    fun setMusicSelected(ids: Set<Long>) {
        _state.update { it.copy(musicIds = ids) }
        persist()
    }

    /** Ignores out-of-range values; the server restarts on the new port by itself. */
    fun setPort(port: Int) {
        if (!isValidPort(port)) return
        _state.update { it.copy(port = port) }
        persist()
    }

    /** Ignores invalid names; the phone re-announces itself under the new name by itself. */
    fun setHostname(name: String) {
        val clean = name.trim().lowercase()
        if (!isValidHostname(clean)) return
        _state.update { it.copy(hostname = clean) }
        persist()
    }

    fun setShareExtras(enabled: Boolean) {
        _state.update { it.copy(shareExtras = enabled) }
        persist()
    }

    fun setAutoStopMinutes(minutes: Int) {
        _state.update { it.copy(autoStopMinutes = minutes.coerceAtLeast(0)) }
        persist()
    }

    fun setWifiOffer(on: Boolean) {
        _state.update { it.copy(wifiOffer = on) }
        persist()
    }

    fun setStopOnWifiLost(on: Boolean) {
        _state.update { it.copy(stopOnWifiLost = on) }
        persist()
    }

    fun setPassword(password: String) {
        _state.update { it.copy(password = password) }
        persist()
    }

    fun setAllowDownload(allowed: Boolean) {
        _state.update { it.copy(allowDownload = allowed) }
        persist()
    }

    @Volatile private var appContext: Context? = null
    // The folder of the videos asked about lately (a plain map, so it also works in tests): the oldest is dropped past 4096.
    private class Folder(val bucket: Long, val at: Long)
    private val buckets = object : LinkedHashMap<Long, Folder>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Folder>) = size > 4096
    }
    /** A video moved to another folder is noticed after this long. */
    private const val FOLDER_TTL_MS = 60_000L

    /** The folder a video is in. A list that already has it passes it as [bucketId]; otherwise it is looked up (and remembered). */
    private fun bucketOf(id: Long): Long? {
        synchronized(buckets) { buckets[id] }?.let { if (System.currentTimeMillis() - it.at < FOLDER_TTL_MS) return it.bucket }
        val context = appContext ?: return null
        val found = runCatching {
            context.contentResolver.query(
                android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                arrayOf(android.provider.MediaStore.Video.Media.BUCKET_ID), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()
        if (found != null) synchronized(buckets) { buckets[id] = Folder(found, System.currentTimeMillis()) }
        return found
    }

    fun isAllowed(id: Long, bucketId: Long? = null): Boolean {
        val s = state.value
        return when (s.mode) {
            ShareMode.ALL -> true
            ShareMode.SELECTED -> id in s.ids
            ShareMode.FOLDERS -> {
                if (bucketId != null) synchronized(buckets) { buckets[id] = Folder(bucketId, System.currentTimeMillis()) }
                (bucketId ?: bucketOf(id))?.let { it in s.folders } == true
            }
        }
    }

    private fun photoBucketOf(id: Long): Long? {
        val context = appContext ?: return null
        return runCatching {
            context.contentResolver.query(
                android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                arrayOf(android.provider.MediaStore.Images.Media.BUCKET_ID), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()
    }

    fun isPhotoAllowed(id: Long, bucketId: Long? = null): Boolean {
        val s = state.value
        if (!s.shareExtras) return false
        return when (s.photoMode) {
            ShareMode.ALL -> true
            ShareMode.SELECTED -> id in s.photoIds
            ShareMode.FOLDERS -> {
                val b = bucketId ?: photoBucketOf(id)
                b != null && b in s.photoFolders
            }
        }
    }

    private fun musicAlbumOf(id: Long): Long? {
        val context = appContext ?: return null
        return runCatching {
            context.contentResolver.query(
                android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                arrayOf(android.provider.MediaStore.Audio.Media.ALBUM_ID), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()
    }

    fun isMusicAllowed(id: Long, albumId: Long? = null): Boolean {
        val s = state.value
        if (!s.shareExtras) return false
        return when (s.musicMode) {
            ShareMode.ALL -> true
            ShareMode.SELECTED -> id in s.musicIds
            ShareMode.FOLDERS -> {
                val a = albumId ?: musicAlbumOf(id)
                a != null && a in s.musicFolders
            }
        }
    }

    private fun persist() {
        val s = _state.value
        prefs?.edit()
            ?.putString(KEY_MODE, s.mode.name)
            ?.putStringSet(KEY_IDS, s.ids.map { it.toString() }.toSet())
            ?.putStringSet(KEY_FOLDERS, s.folders.map { it.toString() }.toSet())
            ?.putString(KEY_PHOTO_MODE, s.photoMode.name)
            ?.putStringSet(KEY_PHOTO_FOLDERS, s.photoFolders.map { it.toString() }.toSet())
            ?.putStringSet(KEY_PHOTO_IDS, s.photoIds.map { it.toString() }.toSet())
            ?.putString(KEY_MUSIC_MODE, s.musicMode.name)
            ?.putStringSet(KEY_MUSIC_FOLDERS, s.musicFolders.map { it.toString() }.toSet())
            ?.putStringSet(KEY_MUSIC_IDS, s.musicIds.map { it.toString() }.toSet())
            ?.putInt(KEY_PORT, s.port)
            ?.putString(KEY_HOSTNAME, s.hostname)
            ?.putBoolean(KEY_EXTRAS, s.shareExtras)
            ?.putInt(KEY_AUTO_STOP, s.autoStopMinutes)
            ?.putBoolean(KEY_WIFI_OFFER, s.wifiOffer)
            ?.putBoolean(KEY_WIFI_STOP, s.stopOnWifiLost)
            ?.putString(KEY_PASSWORD, s.password)
            ?.putBoolean(KEY_ALLOW_DOWNLOAD, s.allowDownload)
            ?.apply()
    }
}
