package com.mediaviewer.playback

import android.content.ComponentName
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mediaviewer.MediaViewerApp
import com.mediaviewer.data.history.BookFolder
import com.mediaviewer.data.history.FileHistory
import com.mediaviewer.data.history.ResumePoint
import com.mediaviewer.data.mtp.MtpPaths
import com.mediaviewer.data.tags.TrackExtras
import com.mediaviewer.data.tags.TrackExtrasLoader
import com.mediaviewer.domain.BookTimes
import com.mediaviewer.domain.Chapters
import com.mediaviewer.domain.PlayerSpeed
import com.mediaviewer.domain.ShuffleOrder
import com.mediaviewer.domain.Skip
import com.mediaviewer.domain.SleepTimer
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/** What the sleep timer waits for (todo §4.2). */
enum class SleepMode { OFF, TIME, END_OF_FILE, END_OF_CHAPTER }

/** What the mini player and the player screen show. */
data class NowPlaying(
    /** The file playing; the folder view marks and focuses its tile. */
    val path: String,
    val folder: String,
    val title: String,
    val index: Int,
    val count: Int,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float = 1f,
    /** Loading or waiting for data (slow USB storage): the play button shows a spinner. */
    val buffering: Boolean = false,
    /** Sleep timer: wall-clock time it pauses at, 0 = off (only for [SleepMode.TIME]). */
    val sleepAtMs: Long = 0,
    /** Sleep timer: pauses when this file ends. */
    val sleepEndOfFile: Boolean = false,
    val sleepMode: SleepMode = SleepMode.OFF,
    /** The sleep timer is fading the sound out: a shake or a tap on the timer adds 5 min. */
    val sleepFading: Boolean = false,
    /** Music mode (todo §3.1): shuffled, shorter skips, no resume rewind, no book bar. */
    val music: Boolean = false,
    /** The skip buttons' length (settings, by mode; todo §4.6). */
    val skipMs: Long = 30_000,
    /** Music mode's repeat: `Player.REPEAT_MODE_OFF` / `ONE` / `ALL`. */
    val repeat: Int = Player.REPEAT_MODE_OFF,
    /** The player screen shows lyrics / subtitle lines (todo §4.6); per folder. */
    val cc: Boolean = true,
)

/**
 * The UI side of [PlaybackService] (todo §3.3): one `MediaController` shared by the folder Play
 * button, the mini player and the player screen. Controller calls run on the main thread (they are
 * cheap: the player lives in this process); building the queue, reading history and tags run on IO.
 */
class BookPlayer(private val app: MediaViewerApp) {
    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private val _times = MutableStateFlow<BookTimes?>(null)
    /** Durations of the queue's files, for the book's total and the segmented bar. */
    val times: StateFlow<BookTimes?> = _times.asStateFlow()
    private var timesJob: Job? = null

