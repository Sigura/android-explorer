package com.mediaviewer

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mediaviewer.data.CrashHandler
import com.mediaviewer.ui.AppViewModel
import com.mediaviewer.ui.CrashActivity
import com.mediaviewer.ui.MouseBack
import com.mediaviewer.ui.Overlay
import com.mediaviewer.ui.PermissionScreen
import com.mediaviewer.ui.Screen
import com.mediaviewer.ui.browser.FolderScreen
import com.mediaviewer.ui.browser.LocalThumbnails
import com.mediaviewer.ui.common.WaitIndicator
import com.mediaviewer.ui.editor.EditorScreen
import com.mediaviewer.ui.playback.FULL_PLAYER_HEIGHT
import com.mediaviewer.ui.playback.LocalMiniPlayerHeight
import com.mediaviewer.ui.playback.MINI_PLAYER_HEIGHT
import com.mediaviewer.ui.playback.MiniPlayer
import com.mediaviewer.ui.playback.PlayerScreen
import com.mediaviewer.ui.root.RootScreen
import com.mediaviewer.ui.settings.ErrorLogScreen
import com.mediaviewer.ui.settings.SettingsScreen
import com.mediaviewer.ui.slideshow.SlideshowScreen
import com.mediaviewer.ui.theme.MediaViewerTheme
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // A crash whose screen couldn't open (the app was in the background): show it now.
        if (CrashHandler.hasPending(this)) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        // README §7.1: settings are read before the first frame, so defaults never flash.
        splash.setKeepOnScreenCondition { !vm.ready.value }
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsState()
            MediaViewerTheme(settings.theme) {
                CompositionLocalProvider(LocalThumbnails provides vm.thumbnails) {
                    AppRoot(vm, onExit = ::finish)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onPermissionMaybeChanged()
        vm.refreshExternalApps()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.isFromSource(InputDevice.SOURCE_MOUSE) &&
            ev.buttonState and MotionEvent.BUTTON_SECONDARY != 0
        ) {
            MouseBack.lastSecondaryPress = SystemClock.uptimeMillis()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && ev.actionButton == MotionEvent.BUTTON_SECONDARY) {
            MouseBack.lastSecondaryPress = SystemClock.uptimeMillis()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    /** The mouse back button is "previous item" in the slideshow (README §5.2). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            // DeX: an unconsumed right-click arrives as Back (README §3.9).
            if (SystemClock.uptimeMillis() - MouseBack.lastSecondaryPress < MouseBack.RIGHT_CLICK_BACK_MS) return true
            val h = MouseBack.handler
            if (h != null) {
                if (event.action == KeyEvent.ACTION_UP) h()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun AppRoot(vm: AppViewModel, onExit: () -> Unit) {
    val screen by vm.screen.collectAsState()
    val overlay by vm.overlay.collectAsState()
    val listing by vm.listing.collectAsState()
    val busy by vm.busy.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lastBack = remember { longArrayOf(0L) }

    // README §4 "Root": Back at the root exits, with "press again to exit".
    BackHandler {
        if (!vm.back()) {
            val now = SystemClock.uptimeMillis()
            if (now - lastBack[0] < 2000) {
                onExit()
            } else {
                lastBack[0] = now
                Toast.makeText(ctx, "Press again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // README §2.1: auto-dismissing, grouped, never steals focus.
    LaunchedEffect(Unit) {
        vm.errors.messages.collect { m ->
            val result = withTimeoutOrNull(m.durationMs) {
                snackbar.showSnackbar(m.text, actionLabel = m.actionLabel, duration = SnackbarDuration.Indefinite)
            }
            if (result == null) snackbar.currentSnackbarData?.dismiss()
            when {
                result == SnackbarResult.ActionPerformed && m.opensErrorLog -> vm.showOverlay(Overlay.ERROR_LOG)
                result == SnackbarResult.ActionPerformed -> m.onAction?.invoke()
                else -> m.onDismiss?.invoke()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                // README §2.1: Esc stops a slow operation.
                if (busy != null && ev.key == Key.Escape && ev.type == KeyEventType.KeyDown) {
                    vm.stopBusy()
                    true
                } else {
                    false
                }
            },
    ) {
        // The mini player (todo §4.3) sits over the root and folder screens, never over the slideshow.
        val nowPlaying by vm.bookPlayer.state.collectAsState()
        val showMini = nowPlaying != null && (screen == Screen.Root || screen is Screen.Folder)
        val playerCompact = vm.settings.collectAsState().value.playerCompact
        val playerHeight = if (playerCompact) MINI_PLAYER_HEIGHT else FULL_PLAYER_HEIGHT
        CompositionLocalProvider(LocalMiniPlayerHeight provides if (showMini) playerHeight else 0.dp) {
            when (val s = screen) {
                Screen.Loading -> Unit
                Screen.Permission -> PermissionScreen(vm)
                Screen.Root -> RootScreen(vm)
                is Screen.Folder -> listing?.let { l -> key(l) { FolderScreen(vm, l) } }
                is Screen.Slideshow -> listing?.let { l ->
                    key(s.folder, s.start) { SlideshowScreen(vm, l, s.start, s.resume) }
                }
                is Screen.Editor -> key(s.doc.path) { EditorScreen(vm, s) }
            }
        }
        val np = nowPlaying
        if (showMini && np != null && overlay == null) {
            MiniPlayer(
                np, vm.bookPlayer, playerCompact,
                onCompact = { c -> vm.settingsStore.update { it.copy(playerCompact = c) } },
                onOpenPlayer = { vm.showOverlay(Overlay.PLAYER) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        when (overlay) {
            Overlay.SETTINGS -> SettingsScreen(vm)
            Overlay.ERROR_LOG -> ErrorLogScreen(vm)
            Overlay.PLAYER -> PlayerScreen(vm)
            null -> Unit
        }
        val startOver by vm.startOver.collectAsState()
        startOver?.let { so ->
            AlertDialog(
                onDismissRequest = { vm.answerStartOver(false) },
                title = { Text("Start over?") },
                text = { Text("You heard ${java.io.File(so.folder).name} to the end.") },
                confirmButton = { TextButton(onClick = { vm.answerStartOver(true) }) { Text("Start over") } },
                dismissButton = { TextButton(onClick = { vm.answerStartOver(false) }) { Text("Cancel") } },
            )
        }
        WaitIndicator(busy, onStop = vm::stopBusy)
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars),
        )
    }
}
