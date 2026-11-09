package com.tuo.navi.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Sfondo della dashboard: di base lo scenario dark fantasy incluso nell'app,
 * oppure una foto scelta dall'utente (si può cambiare quante volte si vuole). Resta sul telefono.
 */
object BackdropStore {
    private lateinit var file: File
    private val _version = MutableStateFlow(0); val version: StateFlow<Int> = _version
    fun init(ctx: Context) { file = File(ctx.filesDir, "backdrop.jpg") }
    fun isCustom() = ::file.isInitialized && file.exists()

    fun load(ctx: Context, maxH: Int = 1800): Bitmap? = runCatching {
        if (isCustom()) decode(maxH) { o -> BitmapFactory.decodeFile(file.path, o) }
        else decode(maxH) { o -> BitmapFactory.decodeResource(ctx.resources, com.tuo.navi.R.drawable.bg_dashboard, o) }
    }.getOrNull()

    private inline fun decode(maxH: Int, f: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }; f(b)
        var s = 1; while (b.outHeight / (s * 2) >= maxH) s *= 2
        return f(BitmapFactory.Options().apply { inSampleSize = s })
    }

    fun set(ctx: Context, uri: Uri) {
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: error("Immagine non leggibile")
        val scale = minOf(1f, 1800f / src.height)
        val bm = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        file.outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        _version.value++
    }

    fun reset() { if (isCustom()) file.delete(); _version.value++ }
}
