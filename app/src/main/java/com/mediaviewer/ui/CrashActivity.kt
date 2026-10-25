package com.mediaviewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mediaviewer.MainActivity
import com.mediaviewer.data.CrashHandler
import kotlin.system.exitProcess

/**
 * Crash screen (README §3.5): runs in its own `:crash` process, so it works even when the app
 * itself can't start. Shows the report and offers to reset settings and clear caches.
 */
class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val report = CrashHandler.takeReport(this) ?: "No crash details were saved."
        // The first line after the header block is the exception itself.
        val summary = report.substringAfter("\n\n", report).lineSequence().firstOrNull().orEmpty()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    CrashScreen(summary, report)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun CrashScreen(summary: String, report: String) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Media Viewer stopped", style = MaterialTheme.typography.headlineSmall)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Text(
                "If it keeps happening, reset the settings and clear the cache. Settings, favourites and " +
                    "recent folders go back to defaults; your files are not touched.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    CrashHandler.resetSettings(this@CrashActivity)
                    CrashHandler.clearCache(this@CrashActivity)
                    restart()
                }) { Text("Reset settings and clear cache") }
                OutlinedButton(onClick = {
                    CrashHandler.clearCache(this@CrashActivity)
                    restart()
                }) { Text("Clear cache only") }
                OutlinedButton(onClick = ::restart) { Text("Restart") }
                TextButton(onClick = { copy(report) }) { Text("Copy details") }
                TextButton(onClick = ::quit) { Text("Close") }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Text(
                    report,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }

    private fun copy(report: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Media Viewer crash", report))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun restart() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        quit()
    }

    private fun quit() {
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
