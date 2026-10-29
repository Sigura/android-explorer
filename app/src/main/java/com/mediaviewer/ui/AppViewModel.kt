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
import com.mediaviewer.data.history.FolderHistory
import com.mediaviewer.data.mtp.MtpDevices
import com.mediaviewer.data.mtp.MtpPaths
import com.mediaviewer.data.settings.RecentFolder
import com.mediaviewer.data.settings.RestoreMode
import com.mediaviewer.data.settings.SlideshowSession
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

enum class Overlay { SETTINGS, ERROR_LOG }

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
    suspend fun bookKind(entries: List<FileEntry>): BookKind? = withContext(Dispatchers.IO) {
        // Device folders (todo §9): no book playback yet, Play starts the slideshow.
        if (entries.any { MtpPaths.isMtp(it.path) }) return@withContext null
        val longMs = settings.value.longVideoMin * 60_000L
        val videos = entries.filter { it.type == MediaType.VIDEO }
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

    /** The folder's Play button: the book, or the slideshow from the first media item. */
    fun playFolder(path: String, entries: List<FileEntry>, kind: BookKind?) {
        if (kind == null) {
            entries.firstOrNull { it.isMedia }?.let(::openSlideshow)
            return
        }
        viewModelScope.launch {
            val first = bookPlayer.play(path)
            // The folder view focuses the file playing (README §2, "Listening").
            if (first == null) errors.notify("Nothing to play here", path, log = false) else if (_listing.value?.path == path) _focusTarget.value = first
        }
    }

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
    fun delete(entries: List<FileEntry>, onRestored: (List<FileEntry>) -> Unit = {}) {
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

    // ------------------------------------------------------------------ move / copy

    /**
     * Move or copy to [destDir] (README §4.6) with a progress indicator and Stop. Stop keeps what
     * was already done. Failures are one snackbar, never a dialog.
     */
    fun transfer(entries: List<FileEntry>, destDir: String, move: Boolean) {
        if (entries.isEmpty()) return
        if (entries.any { MtpPaths.isMtp(it.path) }) return importFromDevice(entries, destDir)
        val listing = _listing.value
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
                        val dir = mtp.mirrorOf(l.path) ?: return@withContext null
                        if (mtp.mirrorBytes() > MtpDevices.MIRROR_BUDGET) mtp.trimMirror(dir)
                        mtp.fetch(entry) { done, total ->
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
            val media = l.snapshot().filter { it.isMedia }
            val i = media.indexOfFirst { it.path == start.path }
            val queue = if (i < 0) media else media.drop(i + 1) + media.take(i)
            var bytes = mtp.mirrorBytes()
            for (e in queue) {
                if (bytes + e.size > MtpDevices.MIRROR_BUDGET) break
                try {
                    mtp.fetch(e) ?: break
                    bytes += e.size
                } catch (ex: kotlinx.coroutines.CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    errors.readError(e.path, ex.message ?: "read error", ex)
                    if (!mtp.isConnected(e.path)) break
                }
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

    /** "Copy to…" from a device (todo §9.5): files and folders, with progress and Stop. */
    private fun importFromDevice(entries: List<FileEntry>, destDir: String) {
        var job: Job? = null
        setBusy("Copying…", onStop = { job?.cancel() }) {
            val id = _busy.value?.id ?: 0L
            var n = 0
            job = viewModelScope.launch(Dispatchers.IO) {
                val r = mtp.importTo(entries, File(destDir)) { name ->
                    n++
                    viewModelScope.launch { updateBusy(id, "Copying $n: $name", null) }
                }
                withContext(Dispatchers.Main) {
                    val name = destDir.substringAfterLast('/')
                    if (r.failed.isEmpty()) {
                        errors.notify("Copied ${r.done} to $name", log = false)
                    } else {
                        r.failed.forEach { (p, why) -> errors.readError(p, why) }
                        errors.notify("${r.failed.size} of ${entries.size} couldn't be copied: ${r.failed[0].second}", r.failed[0].first)
                    }
                }
            }
            job?.join()
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
                    withContext(Dispatchers.IO) { mtp.fetch(entry) }
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

        /** Reading or writing a text file of up to 10 MB; a hung USB drive is given up after this. */
        const val TEXT_IO_TIMEOUT_MS = 20_000L

        /** Folders with more videos aren't checked for lectures (each needs its duration). */
        private const val MAX_LECTURE_VIDEOS = 300
    }
}
