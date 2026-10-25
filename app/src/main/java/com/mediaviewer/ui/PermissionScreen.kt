package com.mediaviewer.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat

/** The app's own page in system settings (App info), the last fallback for granting storage access. */
private fun openAppDetails(ctx: Context): Boolean = try {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
    true
} catch (e: ActivityNotFoundException) {
    false
}

/** First-launch explanation of "All files access" (README §1, Storage access). */
@Composable
fun PermissionScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var unavailable by remember { mutableStateOf(false) }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.onPermissionMaybeChanged()
        // Denied with "Don't ask again": Android shows no dialog any more, only App info can grant it.
        val read = android.Manifest.permission.READ_EXTERNAL_STORAGE
        val activity = ctx as? Activity
        if (result[read] != true && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, read)) {
            openAppDetails(ctx)
        }
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Access to your files", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Media Viewer is a file browser: it shows your real folders, including SD cards and USB drives, " +
                "and can delete files. Android calls this \"All files access\".",
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 30) {
                val app = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
                val all = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                try {
                    ctx.startActivity(app)
                } catch (e: ActivityNotFoundException) {
                    try {
                        ctx.startActivity(all)
                    } catch (e2: ActivityNotFoundException) {
                        // Some TV boxes have no such screen (README §1): try App info, and show the adb hint
                        // too, because App info often lacks the switch on those devices.
                        openAppDetails(ctx)
                        unavailable = true
                    }
                }
            } else {
                legacy.launch(
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                )
            }
        }) { Text("Allow access") }
        if (unavailable) {
            Text(
                "This device has no \"All files access\" screen. Look for it under App info → Permissions, or grant it with:\n" +
                    "adb shell appops set --uid ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
