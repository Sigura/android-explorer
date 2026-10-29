package com.mediaviewer.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mediaviewer.MediaViewerApp
import com.mediaviewer.data.UiMessage
import com.mediaviewer.data.abandonable
import com.mediaviewer.data.fs.Drafts
import com.mediaviewer.data.fs.DropImport
import com.mediaviewer.data.fs.FileOps
import com.mediaviewer.data.fs.FolderListing
import com.mediaviewer.data.fs.TextDoc
import com.mediaviewer.data.fs.TextFile
import com.mediaviewer.data.fs.Trash
import com.mediaviewer.data.fs.Volume
import com.mediaviewer.data.fs.Volumes
import com.mediaviewer.data.history.FileHistory
import com.mediaviewer.data.history.FolderHistory
import com.mediaviewer.data.history.LECTURE_AUTO
import com.mediaviewer.data.history.LECTURE_NO
import com.mediaviewer.data.history.LECTURE_YES
import com.mediaviewer.data.mtp.MtpDevices
import com.mediaviewer.data.mtp.MtpPaths
import com.mediaviewer.data.settings.RecentFolder
import com.mediaviewer.data.settings.RestoreMode
import com.mediaviewer.data.settings.SlideshowSession
import com.mediaviewer.data.tags.TrackExtrasLoader
import com.mediaviewer.data.thumbs.TextDecode
import com.mediaviewer.domain.FolderSort
import com.mediaviewer.domain.SortBy
import com.mediaviewer.domain.SortSpec
import com.mediaviewer.domain.Sorting
import com.mediaviewer.domain.Book
import com.mediaviewer.domain.BookKind
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.ui.common.ExternalApps
import com.mediaviewer.ui.editor.EditorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.charset.Charset

sealed interface Screen {
    data object Loading : Screen
    data object Permission : Screen
    data object Root : Screen
    data class Folder(val path: String) : Screen
    data class Slideshow(val folder: String, val start: String, val resume: SlideshowSession? = null) : Screen

    /** The text editor (todo §6) over [folder]'s listing; [draft] is unsaved text kept from an earlier visit. */
    data class Editor(val folder: String, val doc: TextDoc, val isNew: Boolean, val draft: String? = null) : Screen
}

/** Screens over the current one: Back closes them and returns where it was. PLAYER = the player screen (todo §4.1). */
enum class Overlay { SETTINGS, ERROR_LOG, PLAYER }

/** Play was pressed on the finished book in [folder] (todo §3.4). */
data class StartOver(val folder: String, val showPlayer: Boolean)

/** Makers whose battery savers are known to stop background playback (dontkillmyapp.com). */
private val AGGRESSIVE_BATTERY_SAVERS = setOf("samsung", "xiaomi", "huawei", "honor", "oppo", "vivo", "oneplus", "realme", "meizu", "asus")

