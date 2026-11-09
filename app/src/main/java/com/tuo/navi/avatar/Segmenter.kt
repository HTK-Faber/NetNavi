package com.tuo.navi.avatar

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

object Segmenter {
    private val client by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
    }

    /** Ritaglia il soggetto (sfondo trasparente). Riduce prima l'immagine per i telefoni con poca RAM. */
    suspend fun cutout(src: Bitmap): Bitmap {
        val maxSide = 1024
        val s = if (maxOf(src.width, src.height) > maxSide) {
            val k = maxSide.toFloat() / maxOf(src.width, src.height)
            Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true)
        } else src
        val r = client.process(InputImage.fromBitmap(s, 0)).await()
        return r.foregroundBitmap ?: throw IllegalStateException("Nessun soggetto riconosciuto nell'immagine")
    }

    /** Ritaglia al riquadro dei pixel opachi. */
    fun trim(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var bo = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 40) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bo) bo = y
        }
        if (r < l || bo < t) return b
        return Bitmap.createBitmap(b, l, t, r - l + 1, bo - t + 1)
    }

    /** Mezzo busto: parte alta del ritaglio (circa 48%). */
    fun bust(trimmed: Bitmap): Bitmap {
        val h = (trimmed.height * 0.48f).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(trimmed, 0, 0, trimmed.width, h)
    }
}
