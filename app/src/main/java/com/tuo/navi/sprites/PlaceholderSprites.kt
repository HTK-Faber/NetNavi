package com.tuo.navi.sprites

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Navi provvisorio disegnato via codice (griglia 32×32, ingrandita ×4 = cella 128×128).
 * Serve finché non carichi la tua pixel art: ha già idle, camminata, corsa, salto, volo, parlato, attacco e teletrasporto.
 */
object PlaceholderSprites {
    private const val G = 32
    private val T = Color.TRANSPARENT
    private val OUT = Color.rgb(10, 10, 24)
    private val RED = Color.rgb(208, 32, 42)
    private val RED_D = Color.rgb(130, 16, 28)
    private val CYAN = Color.rgb(0, 229, 255)
    private val SKIN = Color.rgb(246, 200, 160)
    private val EYE = Color.rgb(20, 60, 140)
    private val COAT = Color.rgb(30, 30, 46)
    private val COAT_L = Color.rgb(60, 60, 84)
    private val BOOT = Color.rgb(16, 16, 28)
    private val WHITE = Color.WHITE

    private data class Pose(
        val dy: Int = 0,            // spostamento verticale del corpo (negativo = su)
        val legA: Int = 0, val legB: Int = 0, // spostamento orizzontale gambe
        val liftA: Int = 0, val liftB: Int = 0, // gamba sollevata
        val arm: Int = 0,           // braccio avanti
        val mouth: Boolean = false,
        val crouch: Int = 0,
        val lean: Int = 0,
        val sword: Boolean = false,
        val fade: Float = 1f,
        val eyesClosed: Boolean = false
    )

    private val cache = HashMap<String, List<Bitmap>>()

    fun frames(key: String): List<Bitmap> = cache.getOrPut(key) { poses(key).map { render(it) } }
    fun portrait(): Bitmap = frames("idle").first()

    private fun poses(key: String): List<Pose> = when (key) {
        "idle" -> listOf(Pose(), Pose(), Pose(dy = 1), Pose(dy = 1), Pose(), Pose(eyesClosed = true))
        "walk" -> listOf(Pose(legA = 2, legB = -2), Pose(dy = 1, legA = 1, legB = -1), Pose(liftB = 1), Pose(dy = -1),
            Pose(legA = -2, legB = 2), Pose(dy = 1, legA = -1, legB = 1), Pose(liftA = 1), Pose(dy = -1))
        "run" -> listOf(Pose(lean = 1, legA = 3, legB = -3, arm = 2), Pose(lean = 1, dy = -1, liftB = 2, arm = 1),
            Pose(lean = 1, legA = -3, legB = 3, arm = -1), Pose(lean = 1, dy = -1, liftA = 2, arm = 0))
        "jump" -> listOf(Pose(crouch = 2), Pose(dy = -2, liftA = 2, liftB = 2), Pose(dy = -3, liftA = 3, liftB = 1, arm = 2),
            Pose(dy = -2, liftA = 1, liftB = 1), Pose(crouch = 2), Pose())
        "slide" -> listOf(Pose(crouch = 3, lean = 2, legA = 4), Pose(crouch = 3, lean = 2, legA = 5), Pose(crouch = 3, lean = 2, legA = 5), Pose(crouch = 1))
        "fly" -> listOf(Pose(dy = -1, liftA = 1, liftB = 2), Pose(dy = -2, liftA = 1, liftB = 2), Pose(dy = -3, liftA = 2, liftB = 1), Pose(dy = -2, liftA = 2, liftB = 1))
        "talk" -> listOf(Pose(mouth = true), Pose(), Pose(mouth = true, arm = 1), Pose(), Pose(mouth = true), Pose(arm = 2), Pose(mouth = true, arm = 2), Pose())
        "attack", "sword", "special" -> listOf(Pose(arm = -1), Pose(arm = 0, sword = true), Pose(arm = 3, sword = true, lean = 1, legA = 2),
            Pose(arm = 3, sword = true, lean = 1, legA = 2), Pose(arm = 1, sword = true), Pose())
        "teleport" -> listOf(Pose(fade = 1f), Pose(fade = 0.7f), Pose(fade = 0.4f), Pose(fade = 0.15f),
            Pose(fade = 0.15f), Pose(fade = 0.4f), Pose(fade = 0.7f), Pose(fade = 1f))
        "hurt" -> listOf(Pose(lean = -1, eyesClosed = true), Pose(lean = -2, eyesClosed = true), Pose(lean = -1), Pose())
        else -> listOf(Pose(), Pose(dy = 1))
    }

