package com.tuo.navi.sprites

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.tuo.navi.data.ProfileStore
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animazioni generate dall'immagine del tuo Navi (Idle caricata oppure avatar creato all'inizio).
 * Servono come segnaposto finché non carichi la riga disegnata: il personaggio resta sempre il tuo,
 * cambia solo il movimento (piegamenti, salti, inclinazioni) più qualche piccolo effetto in pixel art.
 */
object ProceduralAnim {
    private const val C = SpriteSpec.CELL
    private const val FX = C / 2f
    private const val FY = SpriteSpec.BASELINE.toFloat()

    /** Una posa: spostamento, scala attorno ai piedi, rotazione, trasparenza, tinta e piccoli effetti. */
    private data class P(
        val dx: Float = 0f, val dy: Float = 0f, val sx: Float = 1f, val sy: Float = 1f, val rot: Float = 0f,
        val alpha: Int = 255, val tint: Int = 0, val tintK: Float = 0f, val dissolve: Float = 0f,
        val fx: (Canvas, Box, Int) -> Unit = { _, _, _ -> }
    )

    /** Riquadro del personaggio nella cella (per posizionare gli effetti). */
    data class Box(val l: Int, val t: Int, val r: Int, val b: Int) { val h get() = b - t + 1; val cx get() = (l + r) / 2 }

    /** Porta un'immagine qualsiasi (es. l'avatar in pixel) dentro una cella 128×128, alta 96 px, piedi a 112. */
    fun normalize(src: Bitmap): Bitmap {
        val bb = alphaBox(src) ?: return Bitmap.createBitmap(C, C, Bitmap.Config.ARGB_8888)
        val k = SpriteSpec.CHAR_H / bb.h.toFloat()
        val w = ((bb.r - bb.l + 1) * k).roundToInt().coerceAtMost(C); val h = (bb.h * k).roundToInt()
        val out = Bitmap.createBitmap(C, C, Bitmap.Config.ARGB_8888)
        val left = (C - w) / 2; val bottom = SpriteSpec.BASELINE
        Canvas(out).drawBitmap(src, android.graphics.Rect(bb.l, bb.t, bb.r + 1, bb.b + 1),
            android.graphics.Rect(left, bottom - h, left + w, bottom), Paint().apply { isFilterBitmap = false; isAntiAlias = false })
        return out
    }

