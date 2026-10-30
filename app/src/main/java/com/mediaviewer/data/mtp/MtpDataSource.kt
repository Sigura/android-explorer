package com.mediaviewer.data.mtp

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * A Media3 [DataSource] over an MTP device file (todo §11.4): `mtp://` URIs play straight from
 * the camera or phone through [MtpStream], without a copy in the mirror. Each `open` (the start,
 * and every seek the player makes) is a new partial read at its position. Reads block the
 * player's loading thread, which is what that thread is for; an interrupted load ends the wait.
 */
@OptIn(UnstableApi::class)
class MtpDataSource(private val connection: (String) -> MtpConnection?) : BaseDataSource(false) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stream: MtpStream? = null
    private var uri: Uri? = null
    private var left = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = dataSpec.uri.toString()
        val c = connection(path) ?: throw IOException("Device disconnected")
        val s = runBlocking { c.stream(path, scope) } ?: throw IOException("the device can't stream ${path.substringAfterLast('/')}")
        if (dataSpec.position > s.length) {
            s.close()
            throw IOException("position ${dataSpec.position} past the end (${s.length})")
        }
        s.seek(dataSpec.position)
        stream = s
        val rest = s.length - dataSpec.position
        left = if (dataSpec.length == C.LENGTH_UNSET.toLong()) rest else minOf(dataSpec.length, rest)
        opened = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val s = stream ?: throw IOException("not open")
        val n = runBlocking { s.read(buffer, offset, minOf(length.toLong(), left).toInt()) }
        if (n < 0) return C.RESULT_END_OF_INPUT
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        stream?.close()
        stream = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/** Device files (`mtp://`) to [device], everything else to [local], as Media3 would do alone. */
@OptIn(UnstableApi::class)
private class DeviceOrLocalDataSource(private val local: DataSource, private val device: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        device.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (MtpPaths.isMtp(dataSpec.uri.toString())) device else local
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (active ?: throw IOException("not open")).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}

/** Players' media sources (todo §11.4): local files and URLs as before, device files streamed. */
@OptIn(UnstableApi::class)
object MediaSources {
    fun factory(context: Context, connection: (String) -> MtpConnection?): MediaSource.Factory {
        val local = DefaultDataSource.Factory(context)
        return DefaultMediaSourceFactory(DataSource.Factory { DeviceOrLocalDataSource(local.createDataSource(), MtpDataSource(connection)) })
    }
}