    private fun render(p: Pose): Bitmap {
        val g = IntArray(G * G) { T }
        fun px(x: Int, y: Int, c: Int) { if (x in 0 until G && y in 0 until G) g[y * G + x] = c }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in y0..y1) for (x in x0..x1) px(x, y, c) }

        val top = 4 + p.dy + p.crouch; val lx = p.lean
        // gambe e stivali (y 21..27)
        val legTop = 21 + p.dy + p.crouch / 2
        val aX = 12 + p.legA; val bX = 17 + p.legB
        rect(aX, legTop, aX + 2, 25 - p.liftA, COAT); rect(aX, 26 - p.liftA, aX + 3, 27 - p.liftA, BOOT)
        rect(bX, legTop, bX + 2, 25 - p.liftB, COAT_L); rect(bX, 26 - p.liftB, bX + 3, 27 - p.liftB, BOOT)
        // mantello dietro
        rect(9 + lx, top + 10, 11 + lx, top + 17, RED_D)
        // corpo / cappotto
        rect(11 + lx, top + 9, 20 + lx, top + 17, COAT)
        rect(11 + lx, top + 9, 12 + lx, top + 17, RED)          // bordo rosso
        rect(15 + lx, top + 11, 16 + lx, top + 12, CYAN)        // emblema
        rect(11 + lx, top + 16, 20 + lx, top + 16, RED)         // cintura
        // braccio anteriore
        val ax = 20 + lx + p.arm.coerceAtLeast(0); val ay = top + 10 - (if (p.arm >= 2) 2 else 0)
        rect(ax, ay, ax + 1, ay + 5, COAT_L); rect(ax, ay + 6, ax + 1, ay + 6, SKIN)
        if (p.sword) { for (k in 0..7) px(ax + 2 + k, ay + 6 - k / 2, CYAN); px(ax + 2, ay + 7, WHITE) }
        // testa: casco rosso + viso
        rect(12 + lx, top, 20 + lx, top + 3, RED)
        rect(11 + lx, top + 1, 11 + lx, top + 6, RED)
        rect(13 + lx, top - 1, 18 + lx, top - 1, RED)
        rect(13 + lx, top + 4, 20 + lx, top + 8, SKIN)
        rect(12 + lx, top + 3, 21 + lx, top + 3, CYAN)          // visiera
        rect(19 + lx, top + 1, 21 + lx, top + 2, RED_D)         // pinna del casco
        if (p.eyesClosed) { rect(16 + lx, top + 5, 17 + lx, top + 5, OUT); rect(19 + lx, top + 5, 19 + lx, top + 5, OUT) }
        else { rect(16 + lx, top + 5, 16 + lx, top + 6, EYE); rect(19 + lx, top + 5, 19 + lx, top + 6, EYE) }
        if (p.mouth) rect(17 + lx, top + 7, 18 + lx, top + 7, RED_D) else rect(17 + lx, top + 7, 18 + lx, top + 7, Color.rgb(200, 150, 120))

        // contorno scuro
        val out = g.copyOf()
        for (y in 0 until G) for (x in 0 until G) if (g[y * G + x] == T) {
            if ((x > 0 && g[y * G + x - 1] != T) || (x < G - 1 && g[y * G + x + 1] != T) ||
                (y > 0 && g[(y - 1) * G + x] != T) || (y < G - 1 && g[(y + 1) * G + x] != T)) out[y * G + x] = OUT
        }
        // dissolvenza a righe (teletrasporto)
        if (p.fade < 1f) for (y in 0 until G) for (x in 0 until G) {
            val i = y * G + x
            if (out[i] != T) {
                if ((y * 7 + x * 3) % 10 >= (p.fade * 10).toInt()) out[i] = T
                else if (p.fade < 0.5f) out[i] = CYAN
            }
        }
        // ingrandimento ×4 → 128×128 (piedi sulla linea dei 112 px)
        val big = IntArray(128 * 128)
        for (y in 0 until 128) for (x in 0 until 128) big[y * 128 + x] = out[(y / 4) * G + (x / 4)]
        return Bitmap.createBitmap(big, 128, 128, Bitmap.Config.ARGB_8888)
    }
}
