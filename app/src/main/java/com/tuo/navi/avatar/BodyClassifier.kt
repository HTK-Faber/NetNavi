package com.tuo.navi.avatar

import android.graphics.Bitmap
import com.tuo.navi.domain.BodyBuild
import com.tuo.navi.domain.NaviClass
import com.tuo.navi.domain.Stature

data class BodyProfile(val build: BodyBuild, val stature: Stature, val cls: NaviClass, val ratio: Float)

object BodyClassifier {
    const val STOCKY_THRESHOLD = 0.30f   // da tarare con foto reali
    const val TALL_CM = 172

    fun classify(cutout: Bitmap, heightCm: Int): BodyProfile {
        val w = cutout.width; val h = cutout.height
        val px = IntArray(w * h); cutout.getPixels(px, 0, w, 0, 0, w, h)
        var top = h; var bottom = 0
        for (y in 0 until h) for (x in 0 until w)
            if ((px[y * w + x] ushr 24) > 128) { if (y < top) top = y; if (y > bottom) bottom = y }
        val bodyH = (bottom - top).coerceAtLeast(1)
        val y0 = top + (bodyH * 0.25f).toInt(); val y1 = top + (bodyH * 0.60f).toInt()
        var sum = 0f; var rows = 0
        for (y in y0..y1.coerceAtMost(h - 1)) {
            var l = w; var r = 0
            for (x in 0 until w) if ((px[y * w + x] ushr 24) > 128) { if (x < l) l = x; if (x > r) r = x }
            if (r > l) { sum += (r - l); rows++ }
        }
        val ratio = if (rows > 0) (sum / rows) / bodyH else 0f
        val build = if (ratio >= STOCKY_THRESHOLD) BodyBuild.STOCKY else BodyBuild.SLIM
        val stature = if (heightCm >= TALL_CM) Stature.TALL else Stature.SHORT
        val cls = when {
            build == BodyBuild.STOCKY && stature == Stature.TALL -> NaviClass.TANK
            build == BodyBuild.STOCKY -> NaviClass.DEFENDER
            stature == Stature.TALL -> NaviClass.WARRIOR
            else -> NaviClass.MAGE
        }
        return BodyProfile(build, stature, cls, ratio)
    }
}
