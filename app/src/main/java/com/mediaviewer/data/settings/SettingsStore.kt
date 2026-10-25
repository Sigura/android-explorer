package com.mediaviewer.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediaviewer.domain.CropMode
import com.mediaviewer.domain.FolderSort
import com.mediaviewer.domain.SortBy
import com.mediaviewer.domain.SortSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class Transition { NONE, FADE, SLIDE }
enum class RestoreMode { OFF, FOLDER, FOLDER_AND_SLIDESHOW }
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/** README §2, "Desktops": AUTO = desktop clicks in Samsung DeX and on Chromebooks, gallery clicks elsewhere. */
enum class MouseMode { AUTO, GALLERY, DESKTOP }

/** Every setting from README §9. Stored as soon as it changes (README §7.1). */
data class AppSettings(
    val intervalSec: Int = 14,
    val transition: Transition = Transition.FADE,
    val invertSwipe: Boolean = false,
    val random: Boolean = false,
    val restore: RestoreMode = RestoreMode.FOLDER_AND_SLIDESHOW,
    val largeGifMb: Int = 40,
    /** Videos longer than this open in an external player when one is installed (README §3.11); 0 = off. */
    val longVideoMin: Int = 3,
    /** "Text files without encoding" (README §3.10); empty = by device language. */
    val textCharset: String = "",
    val muted: Boolean = true,
    val tileDp: Int = 160,
    /** The thumbnail size follows the window width until the user picks one (README §2, "Folder view"). */
    val tileAuto: Boolean = true,
    val cropMode: CropMode = CropMode.SMART,
    val cropThresholdPct: Int = 10,
    val sort: SortSpec = SortSpec(),
    val showHidden: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val favourites: Set<String> = emptySet(),
    /** How many recent folders the root screen shows (README §4 "Root"); up to MAX_RECENT are kept. */
    val recentCount: Int = 20,
    val showNames: Boolean = false,
    /** The tap-zone hint has been shown once in the slideshow (README §5.2). */
    val zonesHintShown: Boolean = false,
    val mouseMode: MouseMode = MouseMode.AUTO,
    /** The "maximise the window" hint has been shown once in a windowed slideshow (README §3.9). */
    val windowHintShown: Boolean = false,
    /** Editor text size in sp (todo §6.6), one of `EDITOR_TEXT_STEPS`. */
    val editorTextSp: Int = 14,
    val editorWrap: Boolean = true,
    /** A tap on a text file opens the editor; otherwise another app (todo §6.2). */
    val textInApp: Boolean = true,
)

/** README §7.2. The anchor is the key of the first visible item plus its pixel offset. */
data class RecentFolder(
    val path: String,
    val lastOpened: Long,
    val anchorKey: String? = null,
    val anchorOffset: Int = 0,
    val lastViewed: String? = null,
)