/** A user-started operation that may be slow (README §2.1): shown after ~300 ms with Stop. */
data class Busy(
    val label: String,
    val startedAt: Long,
    val onStop: () -> Unit,
    /** 0..1 when progress is known: a progress bar instead of a spinner. */
    val progress: Float? = null,
    val id: Long = startedAt,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val mv = app as MediaViewerApp
    val settingsStore = mv.settings
    val settings = mv.settings.settings
    val errors = mv.errors
    val thumbnails = mv.thumbnails
    val bookPlayer = mv.bookPlayer
    val drafts = Drafts(File(app.filesDir, "drafts"))

    /** Cameras and phones over USB (todo §9), shown on the home screen. */
    val mtp = mv.mtp
    val devices: StateFlow<List<MtpDevices.Device>> = mtp.devices

    /** The open editor's state (todo §6), kept here so it survives the activity being recreated. */
    var editorState: EditorState? = null

    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _overlay = MutableStateFlow<Overlay?>(null)
    val overlay: StateFlow<Overlay?> = _overlay.asStateFlow()

    private val _busy = MutableStateFlow<Busy?>(null)
    val busy: StateFlow<Busy?> = _busy.asStateFlow()

    private val _listing = MutableStateFlow<FolderListing?>(null)
    val listing: StateFlow<FolderListing?> = _listing.asStateFlow()

    private val _volumes = MutableStateFlow<List<Volume>>(emptyList())
    val volumes: StateFlow<List<Volume>> = _volumes.asStateFlow()

    private val _startOver = MutableStateFlow<StartOver?>(null)
    /** A finished book's Play waits for *Start over?* (todo §3.4). */
    val startOver: StateFlow<StartOver?> = _startOver.asStateFlow()

    /** Item the folder view should scroll to and focus (README §4.7), consumed once. */
    private val _focusTarget = MutableStateFlow<String?>(null)
    val focusTarget: StateFlow<String?> = _focusTarget.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** The splash screen stays up until settings are loaded and the first screen is chosen. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _folderSort = MutableStateFlow(SortSpec())
    /** The order the open folder is shown in (README §2, "Folder view"); the slideshow follows it. */
    val folderSort: StateFlow<SortSpec> = _folderSort.asStateFlow()

    /** The listing and the order it was last sorted by, so an unchanged order isn't sorted again. */
    private var applied: Pair<FolderListing, SortSpec>? = null

    private var started = false
    private var busyJob: Job? = null

    /** The device folder a slideshow over its local mirror was started from (todo §9.6). */
    private var parked: FolderListing? = null
    private var prefetchJob: Job? = null

    /** Mounting and removal of USB drives and SD cards refresh [volumes]. */
    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshVolumes()
    }

    init {
        viewModelScope.launch {
            settingsStore.load()
            thumbnails.largeGifBytes = settings.value.largeGifMb * 1024L * 1024L
            launch(Dispatchers.IO) { mv.trash } // purge leftovers off the main thread
            if (hasStoragePermission()) start() else _screen.value = Screen.Permission
            _ready.value = true
        }
        mtp.start()
        // USB drives and SD cards: the home screen's Storage list follows mounting and removal.
        ContextCompat.registerReceiver(
            app, mountReceiver,
            IntentFilter().apply {
                listOf(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_EJECT)
                    .forEach(::addAction)
                addDataScheme("file")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        viewModelScope.launch {
            // An unplugged device (todo §9.7): leave its folders, like a removed SD card.
            devices.collect { list ->
                val ids = list.filter { it.state == MtpDevices.State.READY }.mapTo(HashSet()) { it.id }
                fun gone(path: String?) = path != null && MtpPaths.parse(path)?.deviceId?.let { it !in ids } == true
                val parkedGone = gone(parked?.path)
                if (parkedGone) {
                    prefetchJob?.cancel()
                    parked?.close()
                    parked = null
                }
                val l = _listing.value
                if (l != null && gone(l.path)) {
                    errors.notify("Device disconnected", l.path, log = false)
                    goRoot()
                } else if (parkedGone && _screen.value is Screen.Slideshow) {
                    errors.notify("Device disconnected", log = false)
                    _listing.value?.close()
                    goRoot()
                }
            }
        }
        viewModelScope.launch {
            // The open folder's order: settings, its own choice, and whether images turned up.
            _listing.flatMapLatest { l ->
                if (l == null) {
                    flowOf(null)
                } else {
                    combine(settings.map { it.sort }, settingsStore.folderSorts.map { it[l.path] }, l.hasImages) { global, own, images ->
                        l to Sorting.effective(global, own, images)
                    }
                }
            }.distinctUntilChanged().collect { pair ->
                val (l, spec) = pair ?: return@collect
                _folderSort.value = spec
                if (applied?.first === l && applied?.second == spec) return@collect
                applied = l to spec
                withContext(Dispatchers.Default) { l.resort(Sorting.comparator(spec)) }
            }
        }
        viewModelScope.launch {
            settings.map { it.showHidden }.distinctUntilChanged().collect {
                val l = _listing.value ?: return@collect
                reopen(l.path)
            }
        }
        viewModelScope.launch {
            settings.map { it.largeGifMb }.distinctUntilChanged().collect {
                thumbnails.largeGifBytes = it * 1024L * 1024L
            }
        }
        viewModelScope.launch {
            var first = true
            settings.map { it.textCharset }.distinctUntilChanged().collect { name ->
                thumbnails.legacyCharset = try {
                    Charset.forName(name.ifEmpty { TextDecode.defaultLegacyCharset() })
                } catch (e: Exception) {
                    Charsets.ISO_8859_1
                }
                // Text previews already on screen are drawn again with the new charset.
                if (!first) thumbnails.clearMemory()
                first = false
            }
        }
    }

    fun hasStoragePermission(): Boolean {
        val ctx = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * How the folder's Play button plays (todo §3.7): as a book, or null for the slideshow.
     * Lecture folders need every video's duration: read from MetaStore, or under the thumbnail
     * watchdog on IO. Never on the main thread.
     */
    suspend fun bookKind(entries: List<FileEntry>, lecture: Int = LECTURE_AUTO): BookKind? = withContext(Dispatchers.IO) {
        // Device folders (todo §9): no book playback yet, Play starts the slideshow.
        if (entries.any { MtpPaths.isMtp(it.path) }) return@withContext null
        val longMs = settings.value.longVideoMin * 60_000L
        val videos = entries.filter { it.type == MediaType.VIDEO }
        // The folder's own choice (todo §3.6) decides for folders of videos without audio.
        if (entries.none(Book::isAudio) && videos.isNotEmpty()) {
            if (lecture == LECTURE_YES) return@withContext BookKind.LECTURE
            if (lecture == LECTURE_NO) return@withContext null
        }
        val durations = HashMap<String, Long>()
        // Durations matter only for a folder of videos alone; very large folders aren't lecture series.
        if (entries.none(Book::isAudio) && videos.size in 2..MAX_LECTURE_VIDEOS) {
            for (v in videos) {
                val d = try {
                    thumbnails.videoMeta(v).durationMs
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    0L
                }
                durations[v.path] = d
                if (d <= (if (longMs > 0) longMs else Book.DEFAULT_LONG_MS)) break // one short video decides it
            }
        }
        Book.classify(entries, longMs) { durations[it.path] }
    }

    /**
     * The folder's Play button: the book, or the slideshow from the first media item. [showPlayer]
     * (a long press, `Shift` + P) opens the player screen as well (todo §4.1).
     */
    fun playFolder(path: String, entries: List<FileEntry>, kind: BookKind?, showPlayer: Boolean = false) {
        if (kind == null) {
            entries.firstOrNull { it.isMedia }?.let(::openSlideshow)
            return
        }
        playBook(path, showPlayer)
    }

    /**
     * Plays or continues the book in [path] — the folder bar, a folder tile's ▶ (todo §3.2) and
     * *Continue listening* (§3.4). A finished book asks *Start over?* first.
     */
    fun playBook(path: String, showPlayer: Boolean = false) {
        viewModelScope.launch {
            if (bookPlayer.isFinished(path)) _startOver.value = StartOver(path, showPlayer) else startBook(path, showPlayer)
        }
    }

    /** *Start over?* answered: [yes] plays the book from its first file. */
    fun answerStartOver(yes: Boolean) {
        val s = _startOver.value ?: return
        _startOver.value = null
        if (yes) viewModelScope.launch { startBook(s.folder, s.showPlayer) }
    }

    private suspend fun startBook(path: String, showPlayer: Boolean) {
        val first = bookPlayer.play(path)
        // The folder view focuses the file playing (README §2, "Listening").
        if (first == null) {
            errors.notify("Nothing to play here", path, log = false)
        } else {
            if (_listing.value?.path == path) _focusTarget.value = first
            if (showPlayer) _overlay.value = Overlay.PLAYER
            batteryHint()
        }
    }

    /**
     * Once (todo §3.3): phones whose battery savers are known to stop background playback get a
     * hint with a way to the battery optimisation list, unless the app is already exempt.
     */
    private fun batteryHint() {
        if (settings.value.batteryHintShown) return
        val ctx = getApplication<Application>()
        val maker = android.os.Build.MANUFACTURER.lowercase()
        if (maker !in AGGRESSIVE_BATTERY_SAVERS) return
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
        settingsStore.update { it.copy(batteryHintShown = true) }
        errors.message(
            UiMessage(
                "If playback stops with the screen off, allow the app to run in the background",
                actionLabel = "Settings",
                durationMs = 10_000,
                onAction = {
                    try {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: Exception) {
                        errors.notify("No battery settings found on this device", log = false)
                    }
                },
            ),
        )
    }

    /** A tap on an audio file: its folder plays from that file, and the player screen opens (todo §4.1). */
    fun playAudio(entry: FileEntry, orElse: () -> Unit) {
        viewModelScope.launch {
            if (bookPlayer.playFrom(entry.path)) {
                _overlay.value = Overlay.PLAYER
                batteryHint()
            } else {
                orElse()
            }
        }
    }

    /** Listening positions of [folder]'s files, for the slideshow (todo §3.6). */
    suspend fun listenPositions(folder: String): Map<String, FileHistory> = withContext(Dispatchers.IO) { mv.history.files(folder) }

    /** A video's listening position, or 0: the external player starts there (todo §3.6). */
    suspend fun listenPosition(path: String): Long = withContext(Dispatchers.IO) { mv.history.file(path)?.takeIf { !it.finished }?.positionMs ?: 0L }

    /** The slideshow reached [positionMs] in a video that has a listening position. */
    fun saveListenPosition(path: String, positionMs: Long, finished: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { mv.history.updateFilePosition(path, positionMs, finished) }
    }

    /** The folder's lecture override (todo §3.6): [LECTURE_AUTO], [LECTURE_YES] or [LECTURE_NO]. */
    suspend fun lectureMode(folder: String): Int = withContext(Dispatchers.IO) { mv.history.folder(folder)?.lecture ?: LECTURE_AUTO }

    fun setLectureMode(folder: String, mode: Int) {
        viewModelScope.launch(Dispatchers.IO) { mv.history.setLecture(folder, mode) }
    }

    /** *Continue listening* (todo §3.4): the last books played whose folder and file still exist, newest first. */
    suspend fun continueListening(limit: Int = 5): List<FolderHistory> = withContext(Dispatchers.IO) {
        mv.history.recentFolders(limit * 3).filter { h -> h.currentPath != null && File(h.currentPath).isFile }.take(limit)
    }

    /** A book card's cover: the current file's picture or the folder's cover image. */
    suspend fun bookCover(h: FolderHistory, px: Int): android.graphics.Bitmap? =
        h.currentPath?.let { withContext(Dispatchers.IO) { TrackExtrasLoader.cover(it, px) } }

    /** Books inside [parent] with listening history, by folder path (folder tiles, todo §10.1). */
    suspend fun booksIn(parent: String): Map<String, FolderHistory> = withContext(Dispatchers.IO) { mv.history.foldersIn(parent) }

    /** Called on resume: an external video player may have been installed or removed (README §3.11). */
    fun refreshExternalApps() {
        viewModelScope.launch { ExternalApps.refresh(getApplication()) }
        bookPlayer.connectIfRunning()
    }

    /** Called on resume: the user may have granted "All files access" in system settings. */
    fun onPermissionMaybeChanged() {
        if (_screen.value == Screen.Permission && hasStoragePermission()) {
            viewModelScope.launch { start() }
        }
    }

    private suspend fun start() {
        if (started) return
        started = true
        refreshVolumes()
        val s = settings.value
        val session = settingsStore.session
        // README §7.3: straight back into the slideshow if that is where the app stopped.
        if (s.restore == RestoreMode.FOLDER_AND_SLIDESHOW && session != null && session.active && !mtp.isMirror(session.folder) && isAvailable(session.folder)) {
            openFolder(session.folder, focus = session.current, showBusy = false)
            _screen.value = Screen.Slideshow(session.folder, session.current, session)
            errors.message(
                UiMessage(
                    "Resumed slideshow — tap to go to folder",
                    actionLabel = "Folder",
                    durationMs = 3000,
                    onAction = { closeSlideshow(null) },
                ),
            )
            return
        }
        // README §7.2: the first recent folder that is still available.
        if (s.restore != RestoreMode.OFF) {
            for (r in settingsStore.recent.value) {
                if (!MtpPaths.isMtp(r.path) && isAvailable(r.path)) {
                    openFolder(r.path, focus = r.lastViewed, showBusy = false)
                    return
                }
            }
        }
        _screen.value = Screen.Root
    }

    /** Off the main thread, with a short timeout so a slow USB drive can't block startup. */
    private suspend fun isAvailable(path: String): Boolean =
        abandonable(AVAILABILITY_TIMEOUT_MS) { File(path).let { it.isDirectory && it.canRead() } } == true

    fun refreshVolumes() {
        viewModelScope.launch {
            _volumes.value = withContext(Dispatchers.IO) { Volumes.list(getApplication()) }
        }
    }

    // ------------------------------------------------------------------ navigation

    fun openFolder(path: String, focus: String? = null, showBusy: Boolean = true) {
        _listing.value?.let { if (it.path != path) it.close() }
        val current = _listing.value
        val listing = if (current != null && current.path == path) {
            current
        } else {
            // Until an image is listed, the folder is ordered as one without images.
            val spec = Sorting.effective(settings.value.sort, settingsStore.folderSorts.value[path], hasImages = false)
            val remote = MtpPaths.isMtp(path)
            FolderListing(
                path, viewModelScope, Sorting.comparator(spec), settings.value.showHidden,
                scanner = if (remote) mtp::scan else com.mediaviewer.data.fs.FolderScanner::scan,
                watch = !remote,
                live = if (remote) mtp.watch(path, settings.value.showHidden) else null,
                onFailed = ::onListingFailed,
            )
                .also {
                    applied = it to spec
                    _folderSort.value = spec
                    it.start()
                }
        }
        _listing.value = listing
        _focusTarget.value = focus
        _screen.value = Screen.Folder(path)
        // Device paths last only until it is unplugged: never recent.
        if (!MtpPaths.isMtp(path)) touchRecent(path)
        if (showBusy) {
            setBusy("Reading folder…", onStop = { listing.stop() }) { listing.started.first { it } }
        }
    }

    private fun reopen(path: String) {
        _listing.value?.close()
        _listing.value = null
        openFolder(path, showBusy = false)
    }

    /** Continue a stopped listing ("Listing stopped — tap to continue"). */
    fun continueListing() {
        _listing.value?.start()
    }

    fun goUp() {
        val l = _listing.value ?: run {
            _screen.value = Screen.Root
            return
        }
        val path = l.path
        if (MtpPaths.isMtp(path)) {
            MtpPaths.parent(path)?.let { openFolder(it, focus = path) } ?: goRoot()
            return
        }
        val vols = _volumes.value
        val isVolumeRoot = vols.any { it.path == path }
        val parent = File(path).parentFile
        if (isVolumeRoot || parent == null || (Volumes.rootOf(path, vols) == null && !parent.canRead())) {
            goRoot()
        } else {
            openFolder(parent.path, focus = path)
        }
    }

    fun goRoot() {
        _listing.value?.close()
        _listing.value = null
        prefetchJob?.cancel()
        parked?.close()
        parked = null
        _screen.value = Screen.Root
        refreshVolumes()
    }

    fun openSlideshow(entry: FileEntry) {
        val l = _listing.value ?: return
        if (MtpPaths.isMtp(entry.path)) return openDeviceSlideshow(l, entry)
        thumbnails.clearFailure(entry.path)
        _screen.value = Screen.Slideshow(l.path, entry.path)
    }

    /** Back to the folder view, scrolled to and focused on the last viewed item (README §4.7). */
    fun closeSlideshow(lastViewed: String?) {
        val s = _screen.value as? Screen.Slideshow ?: return
        val session = settingsStore.session
        session?.let { settingsStore.saveSession(it.copy(active = false)) }
        if (mtp.isMirror(s.folder)) return closeDeviceSlideshow(lastViewed ?: session?.current?.takeIf { session.folder == s.folder })
        // System Back doesn't know the item: the session always holds the last one shown.
        val lastViewed = lastViewed ?: session?.current?.takeIf { session.folder == s.folder }
        if (lastViewed != null) {
            _focusTarget.value = lastViewed
            settingsStore.updateRecent { list -> list.map { if (it.path == s.folder) it.copy(lastViewed = lastViewed) else it } }
        }
        _screen.value = Screen.Folder(s.folder)
    }

    fun consumeFocusTarget() {
        _focusTarget.value = null
    }

    fun setFocusTarget(key: String) {
        _focusTarget.value = key
    }

    fun showOverlay(o: Overlay?) {
        _overlay.value = o
    }

    /** System Back. Returns false when the app should handle it (exit from root). */
    fun back(): Boolean {
        if (_busy.value != null) {
            stopBusy()
            return true
        }
        if (_overlay.value != null) {
            _overlay.value = null
            return true
        }
        return when (val s = _screen.value) {
            is Screen.Slideshow -> {
                closeSlideshow(null)
                true
            }
            is Screen.Folder -> {
                goUp()
                true
            }
            // The editor handles Back itself (unsaved changes); this is only a fallback.
            is Screen.Editor -> {
                closeEditor()
                true
            }
            else -> false
        }
    }

    // ------------------------------------------------------------------ text editor (todo §6)

    /**
     * Opens [entry] in the editor; files that turn out not to be text, or are too large, go to
     * [orElse] (another app). Read on IO under a watchdog, with the wait indicator.
     */
    fun openText(entry: FileEntry, orElse: () -> Unit) {
        val folder = _listing.value?.path ?: return
        setBusy("Opening ${entry.name}…", onStop = {}) {
            val r = abandonable(TEXT_IO_TIMEOUT_MS) {
                TextFile.load(File(entry.path), thumbnails.legacyCharset, TextDecode.cyrillicDetector)
            }
            when (r) {
                is TextFile.Load.Ok -> {
                    val draft = if (r.doc.readOnly) null else withContext(Dispatchers.IO) { drafts.get(entry.path) }
                    _screen.value = Screen.Editor(folder, r.doc, isNew = false, draft = draft?.text)
                }
                TextFile.Load.NotText, TextFile.Load.TooLarge -> orElse()
                is TextFile.Load.Failed -> errors.notify("Can't open ${entry.name}: ${r.reason}", entry.path)
                null -> errors.notify("${entry.name} took too long to read", entry.path)
            }
        }
    }

    /** New text file in the current folder (todo §6.1); [onError] gets the reason it failed. */
    fun createFile(name: String, onError: (String) -> Unit) {
        val folder = _listing.value?.path ?: return
        val file = File(folder, name)
        viewModelScope.launch {
            when (val r = abandonable(TEXT_IO_TIMEOUT_MS) { TextFile.create(file) ?: TextFile.newDoc(file) }) {
                null -> onError("The storage didn't answer")
                is String -> onError(r)
                is TextDoc -> _screen.value = Screen.Editor(folder, r, isNew = true)
            }
        }
    }

    /** New folder in the current folder, then opens it (todo §6.1). */
    fun createFolder(name: String, onError: (String) -> Unit) {
        val folder = _listing.value?.path ?: return
        if (MtpPaths.isMtp(folder)) {
            viewModelScope.launch {
                try {
                    openFolder(withContext(Dispatchers.IO) { mtp.mkdir(folder, name) })
                } catch (e: java.io.IOException) {
                    onError(e.message ?: "The device didn't create the folder")
                }
            }
            return
        }
        val dir = File(folder, name)
        viewModelScope.launch {
            when (val r = abandonable(TEXT_IO_TIMEOUT_MS) { TextFile.mkdir(dir) ?: "" }) {
                null -> onError("The storage didn't answer")
                "" -> openFolder(dir.path)
                else -> onError(r)
            }
        }
    }

    /** The open file was renamed from the editor: Back focuses it under its new name. */
    fun editorRenamed(path: String) {
        val s = _screen.value as? Screen.Editor ?: return
        _screen.value = s.copy(doc = s.doc.copy(path = path))
    }

    /** Back to the folder, scrolled to and focused on the file. */
    fun closeEditor() {
        val s = _screen.value as? Screen.Editor ?: return
        editorState = null
        _focusTarget.value = s.doc.path
        _screen.value = Screen.Folder(s.folder)
    }

    // ------------------------------------------------------------------ busy indicator

    /** Runs [work]; shows the wait indicator if it takes more than ~300 ms. */
    fun setBusy(label: String, onStop: () -> Unit, work: suspend () -> Unit) {
        busyJob?.cancel()
        val busy = Busy(label, SystemClock.uptimeMillis(), onStop, id = ++busyIds)
        _busy.value = busy
        busyJob = viewModelScope.launch {
            try {
                work()
            } finally {
                if (_busy.value?.id == busy.id) _busy.value = null
            }
        }
    }

    private var busyIds = 0L

    private fun updateBusy(id: Long, label: String, progress: Float?) {
        val b = _busy.value ?: return
        if (b.id == id) _busy.value = b.copy(label = label, progress = progress)
    }

    /** Stop: the UI returns to its previous state immediately (README §2.1). */
    fun stopBusy() {
        val b = _busy.value ?: return
        _busy.value = null
        busyJob?.cancel()
        b.onStop()
    }

    /** The operation finished on its own: hide the indicator without running Stop. */
    fun stopBusyQuietly() {
        _busy.value = null
        busyJob?.cancel()
    }

    // ------------------------------------------------------------------ folder sort

    /** The open folder's own order, kept between runs; the "folders first" setting stays global. */
    fun setFolderSort(by: SortBy, descending: Boolean) {
        val l = _listing.value ?: return
        settingsStore.setFolderSort(l.path, FolderSort(by, descending))
    }

    /** Back to the default order: by name without images, otherwise the sort from settings. */
    fun resetFolderSort() {
        val l = _listing.value ?: return
        settingsStore.setFolderSort(l.path, null)
    }

    // ------------------------------------------------------------------ recent folders

    private fun touchRecent(path: String) {
        settingsStore.updateRecent { list ->
            val old = list.firstOrNull { it.path == path }
            listOf((old ?: RecentFolder(path, 0)).copy(lastOpened = System.currentTimeMillis())) + list.filter { it.path != path }
        }
    }

    fun recentFor(path: String): RecentFolder? = settingsStore.recent.value.firstOrNull { it.path == path }

    fun saveAnchor(path: String, key: String?, offset: Int) {
        settingsStore.updateRecent { list ->
            list.map { if (it.path == path) it.copy(anchorKey = key, anchorOffset = offset) else it }
        }
    }

    fun removeRecent(path: String) {
        settingsStore.updateRecent { list -> list.filter { it.path != path } }
    }

    fun toggleFavourite(path: String) {
        settingsStore.update { s ->
            s.copy(favourites = if (path in s.favourites) s.favourites - path else s.favourites + path)
        }
    }

    // ------------------------------------------------------------------ delete with undo

    /**
     * Deletes without a confirmation dialog; an "Undo" snackbar is shown for 5 s and the files are
     * only removed for good when it closes (README §6, item 3).
     */
    fun delete(all: List<FileEntry>, onRestored: (List<FileEntry>) -> Unit = {}) {
        val (onDevice, entries) = all.partition { MtpPaths.isMtp(it.path) || mtp.isMirror(it.path) }
        if (onDevice.isNotEmpty()) deleteOnDevice(onDevice, onRestored)
        if (entries.isEmpty()) return
        val listing = _listing.value
        viewModelScope.launch {
            val tickets = ArrayList<Pair<FileEntry, Trash.Ticket>>()
            val failed = ArrayList<FileEntry>()
            withContext(Dispatchers.IO) {
                for (e in entries) {
                    val t = mv.trash.moveToTrash(e.path)
                    if (t != null) tickets.add(e to t) else failed.add(e)
                }
            }
            tickets.forEach { listing?.remove(it.first.path) }
            if (failed.isNotEmpty()) {
                errors.notify(
                    if (failed.size == 1) "Couldn't delete ${failed[0].name}" else "Couldn't delete ${failed.size} files",
                    failed[0].path,
                )
            }
            if (tickets.isEmpty()) return@launch
            val text = if (tickets.size == 1) "Deleted ${tickets[0].first.name}" else "Deleted ${tickets.size} files"
            errors.message(
                UiMessage(
                    text,
                    actionLabel = "Undo",
                    durationMs = 5000,
                    onAction = {
                        viewModelScope.launch {
                            val back = withContext(Dispatchers.IO) { tickets.filter { mv.trash.restore(it.second) }.map { it.first } }
                            back.forEach { listing?.add(it) }
                            onRestored(back)
                        }
                    },
                    onDismiss = {
                        viewModelScope.launch(Dispatchers.IO) { tickets.forEach { mv.trash.purge(it.second) } }
                    },
                ),
            )
        }
    }

    /**
     * Delete on a device (todo §9.8 item 1), with the same 5 s *Undo* as local files: there is no
     * trash on a device, so the files only leave the listing until the snackbar closes, and are
     * deleted then. Files of a device slideshow (mirror copies) delete their device file and leave
     * the parked device folder too. A failed delete is reported and the file comes back.
     */
    private fun deleteOnDevice(entries: List<FileEntry>, onRestored: (List<FileEntry>) -> Unit) {
        val listing = _listing.value
        val back = parked
        class Held(val shown: FileEntry, val remote: String, val parkedEntry: FileEntry?)
        val held = entries.mapNotNull { e ->
            val remote = if (MtpPaths.isMtp(e.path)) e.path else mtp.remoteOf(e.path) ?: return@mapNotNull null
            Held(e, remote, back?.snapshot()?.firstOrNull { it.path == remote })
        }
        if (held.isEmpty()) return
        fun restore(list: List<Held>) = list.forEach { h ->
            listing?.add(h.shown)
            h.parkedEntry?.let { back?.add(it) }
        }
        held.forEach { h ->
            listing?.remove(h.shown.path)
            back?.remove(h.remote)
        }
        val text = if (held.size == 1) "Deleted ${held[0].shown.name}" else "Deleted ${held.size} files"
        errors.message(
            UiMessage(
                text,
                actionLabel = "Undo",
                durationMs = 5000,
                onAction = {
                    restore(held)
                    onRestored(held.map { it.shown })
                },
                onDismiss = {
                    viewModelScope.launch {
                        val failed = withContext(Dispatchers.IO) {
                            held.filter { h ->
                                try {
                                    mtp.delete(h.remote)
                                    false
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    errors.readError(h.remote, "Couldn't delete: ${e.message}", e)
                                    true
                                }
                            }
                        }
                        if (failed.isEmpty()) return@launch
                        restore(failed)
                        errors.notify(
                            if (failed.size == 1) "Couldn't delete ${failed[0].shown.name}" else "Couldn't delete ${failed.size} files",
                            failed[0].remote,
                            log = false,
                        )
                    }
                },
            ),
        )
    }

    // ------------------------------------------------------------------ move / copy

    /**
     * Move or copy to [destDir] (README §4.6) with a progress indicator and Stop. Stop keeps what
     * was already done. Failures are one snackbar, never a dialog.
     */
    fun transfer(entries: List<FileEntry>, destDir: String, move: Boolean) {
        if (entries.isEmpty()) return
        val listing = _listing.value
        if (MtpPaths.isMtp(destDir) || entries.any { MtpPaths.isMtp(it.path) }) {
            // A device on either side (todo §9.5, §9.8 item 2); moved files leave the listing.
            val moved = java.util.Collections.synchronizedList(ArrayList<String>())
            copyWithSummary(destDir, entries.size, move, after = { moved.forEach { listing?.remove(it) } }) { progress ->
                mtp.transfer(entries, destDir, move, progress) { moved.add(it) }
            }
            return
        }
        copyWithSummary(destDir, entries.size, move, after = {
            if (move) entries.forEach { e -> if (!File(e.path).exists()) listing?.remove(e.path) }
        }) { progress -> FileOps.transfer(entries.map { it.path }, destDir, move, progress) }
    }

    /**
     * Files or folders dropped on the folder view (todo §10.5): copied into [destDir] with the same
     * progress, Stop and summary as *Copy to…*. [release] gives up the drag's read permission at the end.
     */
    fun importDropped(uris: List<Uri>, destDir: String, release: () -> Unit) {
        if (uris.isEmpty()) return release()
        copyWithSummary(destDir, uris.size, move = false, quietIfNothing = true, after = release) { progress ->
            DropImport.copyIn(getApplication(), uris, destDir, progress)
        }
    }

    /**
     * `Ctrl` + V in the folder view (todo §10.10): files on the clipboard — `content://` and
     * `file://` URIs, our own `Ctrl` + C included — are copied into [destDir] like a drop.
     */
    fun pasteInto(destDir: String) {
        val cm = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        val clip = cm.primaryClip
        val uris = clip?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
            .filter { it.scheme == "content" || it.scheme == "file" }
        if (uris.isEmpty()) {
            errors.notify("Nothing to paste: copy files first", log = false)
            return
        }
        importDropped(uris, destDir) {}
    }

    /**
     * Runs a move or copy on IO behind the wait indicator with Stop and `n / total: name` progress,
     * then one snackbar: done, or the failures (each also in the error log). [after] runs on the
     * main thread at the end, also after Stop or a crash of [work].
     */
    private fun copyWithSummary(
        destDir: String,
        total: Int,
        move: Boolean,
        quietIfNothing: Boolean = false,
        after: () -> Unit = {},
        work: suspend (onProgress: (index: Int, total: Int, name: String) -> Unit) -> FileOps.Result,
    ) {
        val verb = if (move) "Moving" else "Copying"
        var job: Job? = null
        setBusy("$verb…", onStop = { job?.cancel() }) {
            val id = _busy.value?.id ?: 0L
            job = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val r = work { i, n, name -> viewModelScope.launch { updateBusy(id, "$verb ${i + 1} / $n: $name", i.toFloat() / n) } }
                    withContext(Dispatchers.Main) {
                        val name = destDir.substringAfterLast('/')
                        when {
                            r.failed.isEmpty() -> if (r.done > 0 || !quietIfNothing) errors.notify("${if (move) "Moved" else "Copied"} ${r.done} to $name", log = false)
                            else -> {
                                r.failed.forEach { (p, why) -> errors.readError(p, why) }
                                errors.notify("${r.failed.size} of $total couldn't be ${if (move) "moved" else "copied"}: ${r.failed[0].second}", r.failed[0].first)
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) { after() }
                }
            }
            job?.join()
        }
    }

    // ------------------------------------------------------------------ errors

    /** Folder unreadable or removed (README §2.1, "Removed volume"). */
    private fun onListingFailed(path: String, reason: String) {
        if (MtpPaths.isMtp(path)) {
            if (mtp.isConnected(path)) errors.readError(path, "Can't read folder: $reason") else errors.notify("Device not responding — unplug it and plug it in again", path)
            return
        }
        viewModelScope.launch {
            val gone = withContext(Dispatchers.IO) { abandonable(1000) { !File(path).isDirectory } ?: true }
            if (!gone) {
                errors.readError(path, "Can't read folder: $reason")
                return@launch
            }
            errors.notify("Storage removed", path)
            refreshVolumes()
            var p: File? = File(path).parentFile
            while (p != null && abandonable(500) { p!!.isDirectory } != true) p = p.parentFile
            val vols = withContext(Dispatchers.IO) { Volumes.list(getApplication()) }
            if (p == null || Volumes.rootOf(p.path, vols) == null) goRoot() else openFolder(p.path, showBusy = false)
        }
    }

    // ------------------------------------------------------------------ MTP devices (todo §9)

    /**
     * Media on a device are shown from a local mirror (todo §9.6): the tapped file is downloaded
     * with the wait indicator, then the slideshow runs over the mirror folder while the folder's
     * other media follow in its order. The mirror listing watches its folder, so each file joins
     * the slideshow as soon as it is complete. The device folder stays open behind it.
     */
    private fun openDeviceSlideshow(l: FolderListing, entry: FileEntry) {
        var job: Job? = null
        setBusy("Loading ${entry.name}…", onStop = { job?.cancel() }) {
            val id = _busy.value?.id ?: 0L
            job = viewModelScope.launch {
                val local = try {
                    withContext(Dispatchers.IO) {
                        val dir = mtp.mirror.of(l.path) ?: return@withContext null
                        mtp.mirror.makeRoomFor(dir)
                        mtp.mirror.fetch(entry) { done, total ->
                            if (total > 0) viewModelScope.launch { updateBusy(id, "Loading ${entry.name}…", done.toFloat() / total) }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors.readError(entry.path, e.message ?: "read error", e)
                    errors.notify("Can't load ${entry.name}: ${e.message}", entry.path, log = false)
                    return@launch
                }
                if (local == null) {
                    errors.notify("Device disconnected", entry.path, log = false)
                    return@launch
                }
                val mirror = local.parentFile?.path ?: return@launch
                val spec = _folderSort.value
                val ml = FolderListing(mirror, viewModelScope, Sorting.comparator(spec), showHidden = true) { _, _ -> }
                ml.start()
                parked = l
                applied = ml to spec
                _listing.value = ml
                _screen.value = Screen.Slideshow(mirror, local.path)
                prefetch(l, entry)
            }
            job?.join()
        }
    }

    /** The folder's other media, from the one after [start] round to the one before it, within the mirror budget. */
    private fun prefetch(l: FolderListing, start: FileEntry) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            l.status.first { it != FolderListing.Status.LOADING }
            mtp.mirror.prefetch(l.snapshot().filter { it.isMedia }, start) { e, ex ->
                errors.readError(e.path, ex.message ?: "read error", ex)
                mtp.isConnected(e.path)
            }
        }
    }

    private fun closeDeviceSlideshow(lastViewed: String?) {
        prefetchJob?.cancel()
        val back = parked
        parked = null
        _listing.value?.close()
        if (back == null) {
            goRoot()
            return
        }
        _listing.value = back
        _focusTarget.value = lastViewed?.let(mtp::remoteOf)
        _screen.value = Screen.Folder(back.path)
    }

    /**
     * Android opened the app for a camera just plugged in (todo §9.8 item 6), which also granted
     * access. Once the device is ready, a single storage opens in the folder view; several, or a
     * device that can't be opened, show the home screen with its *Devices* section. A slideshow or
     * the editor is left alone.
     */
    fun deviceAttached(id: Int) {
        mtp.refresh()
        viewModelScope.launch {
            _ready.first { it }
            val d = withTimeoutOrNull(DEVICE_OPEN_WAIT_MS) {
                devices.first { list -> list.any { it.id == id && (it.state == MtpDevices.State.READY || it.state == MtpDevices.State.FAILED) } }
            }?.firstOrNull { it.id == id }
            val s = _screen.value
            if (s !is Screen.Root && s !is Screen.Folder) return@launch
            val storage = d?.storages?.singleOrNull()
            if (d?.state == MtpDevices.State.READY && storage != null) openFolder(storage.path) else goRoot()
        }
    }

    /**
     * Files other than media on a device open with other apps (README §3.11) from a local copy.
     */
    fun openDeviceFileWith(entry: FileEntry, open: suspend (FileEntry) -> Boolean) {
        var job: Job? = null
        setBusy("Loading ${entry.name}…", onStop = { job?.cancel() }) {
            job = viewModelScope.launch {
                val local = try {
                    withContext(Dispatchers.IO) { mtp.mirror.fetch(entry) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors.notify("Can't load ${entry.name}: ${e.message}", entry.path)
                    return@launch
                }
                if (local == null) return@launch errors.notify("Device disconnected", entry.path, log = false)
                if (!open(entry.copy(path = local.path))) errors.notify("No app can open ${entry.name}", entry.path, log = false)
            }
            job?.join()
        }
    }

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(mountReceiver)
        _listing.value?.close()
        parked?.close()
        prefetchJob?.cancel()
    }

    companion object {
        const val AVAILABILITY_TIMEOUT_MS = 400L

        /** How long a plugged-in device may take to open before the home screen shows it instead. */
        private const val DEVICE_OPEN_WAIT_MS = 15_000L

        /** Reading or writing a text file of up to 10 MB; a hung USB drive is given up after this. */
        const val TEXT_IO_TIMEOUT_MS = 20_000L

        /** Folders with more videos aren't checked for lectures (each needs its duration). */
        private const val MAX_LECTURE_VIDEOS = 300
    }
}