    fun alphaBox(b: Bitmap): Box? {
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var bt = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 40) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y }
        return if (r < 0) null else Box(l, t, r, bt)
    }

    fun make(key: String, base: Bitmap, frames: Int): List<Bitmap> {
        val box = alphaBox(base) ?: Box(32, 16, 96, 111)
        val n = frames.coerceAtLeast(1)
        return (0 until n).map { i -> render(base, box, pose(key, i, n, box), i) }
    }

    private fun wave(i: Int, n: Int, cycles: Float = 1f) = sin(2 * PI * cycles * i / n).toFloat()

    private fun pose(key: String, i: Int, n: Int, b: Box): P {
        val t = i / n.toFloat()
        return when (key) {
            "idle" -> P(sy = 1f + 0.02f * wave(i, n), dy = if (wave(i, n) > 0.3f) -1f else 0f)
            "walk" -> P(dy = -abs(wave(i, n, 2f)) * 3f, rot = wave(i, n) * 4f, sx = 1f + 0.02f * wave(i, n, 2f))
            "run" -> P(dy = -abs(wave(i, n, 2f)) * 5f, rot = 9f + wave(i, n, 2f) * 3f, dx = 2f)
            "jump" -> listOf(P(sx = 1.1f, sy = 0.86f), P(sy = 1.12f, sx = 0.94f, dy = -8f), P(dy = -16f), P(dy = -10f, sy = 1.05f),
                P(sx = 1.1f, sy = 0.88f), P())[i.coerceAtMost(5)]
            "slide" -> P(rot = 22f, sy = 0.82f, dx = 6f + i * 2f)
            "fly" -> P(dy = -8f + wave(i, n) * 3f, rot = 4f)
            "attack" -> listOf(P(dx = -3f, rot = -4f), P(dx = -4f, rot = -6f), P(dx = 8f, rot = 8f, fx = slash(false)),
                P(dx = 6f, rot = 5f), P(dx = 2f), P())[i.coerceAtMost(5)]
            "sword" -> listOf(P(dx = -2f), P(dx = -4f, rot = -8f, dy = -2f), P(dx = -5f, rot = -12f, dy = -4f), P(dx = 10f, rot = 12f, fx = slash(true)),
                P(dx = 9f, rot = 9f, fx = slash(true)), P(dx = 5f, rot = 4f), P(dx = 2f), P())[i.coerceAtMost(7)]
            "special" -> if (i < n / 2) P(tint = Color.rgb(0, 229, 255), tintK = 0.15f + 0.1f * (i % 2), sy = 0.96f, fx = aura(i))
                else P(dx = 6f + (i - n / 2) * 4f, rot = 10f, tint = Color.WHITE, tintK = if (i == n / 2) 0.5f else 0.1f, fx = if (i == n / 2) slash(true) else noFx)
            "guard" -> P(rot = -4f, dx = -2f + if (i % 2 == 0) 1f else 0f)
            "dodge" -> listOf(P(dx = -6f, dy = -6f, rot = -10f), P(dx = -12f, dy = -8f, rot = -12f), P(dx = -10f, sx = 1.08f, sy = 0.9f), P(dx = -4f))[i.coerceAtMost(3)]
            "hurt" -> P(dx = if (i % 2 == 0) -4f else -2f, rot = -6f, tint = Color.rgb(255, 40, 40), tintK = if (i < 2) 0.45f else 0.15f)
            "knock" -> listOf(P(rot = -20f, dx = -2f), P(rot = -60f, dx = 20f), P(rot = -85f, dx = 36f, sy = 0.95f), P(rot = -85f, dx = 36f, sy = 0.95f),
                P(rot = -40f, dx = 14f), P())[i.coerceAtMost(5)]
            "defeat" -> when {
                i < 3 -> listOf(P(rot = -15f), P(rot = -55f, dx = 18f), P(rot = -85f, dx = 36f))[i]
                else -> P(rot = -85f, dx = 36f, alpha = (255 * (1f - (i - 2) / (n - 2f))).toInt().coerceAtLeast(0), dissolve = (i - 2) / (n - 2f))
            }
            "talk" -> P(dy = if (i % 2 == 0) 0f else -1f, rot = if (i % 4 == 1) 2f else 0f, fx = if (i % 2 == 0) mouthDots(i) else noFx)
            "teleport" -> {
                val k = if (i < n / 2) i / (n / 2f) else 1f - (i - n / 2) / (n / 2f)
                P(sx = 1f - 0.8f * k, sy = 1f + 0.15f * k, alpha = (255 * (1f - 0.6f * k)).toInt(), tint = Color.rgb(0, 229, 255), tintK = 0.6f * k, fx = beam(k))
            }
            "sleep" -> P(sy = 0.84f + 0.015f * wave(i, n), sx = 1.04f, tint = Color.rgb(20, 30, 80), tintK = 0.22f, fx = zzz(i, n))
            "read" -> P(dy = if (i % 3 == 0) -1f else 0f, fx = holo(i))
            "scan" -> P(tint = Color.rgb(0, 229, 255), tintK = 0.08f, rot = if (i % 4 < 2) 1.5f else -1.5f, fx = scanLine(i, n))
            "repair" -> P(sy = 0.86f, rot = 6f, dx = 2f, fx = sparks(i))
            "alert" -> listOf(P(dx = -4f, dy = -5f, rot = -6f, tint = Color.rgb(255, 60, 60), tintK = 0.3f, fx = bang),
                P(dx = -3f, dy = -2f, fx = bang), P(dx = -2f, rot = -3f, fx = bang), P(dx = -2f, rot = -3f, fx = bang))[i.coerceAtMost(3)]
            "cheer" -> listOf(P(sx = 1.08f, sy = 0.9f), P(dy = -10f, sy = 1.08f, fx = stars(0)), P(dy = -14f, fx = stars(1)), P(dy = -8f, fx = stars(2)),
                P(sx = 1.08f, sy = 0.9f, fx = stars(3)), P())[i.coerceAtMost(5)]
            "think" -> P(rot = -3f, dy = if (i % 3 == 2) -1f else 0f, fx = thinkDots(i))
            "surprised" -> listOf(P(), P(dy = -7f, sy = 1.06f, sx = 0.96f, fx = bang), P(dx = -2f, fx = bang), P())[i.coerceAtMost(3)]
            "wave" -> listOf(P(), P(rot = 1f, fx = hand(0)), P(rot = 2f, fx = hand(1)), P(rot = -1f, fx = hand(2)), P(rot = 2f, fx = hand(1)), P(fx = hand(0)))[i.coerceAtMost(5)]
            "pet" -> P(sy = if (i % 2 == 0) 0.94f else 1.04f, sx = if (i % 2 == 0) 1.05f else 0.98f, dy = if (i % 2 == 1) -3f else 0f, fx = hearts(i, n))
            else -> P(dy = if (t < 0.5f) 0f else -1f)
        }
    }

    // ------------------------------------------------------------------ effetti in pixel art (blocchi da 2 px)

    private val noFx: (Canvas, Box, Int) -> Unit = { _, _, _ -> }
    /** Manina che saluta accanto alla testa (0 = bassa, 1 = alta a destra, 2 = alta più in fuori). */
    private fun hand(k: Int): (Canvas, Box, Int) -> Unit = { c, b, _ ->
        val skin = Color.rgb(255, 214, 170); val line = Color.rgb(40, 30, 30)
        val x = (b.r - 4 + k * 3).coerceAtMost(C - 10); val y = (b.t + b.h / 4 - if (k > 0) 8 else 0).coerceAtLeast(2)
        for (yy in 0 until 4) for (xx in 0 until 3) block(c, x + xx * 2, y + yy * 2, skin)
        block(c, x - 2, y, line); block(c, x + 6, y, line); block(c, x, y - 2, line); block(c, x + 4, y - 2, line)
        if (k > 0) { block(c, x + 9, y - 1, Color.argb(200, 255, 255, 255)); block(c, x + 9, y + 4, Color.argb(200, 255, 255, 255)) }
    }
    private val paint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private fun block(c: Canvas, x: Int, y: Int, color: Int, s: Int = 2) {
        paint.color = color; c.drawRect(x.toFloat(), y.toFloat(), (x + s).toFloat(), (y + s).toFloat(), paint)
    }
    private fun glyph(c: Canvas, rows: List<String>, x: Int, y: Int, color: Int, s: Int = 2) =
        rows.forEachIndexed { ry, row -> row.forEachIndexed { rx, ch -> if (ch == '#') block(c, x + rx * s, y + ry * s, color, s) } }

    private val Z = listOf("####", "  # ", " #  ", "####")
    private fun zzz(i: Int, n: Int): (Canvas, Box, Int) -> Unit = { c, b, _ ->
        val col = Color.rgb(190, 220, 255)
        val k = i % n
        glyph(c, Z, (b.r - 6).coerceAtMost(C - 12), (b.t + 6 - k).coerceAtLeast(2), col, 2)
        if (k >= n / 2) glyph(c, Z, (b.r + 2).coerceAtMost(C - 8), (b.t - 4 - k).coerceAtLeast(0), col, 1)
    }
    private fun slash(big: Boolean): (Canvas, Box, Int) -> Unit = { c, b, _ ->
        val col = Color.argb(230, 255, 255, 255)
        val r = if (big) 26 else 18
        for (a in -50..50 step 10) {
            val rad = Math.toRadians(a.toDouble())
            block(c, (b.r + 2 + r * kotlin.math.cos(rad) - r).toInt().coerceIn(0, C - 2), (b.t + b.h / 2 + r * sin(rad)).toInt().coerceIn(0, C - 2), col, 2)
        }
    }
    private fun aura(i: Int): (Canvas, Box, Int) -> Unit = { c, b, _ ->
        val rnd = Random(i * 31 + 7)
        repeat(10) { block(c, b.l - 4 + rnd.nextInt(b.r - b.l + 8).coerceAtLeast(1), b.t + rnd.nextInt(b.h.coerceAtLeast(1)), Color.argb(200, 0, 229, 255)) }
    }
    private fun beam(k: Float): (Canvas, Box, Int) -> Unit = { c, b, _ ->
        if (k > 0.05f) {
            paint.color = Color.argb((140 * k).toInt(), 0, 229, 255)
            c.drawRect((FX - 3).toFloat(), 0f, (FX + 3).toFloat(), FY, paint)
        }
    }
    private fun mouthDots(i: Int): (Canvas, Box, Int) -> Unit = { _, _, _ -> }
    private fun holo(i: Int): (Canvas, Box, Int) -> Unit = { c, b, _ ->
        val x0 = (b.r - 4).coerceAtMost(C - 30); val y0 = b.t + b.h / 4
        paint.color = Color.argb(110, 0, 229, 255); c.drawRect(x0.toFloat(), y0.toFloat(), (x0 + 24).toFloat(), (y0 + 18).toFloat(), paint)
        paint.color = Color.argb(220, 0, 229, 255)
        c.drawRect(x0.toFloat(), y0.toFloat(), (x0 + 24).toFloat(), (y0 + 1).toFloat(), paint)
        for (l in 0 until 3) { val yy = y0 + 4 + ((l * 5 + i * 2) % 14); c.drawRect((x0 + 3).toFloat(), yy.toFloat(), (x0 + 15 + (l * 3) % 6).toFloat(), (yy + 1).toFloat(), paint) }
    }
    private fun scanLine(i: Int, n: Int): (Canvas, Box, Int) -> Unit = { c, b, _ ->
        val y = b.t + (b.h * i / n.toFloat()).toInt()
        paint.color = Color.argb(200, 0, 229, 255)
        c.drawRect((b.l - 6).toFloat().coerceAtLeast(0f), y.toFloat(), (b.r + 6).toFloat().coerceAtMost(C.toFloat()), (y + 2).toFloat(), paint)
        paint.color = Color.argb(60, 0, 229, 255)
        c.drawRect((b.l - 6).toFloat().coerceAtLeast(0f), (y - 6).toFloat(), (b.r + 6).toFloat().coerceAtMost(C.toFloat()), y.toFloat(), paint)
    }
    private fun sparks(i: Int): (Canvas, Box, Int) -> Unit = { c, b, _ ->
        val rnd = Random(i * 13 + 3)
        repeat(5) { block(c, (b.r - 2 + rnd.nextInt(10)).coerceAtMost(C - 2), (FY.toInt() - 10 + rnd.nextInt(10)), if (it % 2 == 0) Color.rgb(255, 214, 90) else Color.WHITE) }
    }
    private val BANG = listOf("##", "##", "##", "##", "  ", "##")
    private val bang: (Canvas, Box, Int) -> Unit = { c, b, _ -> glyph(c, BANG, b.cx - 2, (b.t - 16).coerceAtLeast(0), Color.rgb(255, 60, 60), 2) }
    private val STAR = listOf(" # ", "###", " # ")
    private fun stars(k: Int): (Canvas, Box, Int) -> Unit = { c, b, _ ->
        glyph(c, STAR, (b.l - 6 + k * 3).coerceAtLeast(0), (b.t - 2 - k * 2).coerceAtLeast(0), Color.rgb(255, 214, 90), 2)
        glyph(c, STAR, (b.r - k * 2).coerceAtMost(C - 6), (b.t + 6 - k * 2).coerceAtLeast(0), Color.rgb(255, 214, 90), 2)
    }
    private fun thinkDots(i: Int): (Canvas, Box, Int) -> Unit = { c, b, _ ->
        val k = (i / 2) % 3 + 1
        for (d in 0 until k) block(c, (b.r - 4 + d * 5).coerceAtMost(C - 3), (b.t - 2).coerceAtLeast(0), Color.rgb(230, 235, 245), 3)
    }
    private val HEART = listOf("# #", "###", " # ")
    private fun hearts(i: Int, n: Int): (Canvas, Box, Int) -> Unit = { c, b, _ ->
        glyph(c, HEART, (b.r - 4).coerceAtMost(C - 6), (b.t + 4 - i * 2).coerceAtLeast(0), Color.rgb(255, 110, 170), 2)
        if (i >= n / 2) glyph(c, HEART, (b.l - 2).coerceAtLeast(0), (b.t + 8 - i).coerceAtLeast(0), Color.rgb(255, 110, 170), 2)
    }

    // ------------------------------------------------------------------ disegno di un frame

    private fun render(base: Bitmap, box: Box, p: P, seed: Int): Bitmap {
        val out = Bitmap.createBitmap(C, C, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val m = Matrix().apply {
            postScale(p.sx, p.sy, FX, FY); postRotate(p.rot, FX, FY); postTranslate(p.dx.roundToInt().toFloat(), p.dy.roundToInt().toFloat())
        }
        val pt = Paint().apply {
            isAntiAlias = false; isFilterBitmap = false; alpha = p.alpha.coerceIn(0, 255)
            if (p.tintK > 0f) {
                val k = p.tintK.coerceIn(0f, 1f); val r = Color.red(p.tint) * k; val g = Color.green(p.tint) * k; val bl = Color.blue(p.tint) * k
                colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    1 - k, 0f, 0f, 0f, r, 0f, 1 - k, 0f, 0f, g, 0f, 0f, 1 - k, 0f, bl, 0f, 0f, 0f, 1f, 0f)))
            }
        }
        cv.drawBitmap(base, m, pt)
        if (p.dissolve > 0f) {
            val px = IntArray(C * C); out.getPixels(px, 0, C, 0, 0, C, C)
            val rnd = Random(seed * 97 + 11)
            for (k in px.indices) if (px[k] != 0 && rnd.nextFloat() < p.dissolve) px[k] = 0
            out.setPixels(px, 0, C, 0, 0, C, C)
        }
        p.fx(cv, box, seed)
        return out
    }
}