    private val _extras = MutableStateFlow<TrackExtras?>(null)
    /** Chapters, lyrics, title tag and cover of the file playing (todo §4.1, §4.4, §4.5); null while read. */
    val extras: StateFlow<TrackExtras?> = _extras.asStateFlow()
    private var extrasJob: Job? = null
    private var extrasFor: String? = null
    /** The last few files' extras, so ⏮ / ⏭ back and forth doesn't read tags again. */
    private val extrasCache = object : LinkedHashMap<String, TrackExtras>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackExtras>?) = size > 4
    }

    /** Music mode of the folder playing (todo §3.1). */
    private var music = false

    /** Lyrics / subtitle lines on the player screen, for the folder playing (todo §4.6). */
    private var cc = true

    private var sleepMode = SleepMode.OFF
    private var sleepAt = 0L
    /** For [SleepMode.END_OF_FILE] / [SleepMode.END_OF_CHAPTER]: the file it was set in. */
    private var sleepPath: String? = null
    private var sleepFading = false
    private var sleepJob: Job? = null

    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private val waiting = ArrayList<(MediaController) -> Unit>()

    /** On app start: show the mini player if the service is still playing from before. */
    fun connectIfRunning() {
        if (PlaybackService.running) connect {}
    }

    /** Starts or continues the book in [folder]. Returns the file it starts with, or null when it has nothing to play. */
    suspend fun play(folder: String): String? {
        val point = withContext(Dispatchers.IO) { BookFolder.resume(folder, app.history, app.legacyCharset()) } ?: return null
        start(point)
        return point.queue.getOrNull(point.index)?.path
    }

    /** Plays [path]'s folder from that file (a tap on an audio file, todo §4.1). False when it can't be played. */
    suspend fun playFrom(path: String): Boolean {
        val point = withContext(Dispatchers.IO) { BookFolder.startAt(path, app.history, app.legacyCharset()) } ?: return false
        start(point)
        return true
    }

    private fun start(point: ResumePoint) {
        val items = PlaybackService.mediaItems(point)
        setMusic(point.music)
        connect { c ->
            c.volume = 1f
            c.setMediaItems(items, point.index, point.positionMs)
            c.setPlaybackSpeed(point.speed)
            c.repeatMode = if (point.music) point.repeat else Player.REPEAT_MODE_OFF
            c.prepare()
            c.play()
        }
    }

    private fun setMusic(on: Boolean) {
        music = on
        PlaybackService.musicMode = on
    }

    /** True when [folder]'s book was heard to the end: Play asks *Start over?* first (todo §3.4). */
    suspend fun isFinished(folder: String): Boolean = withContext(Dispatchers.IO) { BookFolder.isFinished(folder, app.history) }

    fun togglePlay() = controller?.let { if (it.isPlaying) it.pause() else it.play() }

    /** Back by the skip length of the settings and the mode; the service's player does it (the notification skips the same). */
    fun skipBack() = controller?.seekBack()

    /** Forward by the skip length; past the end of a file, the next one. */
    fun skipForward() = controller?.seekForward()

    /** Lyrics / subtitle lines on the player screen on or off; kept per folder. */
    fun setCc(on: Boolean) {
        cc = on
        val folder = _state.value?.folder ?: return
        app.appScope.launch(Dispatchers.IO) { app.history.setCc(folder, on) }
        refresh()
    }

    /** Music mode's repeat: off → all → one → off; kept per folder. */
    fun cycleRepeat() {
        val c = controller ?: return
        if (!music) return
        val next = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        c.repeatMode = next
        val folder = _state.value?.folder ?: return
        app.appScope.launch(Dispatchers.IO) { app.history.setRepeat(folder, next) }
    }

    private fun chapters() = _extras.value?.takeIf { it.path == controller?.currentMediaItem?.mediaId }?.chapters.orEmpty()

    /**
     * ⏮: inside a file with chapters, the start of this chapter (or the previous one within its
     * first 3 s); otherwise the previous file within the first 3 s of this one, else its start.
     */
    fun previous() {
        val c = controller ?: return
        val ch = chapters()
        if (ch.isNotEmpty()) {
            val to = Chapters.previousStart(ch, c.currentPosition)
            if (to != null) c.seekTo(to) else c.seekToPreviousMediaItem()
            return
        }
        c.seekToPrevious()
    }

    /** ⏭: the next chapter, else the next file in the queue. */
    fun next() {
        val c = controller ?: return
        val to = chapters().takeIf { it.isNotEmpty() }?.let { Chapters.nextStart(it, c.currentPosition) }
        if (to != null) c.seekTo(to) else c.seekToNextMediaItem()
    }

    /** The seek bar: a position in the file playing. */
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

    /** The book bar and the queue list: file [index] — from its start, or at [positionMs]. */
    fun playFile(index: Int, positionMs: Long = 0) = controller?.let {
        it.seekTo(index, positionMs)
        it.play()
    }

    /** Saved with the position, so the book resumes at this speed. */
    fun setSpeed(speed: Float) = controller?.setPlaybackSpeed(PlayerSpeed.snap(speed))

    // ------------------------------------------------------------------ shuffle / music mode

    /**
     * Shuffle on: music mode, the queue reordered in place around the file playing (no reload, no
     * gap), a new seed. Off: book mode, back to the folder's playlist / name order. Saved per folder.
     */
    fun setShuffle(on: Boolean) {
        val c = controller ?: return
        val folder = _state.value?.folder ?: return
        val current = c.currentMediaItem?.mediaId ?: return
        app.appScope.launch {
            val seed = if (on) Random.nextLong() else 0L
            val first = if (on) current else null
            val order: List<String> = withContext(Dispatchers.IO) {
                val plain = BookFolder.queue(folder, app.legacyCharset())
                app.history.setShuffle(folder, on, seed, first)
                (if (on) ShuffleOrder.of(plain, seed, first) else plain).map { it.path }
            }
            reorder(c, order)
            if (!on) c.repeatMode = Player.REPEAT_MODE_OFF
            setMusic(on)
            refresh()
        }
    }

    /** Moves the controller's items into [order]; files not in it (gone meanwhile) go to the end. */
    private fun reorder(c: MediaController, order: List<String>) {
        val rank = order.withIndex().associate { it.value to it.index }
        val n = c.mediaItemCount
        val ids = MutableList(n) { c.getMediaItemAt(it).mediaId }
        val target = ids.sortedBy { rank[it] ?: Int.MAX_VALUE }
        if (n > MAX_MOVES) {
            // A huge folder: one replacement is cheaper than hundreds of moves (a short gap).
            val items = target.map { id -> c.getMediaItemAt(ids.indexOf(id)) }
            val at = target.indexOf(c.currentMediaItem?.mediaId)
            c.setMediaItems(items, at.coerceAtLeast(0), c.currentPosition)
            return
        }
        for (i in target.indices) {
            val j = ids.indexOf(target[i])
            if (j != i && j >= 0) {
                c.moveMediaItem(j, i)
                ids.add(i, ids.removeAt(j))
            }
        }
    }

    // ------------------------------------------------------------------ sleep timer

    /** Pauses in [minutes]; replaces a timer already set. */
    fun sleepIn(minutes: Int) {
        cancelSleep()
        sleepMode = SleepMode.TIME
        sleepAt = System.currentTimeMillis() + minutes.coerceIn(1, SleepTimer.MAX_MINUTES) * 60_000L
        startSleepWatch()
    }

    /** Pauses when the file playing ends. */
    fun sleepAtEndOfFile() {
        cancelSleep()
        sleepMode = SleepMode.END_OF_FILE
        sleepPath = controller?.currentMediaItem?.mediaId
        startSleepWatch()
    }

    /** Pauses when the chapter playing ends (the file's end when it has no chapters). */
    fun sleepAtEndOfChapter() {
        cancelSleep()
        sleepMode = SleepMode.END_OF_CHAPTER
        sleepPath = controller?.currentMediaItem?.mediaId
        startSleepWatch()
    }

    /** While fading: 5 more minutes (a shake, or a tap on the timer). */
    fun extendSleep() {
        if (sleepMode == SleepMode.OFF) return
        sleepMode = SleepMode.TIME
        sleepAt = maxOf(sleepAt, System.currentTimeMillis()) + SleepTimer.EXTEND_MS
        setFading(false)
        controller?.volume = 1f
        refresh()
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        if (sleepFading) controller?.volume = 1f
        setFading(false)
        sleepMode = SleepMode.OFF
        sleepAt = 0
        sleepPath = null
        refresh()
    }

    /** Time left until the timer pauses, at the playback speed; null while it can't be known (paused, loading). */
    private fun sleepLeftMs(c: MediaController): Long? = when (sleepMode) {
        SleepMode.OFF -> null
        SleepMode.TIME -> sleepAt - System.currentTimeMillis()
        SleepMode.END_OF_FILE, SleepMode.END_OF_CHAPTER -> {
            val dur = c.duration
            if (dur == C.TIME_UNSET || !c.isPlaying || c.currentMediaItem?.mediaId != sleepPath) {
                null
            } else {
                val pos = c.currentPosition
                val end = if (sleepMode == SleepMode.END_OF_CHAPTER && chapters().isNotEmpty()) Chapters.endAt(chapters(), pos, dur) else dur
                ((end - pos) / c.playbackParameters.speed.coerceAtLeast(0.1f)).toLong()
            }
        }
    }

    /**
     * Watches the timer: far from the end once a second, in the last 10 s four times a second to
     * fade the volume out. At the end it pauses and puts the volume back.
     */
    private fun startSleepWatch() {
        sleepJob?.cancel()
        sleepJob = app.appScope.launch {
            while (isActive && sleepMode != SleepMode.OFF) {
                val c = controller
                val left = c?.let(::sleepLeftMs)
                if (c != null && left != null) {
                    if (left <= 0) {
                        c.pause()
                        c.volume = 1f
                        sleepJob = null
                        cancelSleep()
                        return@launch
                    }
                    val fading = left < SleepTimer.FADE_MS && c.isPlaying
                    if (fading || sleepFading) c.volume = if (fading) SleepTimer.volume(left) else 1f
                    if (fading != sleepFading) {
                        setFading(fading)
                        refresh()
                    }
                }
                delay(if (left != null && left < SleepTimer.FADE_MS + 1_000) 250 else 1_000)
            }
        }
        refresh()
    }

    private val shake = object : SensorEventListener {
        private var lastShake = 0L

        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            if (v.size >= 3 && SleepTimer.isShake(v[0], v[1], v[2]) && event.timestamp - lastShake > 1_000_000_000L) {
                lastShake = event.timestamp
                extendSleep()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** The accelerometer is listened to only while the sound fades (a few seconds), never otherwise. */
    private fun setFading(on: Boolean) {
        if (on == sleepFading) return
        sleepFading = on
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        if (on) {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(shake, it, SensorManager.SENSOR_DELAY_UI) }
        } else {
            sm.unregisterListener(shake)
        }
    }

    // ------------------------------------------------------------------ history for the queue list

    /** Saved positions of the files in [folder]: ✓ heard, ◐ partly heard (todo §4.1). */
    suspend fun fileHistory(folder: String): Map<String, FileHistory> = withContext(Dispatchers.IO) { app.history.files(folder) }

    /** Marks one file heard or not heard. */
    suspend fun setFileFinished(path: String, finished: Boolean) = withContext(Dispatchers.IO) { app.history.setFileFinished(path, finished) }

    /** ⋮ *Start over*: the book's history forgotten, playing from its first file. */
    fun startOver() {
        val folder = _state.value?.folder ?: return
        app.appScope.launch {
            withContext(Dispatchers.IO) { app.history.reset(folder) }
            controller?.let {
                it.seekTo(0, 0)
                it.play()
            }
        }
    }

    /** ⋮ *Mark as finished*: the book ends here; the next Play starts it over. */
    fun markFinished() {
        val folder = _state.value?.folder ?: return
        stop()
        app.appScope.launch(Dispatchers.IO) { app.history.markBookFinished(folder, true) }
    }

    /** Stops playback and hides the mini player; the position is saved by the service. */
    fun stop() {
        controller?.let {
            it.pause()
            it.stop()
            it.clearMediaItems()
        }
        cancelSleep()
        timesJob?.cancel()
        _times.value = null
        extrasJob?.cancel()
        extrasFor = null
        _extras.value = null
        _state.value = null
    }

    /** Position for the mini player's progress line; polled by the UI while it is visible. */
    fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        if (item == null || c.mediaItemCount == 0) {
            _state.value = null
            return
        }
        val folder = BookFolder.parentOf(item.mediaId) ?: ""
        if (_state.value?.folder != folder) loadMusicMode(folder)
        _state.value = NowPlaying(
            path = item.mediaId,
            folder = folder,
            title = item.mediaMetadata.title?.toString() ?: File(item.mediaId).name,
            index = c.currentMediaItemIndex,
            count = c.mediaItemCount,
            playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            speed = c.playbackParameters.speed,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            sleepAtMs = if (sleepMode == SleepMode.TIME) sleepAt else 0,
            sleepEndOfFile = sleepMode == SleepMode.END_OF_FILE,
            sleepMode = sleepMode,
            sleepFading = sleepFading,
            music = music,
            skipMs = app.settings.settings.value.let { Skip.ms(music, it.skipBookSec, it.skipMusicSec) },
            repeat = c.repeatMode,
            cc = cc,
        )
        val t = _times.value
        if (t == null || t.paths.size != c.mediaItemCount || t.paths.getOrNull(c.currentMediaItemIndex) != item.mediaId) loadTimes(c)
        if (extrasFor != item.mediaId) loadExtras(item.mediaId, c.duration.takeIf { it != C.TIME_UNSET } ?: 0)
    }

    /** A new folder playing (or the app restarted with the service still playing): its mode and CC come from history. */
    private fun loadMusicMode(folder: String) {
        app.appScope.launch {
            val h = withContext(Dispatchers.IO) { app.history.folder(folder) }
            val on = h?.shuffle == true
            val showCc = h?.cc ?: true
            if ((on != music || showCc != cc) && _state.value?.folder == folder) {
                setMusic(on)
                cc = showCc
                refresh()
            }
        }
    }

    /** Chapters, lyrics, title tag and cover of [path], on IO; cached for the last few files. */
    private fun loadExtras(path: String, durationMs: Long) {
        extrasFor = path
        extrasJob?.cancel()
        extrasCache[path]?.let {
            _extras.value = it
            return
        }
        _extras.value = null
        extrasJob = app.appScope.launch {
            val e = withContext(Dispatchers.IO) { TrackExtrasLoader.load(path, durationMs, app.legacyCharset()) }
            extrasCache[path] = e
            if (extrasFor == path) _extras.value = e
        }
    }

    /**
     * Reads the duration of every file in the queue, one at a time on IO under the thumbnail
     * watchdog; cached in `meta.db`, so a book read once is instant. Publishes as it goes.
     */
    private fun loadTimes(c: MediaController) {
        val paths = List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId }
        // Reordered (shuffle): the durations already read follow their files.
        val known = _times.value?.let { t -> t.paths.indices.associate { t.paths[it] to t.durationsMs[it] } }.orEmpty()
        val durations = Array(paths.size) { known[paths[it]] }
        timesJob?.cancel()
        _times.value = BookTimes(paths, durations.toList())
        if (durations.all { it != null }) return
        timesJob = app.appScope.launch(Dispatchers.IO) {
            var published = System.currentTimeMillis()
            paths.forEachIndexed { i, path ->
                if (durations[i] != null) return@forEachIndexed
                // Device files (todo §11.4) would need a download: no duration until played.
                if (MtpPaths.isMtp(path)) return@forEachIndexed
                durations[i] = try {
                    val f = File(path)
                    val entry = FileEntry(path, f.name, MediaType.OTHER, f.length(), f.lastModified(), OtherKind.AUDIO)
                    app.thumbnails.videoMeta(entry).durationMs.takeIf { it > 0 }
                } catch (e: Exception) {
                    ensureActive()
                    null
                }
                if (i == paths.lastIndex || System.currentTimeMillis() - published > 500) {
                    _times.value = BookTimes(paths, durations.toList())
                    published = System.currentTimeMillis()
                }
            }
            _times.value = BookTimes(paths, durations.toList())
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The file it was set in ended: pause at the start of the next one (the fade may have been cut short).
            if ((sleepMode == SleepMode.END_OF_FILE || sleepMode == SleepMode.END_OF_CHAPTER) &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            ) {
                controller?.pause()
                cancelSleep()
            } else if (sleepMode == SleepMode.END_OF_FILE || sleepMode == SleepMode.END_OF_CHAPTER) {
                // Moved to another file by hand: the timer follows it.
                sleepPath = mediaItem?.mediaId
            }
        }
    }

    private fun connect(then: (MediaController) -> Unit) {
        controller?.let {
            then(it)
            return
        }
        waiting.add(then)
        if (connecting != null) return
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        connecting = future
        future.addListener({
            connecting = null
            val c = try {
                future.get() // already complete: doesn't block
            } catch (e: Exception) {
                Log.w("MediaViewer", "Can't connect to the player", e)
                waiting.clear()
                return@addListener
            }
            controller = c
            c.addListener(listener)
            val pending = waiting.toList()
            waiting.clear()
            pending.forEach { it(c) }
            refresh()
        }, ContextCompat.getMainExecutor(app))
    }

    private companion object {
        /** Above this many files, shuffle replaces the queue instead of moving items one by one. */
        const val MAX_MOVES = 400
    }
}