/** README §7.3. */
data class SlideshowSession(
    val active: Boolean,
    val folder: String,
    val current: String,
    val random: Boolean,
    val seed: Long,
    val pinnedFirst: String?,
    val positionMs: Long,
    val listCount: Int,
    val listHash: Int,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(context: Context, private val scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore
    private val mutex = Mutex()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _recent = MutableStateFlow<List<RecentFolder>>(emptyList())
    val recent: StateFlow<List<RecentFolder>> = _recent.asStateFlow()

    var session: SlideshowSession? = null
        private set

    private val _folderSorts = MutableStateFlow<Map<String, FolderSort>>(emptyMap())
    /** Sort chosen in a folder's top bar, by folder path (README §2, "Folder view"); oldest dropped first. */
    val folderSorts: StateFlow<Map<String, FolderSort>> = _folderSorts.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** The splash screen stays up until this is true, so the UI never flashes defaults. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private object K {
        val interval = intPreferencesKey("interval_sec")
        val transition = stringPreferencesKey("transition")
        val invertSwipe = booleanPreferencesKey("invert_swipe")
        val random = booleanPreferencesKey("random")
        val restore = stringPreferencesKey("restore")
        val largeGif = intPreferencesKey("large_gif_mb")
        val longVideo = intPreferencesKey("long_video_min")
        val textCharset = stringPreferencesKey("text_charset")
        val muted = booleanPreferencesKey("muted")
        val tileDp = intPreferencesKey("tile_dp")
        val tileAuto = booleanPreferencesKey("tile_auto")
        val cropMode = stringPreferencesKey("crop_mode")
        val cropThreshold = intPreferencesKey("crop_threshold")
        val sortBy = stringPreferencesKey("sort_by")
        val sortDesc = booleanPreferencesKey("sort_desc")
        val foldersFirst = booleanPreferencesKey("folders_first")
        val showHidden = booleanPreferencesKey("show_hidden")
        val theme = stringPreferencesKey("theme")
        val favourites = stringSetPreferencesKey("favourites")
        val recentCount = intPreferencesKey("recent_count")
        val showNames = booleanPreferencesKey("show_names")
        val zonesHint = booleanPreferencesKey("zones_hint_shown")
        val mouseMode = stringPreferencesKey("mouse_mode")
        val windowHint = booleanPreferencesKey("window_hint_shown")
        val editorTextSp = intPreferencesKey("editor_text_sp")
        val editorWrap = booleanPreferencesKey("editor_wrap")
        val textInApp = booleanPreferencesKey("text_open_in_app")
        val recent = stringPreferencesKey("recent")
        val session = stringPreferencesKey("session")
        val folderSorts = stringPreferencesKey("folder_sorts")
    }

    suspend fun load() {
        val p = try {
            store.data.first()
        } catch (e: Exception) {
            null
        }
        if (p != null) {
            _settings.value = read(p)
            _recent.value = p[K.recent]?.let(::parseRecent) ?: emptyList()
            session = p[K.session]?.let(::parseSession)
            _folderSorts.value = p[K.folderSorts]?.let(::parseFolderSorts) ?: emptyMap()
        }
        _loaded.value = true
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        write { write(it, next) }
    }

    fun updateRecent(transform: (List<RecentFolder>) -> List<RecentFolder>) {
        val next = transform(_recent.value).sortedByDescending { it.lastOpened }.take(MAX_RECENT)
        _recent.value = next
        val json = JSONArray().apply {
            next.forEach { r ->
                put(
                    JSONObject().put("path", r.path).put("t", r.lastOpened).put("ak", r.anchorKey ?: JSONObject.NULL)
                        .put("ao", r.anchorOffset).put("lv", r.lastViewed ?: JSONObject.NULL),
                )
            }
        }.toString()
        write { it[K.recent] = json }
    }

    /** Remembers [sort] for [path]; null goes back to the default order. */
    fun setFolderSort(path: String, sort: FolderSort?) {
        val next = LinkedHashMap(_folderSorts.value)
        next.remove(path)
        if (sort != null) next[path] = sort
        while (next.size > MAX_FOLDER_SORTS) next.remove(next.keys.first())
        _folderSorts.value = next
        val json = JSONArray().apply {
            next.forEach { (p, f) -> put(JSONObject().put("path", p).put("by", f.by.name).put("desc", f.descending)) }
        }.toString()
        write { it[K.folderSorts] = json }
    }

    fun saveSession(s: SlideshowSession?) {
        session = s
        val json = s?.let {
            JSONObject().put("active", it.active).put("folder", it.folder).put("current", it.current)
                .put("random", it.random).put("seed", it.seed).put("pinned", it.pinnedFirst ?: JSONObject.NULL)
                .put("pos", it.positionMs).put("count", it.listCount).put("hash", it.listHash).toString()
        }
        write { if (json == null) it.remove(K.session) else it[K.session] = json }
    }

    private fun write(block: (MutablePreferences) -> Unit) {
        scope.launch {
            mutex.withLock {
                try {
                    store.edit(block)
                } catch (e: Exception) {
                    android.util.Log.w("MediaViewer", "Settings write failed", e)
                }
            }
        }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            intervalSec = (p[K.interval] ?: d.intervalSec).coerceIn(1, 40),
            transition = enumOr(p[K.transition], d.transition),
            invertSwipe = p[K.invertSwipe] ?: d.invertSwipe,
            random = p[K.random] ?: d.random,
            restore = enumOr(p[K.restore], d.restore),
            largeGifMb = p[K.largeGif] ?: d.largeGifMb,
            longVideoMin = (p[K.longVideo] ?: d.longVideoMin).coerceIn(0, 60),
            textCharset = p[K.textCharset] ?: d.textCharset,
            muted = p[K.muted] ?: d.muted,
            tileDp = p[K.tileDp] ?: d.tileDp,
            tileAuto = p[K.tileAuto] ?: d.tileAuto,
            cropMode = enumOr(p[K.cropMode], d.cropMode),
            cropThresholdPct = p[K.cropThreshold] ?: d.cropThresholdPct,
            sort = SortSpec(
                enumOr(p[K.sortBy], SortBy.DATE),
                p[K.sortDesc] ?: true,
                p[K.foldersFirst] ?: true,
            ),
            showHidden = p[K.showHidden] ?: d.showHidden,
            theme = enumOr(p[K.theme], d.theme),
            favourites = p[K.favourites] ?: emptySet(),
            recentCount = (p[K.recentCount] ?: d.recentCount).coerceIn(1, MAX_RECENT),
            showNames = p[K.showNames] ?: d.showNames,
            zonesHintShown = p[K.zonesHint] ?: false,
            mouseMode = enumOr(p[K.mouseMode], d.mouseMode),
            windowHintShown = p[K.windowHint] ?: false,
            editorTextSp = (p[K.editorTextSp] ?: d.editorTextSp).coerceIn(8, 40),
            editorWrap = p[K.editorWrap] ?: d.editorWrap,
            textInApp = p[K.textInApp] ?: d.textInApp,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[K.interval] = s.intervalSec
        p[K.transition] = s.transition.name
        p[K.invertSwipe] = s.invertSwipe
        p[K.random] = s.random
        p[K.restore] = s.restore.name
        p[K.largeGif] = s.largeGifMb
        p[K.longVideo] = s.longVideoMin
        p[K.textCharset] = s.textCharset
        p[K.muted] = s.muted
        p[K.tileDp] = s.tileDp
        p[K.tileAuto] = s.tileAuto
        p[K.cropMode] = s.cropMode.name
        p[K.cropThreshold] = s.cropThresholdPct
        p[K.sortBy] = s.sort.by.name
        p[K.sortDesc] = s.sort.descending
        p[K.foldersFirst] = s.sort.foldersFirst
        p[K.showHidden] = s.showHidden
        p[K.theme] = s.theme.name
        p[K.favourites] = s.favourites
        p[K.recentCount] = s.recentCount
        p[K.showNames] = s.showNames
        p[K.zonesHint] = s.zonesHintShown
        p[K.mouseMode] = s.mouseMode.name
        p[K.windowHint] = s.windowHintShown
        p[K.editorTextSp] = s.editorTextSp
        p[K.editorWrap] = s.editorWrap
        p[K.textInApp] = s.textInApp
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun parseRecent(json: String): List<RecentFolder> = try {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            RecentFolder(
                o.getString("path"),
                o.optLong("t"),
                o.optString("ak").takeIf { !o.isNull("ak") && it.isNotEmpty() },
                o.optInt("ao"),
                o.optString("lv").takeIf { !o.isNull("lv") && it.isNotEmpty() },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun parseFolderSorts(json: String): Map<String, FolderSort> = try {
        val a = JSONArray(json)
        val out = LinkedHashMap<String, FolderSort>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out[o.getString("path")] = FolderSort(enumOr(o.optString("by"), SortBy.NAME), o.optBoolean("desc"))
        }
        out
    } catch (e: Exception) {
        emptyMap()
    }

    private fun parseSession(json: String): SlideshowSession? = try {
        val o = JSONObject(json)
        SlideshowSession(
            o.getBoolean("active"),
            o.getString("folder"),
            o.getString("current"),
            o.optBoolean("random"),
            o.optLong("seed"),
            o.optString("pinned").takeIf { !o.isNull("pinned") && it.isNotEmpty() },
            o.optLong("pos"),
            o.optInt("count"),
            o.optInt("hash"),
        )
    } catch (e: Exception) {
        null
    }

    companion object {
        const val MAX_RECENT = 50

        /** Folders whose own sort is kept; a few dozen bytes each. */
        const val MAX_FOLDER_SORTS = 2000
    }
}