/**
 * Da dove arrivano i frame di un'animazione:
 *  1. la riga caricata da te;
 *  2. con l'avatar provvisorio, il Navi predefinito disegnato dall'app;
 *  3. altrimenti animazione generata dalla tua immagine (prima frame della Idle caricata, o l'avatar creato).
 */
object SpriteFrames {
    private val cache = HashMap<String, List<Bitmap>>()
    private var cacheVer = -1
    private var normSrc: Bitmap? = null
    private var normOut: Bitmap? = null
    private val placeholderNative = setOf("idle", "walk", "run", "jump", "slide", "fly", "talk", "attack", "sword", "special", "teleport", "hurt")

    @Synchronized
    fun frames(key: String, avatar: Bitmap?): List<Bitmap> {
        SpriteStore.frames(key)?.let { return it }
        val ver = SpriteStore.version.value
        if (ver != cacheVer) { cache.clear(); cacheVer = ver }
        val provisional = ProfileStore.profile.value?.provisional != false
        if (provisional && key in placeholderNative) return PlaceholderSprites.frames(key)
        val base = SpriteStore.frames("idle")?.firstOrNull()
            ?: if (!provisional && avatar != null) normalized(avatar) else PlaceholderSprites.portrait()
        val ck = "$key|${System.identityHashCode(base)}"
        return cache.getOrPut(ck) { ProceduralAnim.make(key, base, SpriteSpec.byKey(key)?.frames ?: 6) }
    }

    private fun normalized(a: Bitmap): Bitmap {
        if (normSrc !== a || normOut == null) { normSrc = a; normOut = ProceduralAnim.normalize(a) }
        return normOut!!
    }
}
