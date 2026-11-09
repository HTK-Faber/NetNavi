package com.tuo.navi.avatar

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.random.Random

object Pixelizer {
    fun pixelate(cut: Bitmap, targetW: Int = 64, colors: Int = 16): Bitmap {
        val targetH = (cut.height * targetW / cut.width.toFloat()).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(cut, targetW, targetH, true)
        val px = IntArray(targetW * targetH); small.getPixels(px, 0, targetW, 0, 0, targetW, targetH)
        val opaque = px.filter { Color.alpha(it) > 128 }
        if (opaque.isEmpty()) return small
        val rnd = Random(1)
        var pal = List(colors.coerceAtMost(opaque.size)) { opaque[rnd.nextInt(opaque.size)] }
        repeat(8) {
            val sr = LongArray(pal.size); val sg = LongArray(pal.size); val sb = LongArray(pal.size); val n = IntArray(pal.size)
            for (c in opaque) { val i = nearest(c, pal); sr[i] += Color.red(c).toLong(); sg[i] += Color.green(c).toLong(); sb[i] += Color.blue(c).toLong(); n[i]++ }
            pal = pal.indices.map { i -> if (n[i] == 0) pal[i] else Color.rgb((sr[i] / n[i]).toInt(), (sg[i] / n[i]).toInt(), (sb[i] / n[i]).toInt()) }
        }
        val out = IntArray(px.size) { i -> if (Color.alpha(px[i]) <= 128) Color.TRANSPARENT else pal[nearest(px[i], pal)] }
        val res = out.copyOf()
        for (y in 0 until targetH) for (x in 0 until targetW) {
            if (out[y * targetW + x] != Color.TRANSPARENT) continue
            val near = arrayOf(intArrayOf(x - 1, y), intArrayOf(x + 1, y), intArrayOf(x, y - 1), intArrayOf(x, y + 1))
            if (near.any { (nx, ny) -> nx in 0 until targetW && ny in 0 until targetH && out[ny * targetW + nx] != Color.TRANSPARENT })
                res[y * targetW + x] = Color.rgb(10, 10, 20)
        }
        return Bitmap.createBitmap(res, targetW, targetH, Bitmap.Config.ARGB_8888)
    }

    private fun nearest(c: Int, pal: List<Int>): Int {
        var best = 0; var bd = Int.MAX_VALUE
        for (i in pal.indices) {
            val p = pal[i]
            val dr = Color.red(c) - Color.red(p); val dg = Color.green(c) - Color.green(p); val db = Color.blue(c) - Color.blue(p)
            val d = dr * dr + dg * dg + db * db
            if (d < bd) { bd = d; best = i }
        }
        return best
    }
}
