package com.tuo.navi.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.security.MessageDigest

/**
 * Lettura locale di foto e video (MediaStore). Nessun file esce dal telefono:
 * l'invio a Drive/Dropbox passa dalla condivisione di Android all'app scelta dall'utente.
 */
object MediaScanner {
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34 &&
            ctx.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED) return true
        return permissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    fun uri(m: MediaInfo): Uri = ContentUris.withAppendedId(
        if (m.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, m.id)

    fun scan(ctx: Context): List<MediaInfo> = query(ctx, false) + query(ctx, true)

    private fun query(ctx: Context, video: Boolean): List<MediaInfo> {
        val col = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DURATION)
        val out = mutableListOf<MediaInfo>()
        runCatching {
            ctx.contentResolver.query(col, proj, null, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(proj[0]); val iName = c.getColumnIndexOrThrow(proj[1]); val iSize = c.getColumnIndexOrThrow(proj[2])
                val iTaken = c.getColumnIndexOrThrow(proj[3]); val iAdded = c.getColumnIndexOrThrow(proj[4]); val iBucket = c.getColumnIndexOrThrow(proj[5])
                val iW = c.getColumnIndexOrThrow(proj[6]); val iH = c.getColumnIndexOrThrow(proj[7]); val iD = c.getColumnIndexOrThrow(proj[8])
                while (c.moveToNext()) {
                    val taken = c.getLong(iTaken).takeIf { it > 0 } ?: (c.getLong(iAdded) * 1000L)
                    out += MediaInfo(c.getLong(iId), c.getString(iName) ?: "?", c.getLong(iSize), taken,
                        c.getString(iBucket) ?: "Altro", video, c.getInt(iW), c.getInt(iH), if (video) c.getLong(iD) else 0)
                }
            }
        }
        return out
    }

    /** Impronta del contenuto: dimensione + primi e ultimi 256 KB. Basta per riconoscere copie identiche senza leggere tutto il file. */
    fun fingerprint(ctx: Context, m: MediaInfo): String? = runCatching {
        val md = MessageDigest.getInstance("MD5"); val chunk = 256 * 1024
        md.update(m.size.toString().toByteArray())
        ctx.contentResolver.openFileDescriptor(uri(m), "r")?.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val buf = java.nio.ByteBuffer.allocate(chunk)
                ch.position(0); ch.read(buf); buf.flip(); md.update(buf)
                if (m.size > chunk * 2) { buf.clear(); ch.position(m.size - chunk); ch.read(buf); buf.flip(); md.update(buf) }
            }
        } ?: return null
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun thumbnail(ctx: Context, m: MediaInfo, px: Int = 160): Bitmap? =
        runCatching { ctx.contentResolver.loadThumbnail(uri(m), Size(px, px), null) }.getOrNull()

    /** Condivisione verso Drive, Dropbox, OneDrive...: l'utente sceglie l'app; NetNavi non usa Internet. */
    fun shareIntent(items: List<MediaInfo>): Intent {
        val uris = ArrayList(items.map { uri(it) })
        val type = when { items.all { it.isVideo } -> "video/*"; items.none { it.isVideo } -> "image/*"; else -> "*/*" }
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = type; send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Invia a Drive, Dropbox o un'altra app")
    }

    /** Eliminazione: Android mostra sempre la sua finestra di conferma. */
    fun deleteRequest(ctx: Context, items: List<MediaInfo>): IntentSender =
        MediaStore.createDeleteRequest(ctx.contentResolver, items.map { uri(it) }).intentSender
}
