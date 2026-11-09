package com.tuo.navi.sprites

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

data class ImportResult(
    val strip: Bitmap?,          // N×128 per 128, pronto da salvare
    val frames: List<Bitmap>,
    val warnings: List<String>,
    val error: String?,
    val bandCount: Int,          // righe di personaggi trovate nell'immagine
    val method: String
)

/**
 * Importatore v2:
 *  - rimozione del verde con tolleranza e despill;
 *  - eliminazione di riquadri e linee scure tra i frame;
 *  - riconoscimento di più righe nella stessa immagine (scelta con bandIndex);
 *  - divisione dei frame per spazi vuoti, poi per linee divisorie, infine in parti uguali;
 *  - scala comune, centratura e piedi sulla linea dei 112 px (mantenendo i salti).
 */
object SpriteImporter {
    private const val C = SpriteSpec.CELL

    fun process(src0: Bitmap, expectedFrames: Int, bandIndex: Int = -1, heightRatio: Float = 1f, altFrames: Int = -1): ImportResult {
        val warn = mutableListOf<String>()
        val src = downscale(src0, 4096)
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)

        // 1. maschera del verde
        val fg = BooleanArray(w * h)
        var green = 0
        for (i in px.indices) {
            val c = px[i]; val a = c ushr 24; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val isBg = a < 30 || (g > 120 && g > r + 50 && g > b + 50)
            if (isBg) { green++; px[i] = 0 } else {
                fg[i] = true
                val m = maxOf(r, b)
                if (g > m + 10) px[i] = (0xFF shl 24) or (r shl 16) or (m shl 8) or b // despill
            }
        }
        val greenFrac = green / px.size.toFloat()
        if (greenFrac < 0.25f) return fail("Sfondo verde #00FF00 non trovato (verde ${(greenFrac * 100).toInt()}%). Rigenera la striscia con lo sfondo verde.")

        // 2. linee scure (riquadri): solo linee SOTTILI e CONTINUE per quasi tutta l'immagine.
        //    Un personaggio con abiti scuri non viene mai scambiato per un bordo: i personaggi
        //    sono separati dal verde, quindi non formano mai una striscia scura continua.
        fun darkAt(i: Int) = fg[i] && lum(px[i]) < 70
        val colCand = (0 until w).filter { x -> longestRun(h) { y -> darkAt(y * w + x) } > h * 0.85f }
        val rowCand = (0 until h).filter { y -> longestRun(w) { x -> darkAt(y * w + x) } > w * 0.85f }
        val sepCols = thinGroups(colCand)
        val sepRows = thinGroups(rowCand)
        for (x in sepCols) for (y in 0 until h) { fg[y * w + x] = false; px[y * w + x] = 0 }
        for (y in sepRows) for (x in 0 until w) { fg[y * w + x] = false; px[y * w + x] = 0 }
        if (sepCols.isNotEmpty() || sepRows.isNotEmpty()) warn += "Trovati e rimossi riquadri o linee scure tra i frame."
        removeThinLines(fg, px, w, h)

        // 3. bande orizzontali (più righe nella stessa immagine)
        val rowCount = IntArray(h) { y -> var n = 0; for (x in 0 until w) if (fg[y * w + x]) n++; n }
        val bands = segments(rowCount, minGap = 4, minCount = 2).filter { it.last - it.first > 8 }
        if (bands.isEmpty()) return fail("Nessun personaggio trovato nell'immagine.")
        val maxBand = bands.maxOf { it.last - it.first }
        val good = bands.filter { (it.last - it.first) > maxBand * 0.4f }
        val chosen = when {
            good.size == 1 -> good[0]
            bandIndex in good.indices -> good[bandIndex]
            else -> good.last()
        }
        if (good.size > 1) warn += "L'immagine contiene ${good.size} righe: uso la riga ${good.indexOf(chosen) + 1}. Puoi sceglierne un'altra."

        // 3b. pezzi staccati e piccoli (scintille, filigrane, puntini): non devono allargare il riquadro
        val removed = removeSpecks(fg, px, w, h, chosen)
        if (removed > 0) warn += "Tolti $removed puntini o simboli staccati dal personaggio (scintille, filigrane)."

        // 4. colonne dentro la banda
        val colCount = IntArray(w) { x -> var n = 0; for (y in chosen) if (fg[y * w + x]) n++; n }
        var segs = segments(colCount, minGap = 2, minCount = 1)
        segs = mergeSmall(segs)
        var method = "spazi vuoti"
        val ranges: List<IntRange> = when {
            segs.size == expectedFrames -> segs
            altFrames > 0 && segs.size == altFrames -> { warn += "Striscia vecchia da $altFrames frame: la divido nelle righe giuste (parla, sorpreso, pensa)."; segs }
            sepCols.isNotEmpty() && cellsFromSeparators(sepCols, w).size == expectedFrames -> { method = "linee divisorie"; cellsFromSeparators(sepCols, w) }
            else -> {
                method = "parti uguali"
                warn += "Trovati ${segs.size} frame invece di $expectedFrames: divido la striscia in parti uguali."
                val cw = w / expectedFrames.toFloat()
                (0 until expectedFrames).map { (it * cw).toInt() until ((it + 1) * cw).toInt() }
            }
        }

        // 5. bounding box di ogni frame
        data class Box(val l: Int, val t: Int, val r: Int, val b: Int)
        val boxes = ranges.map { xr ->
            var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
            for (y in chosen) for (x in xr) if (x in 0 until w && fg[y * w + x]) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
            if (r < 0) null else Box(l, t, r, b)
        }
        if (boxes.any { it == null }) return fail("Uno o più frame sono vuoti. Rigenera la striscia.")
        val bx = boxes.filterNotNull()
        val maxH = bx.maxOf { it.b - it.t + 1 }; val maxW = bx.maxOf { it.r - it.l + 1 }
        val baseline = bx.maxOf { it.b }
        // Scala in base all'altezza del personaggio in piedi, uguale per tutte le righe (come il Navi predefinito).
        // Le pose larghe non rimpiccioliscono più il personaggio: al massimo si riduce del 15%, poi i bordi vengono ritagliati.
        val target = SpriteSpec.CHAR_H * heightRatio.coerceIn(0.4f, 1.2f)
        val byH = target / maxH
        var scale = byH
        if (maxW * byH > C) {
            scale = maxOf(C / maxW.toFloat(), byH * 0.85f)
            if (maxW * scale > C + 1) warn += "Alcune pose sono più larghe della cella: le estremità (armi, mantello) vengono tagliate di poco."
            else warn += "Pose larghe: personaggio ridotto leggermente per farle entrare nella cella."
        }

        val clean = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        val frames = bx.map { bb ->
            val out = Bitmap.createBitmap(C, C, Bitmap.Config.ARGB_8888)
            val cw = ((bb.r - bb.l + 1) * scale).toInt().coerceAtLeast(1)
            val ch = ((bb.b - bb.t + 1) * scale).toInt().coerceAtLeast(1)
            val lift = ((baseline - bb.b) * scale).toInt()
            val bottom = SpriteSpec.BASELINE - lift
            // centra sul corpo (colonna con più pixel), non sul riquadro: armi e mantello non spostano il personaggio
            val bodyX = bodyCenter(fg, w, bb.l, bb.r, bb.t, bb.b)
            val left = (C / 2 - ((bodyX - bb.l) * scale)).toInt().coerceIn(minOf(0, C - cw), maxOf(0, C - cw))
            Canvas(out).drawBitmap(clean, Rect(bb.l, bb.t, bb.r + 1, bb.b + 1), Rect(left, bottom - ch, left + cw, bottom), paint)
            out
        }

        // 6. controllo del movimento
        val diffs = frames.zipWithNext { a, b -> diff(a, b) }
        if (diffs.isNotEmpty() && diffs.average() < 0.02) warn += "I frame sono quasi identici: l'animazione si vedrà poco."
        if (scale > 2.2f) warn += "La striscia è piccola: il personaggio è stato ingrandito molto."

        val strip = Bitmap.createBitmap(C * frames.size, C, Bitmap.Config.ARGB_8888)
        val cv = Canvas(strip); frames.forEachIndexed { i, f -> cv.drawBitmap(f, (i * C).toFloat(), 0f, null) }
        return ImportResult(strip, frames, warn, null, good.size, method)
    }

    /** Colonna centrale del corpo: media pesata delle colonne più piene del riquadro. */
    private fun bodyCenter(fg: BooleanArray, w: Int, l: Int, r: Int, t: Int, b: Int): Float {
        val cnt = IntArray(r - l + 1) { i -> var n = 0; for (y in t..b) if (fg[y * w + l + i]) n++; n }
        val mx = cnt.maxOrNull() ?: return (l + r) / 2f
        var sw = 0.0; var sx = 0.0
        cnt.forEachIndexed { i, c -> if (c >= mx * 0.5f) { sw += c; sx += c * (l + i).toDouble() } }
        return if (sw == 0.0) (l + r) / 2f else (sx / sw).toFloat()
    }

    /**
     * Toglie i gruppi di pixel staccati e piccoli (meno del 15% del pezzo più grande) lontani più di 6 px
     * da un pezzo grande. Restituisce quanti ne ha tolti.
     */
    private fun removeSpecks(fg: BooleanArray, px: IntArray, w: Int, h: Int, band: IntRange): Int {
        val y0 = band.first.coerceAtLeast(0); val y1 = band.last.coerceAtMost(h - 1)
        val lab = IntArray(w * h)
        data class Comp(val id: Int, var area: Int, var l: Int, var t: Int, var r: Int, var b: Int)
        val comps = ArrayList<Comp>()
        val stack = IntArray(w * (y1 - y0 + 1))
        for (y in y0..y1) for (x in 0 until w) {
            val i0 = y * w + x
            if (!fg[i0] || lab[i0] != 0) continue
            val c = Comp(comps.size + 1, 0, x, y, x, y); comps += c
            var sp = 0; stack[sp++] = i0; lab[i0] = c.id
            while (sp > 0) {
                val i = stack[--sp]; val cx = i % w; val cy = i / w
                c.area++; if (cx < c.l) c.l = cx; if (cx > c.r) c.r = cx; if (cy < c.t) c.t = cy; if (cy > c.b) c.b = cy
                if (cx > 0) { val j = i - 1; if (fg[j] && lab[j] == 0) { lab[j] = c.id; stack[sp++] = j } }
                if (cx < w - 1) { val j = i + 1; if (fg[j] && lab[j] == 0) { lab[j] = c.id; stack[sp++] = j } }
                if (cy > y0) { val j = i - w; if (fg[j] && lab[j] == 0) { lab[j] = c.id; stack[sp++] = j } }
                if (cy < y1) { val j = i + w; if (fg[j] && lab[j] == 0) { lab[j] = c.id; stack[sp++] = j } }
            }
        }
        if (comps.size < 2) return 0
        val maxA = comps.maxOf { it.area }
        val big = comps.filter { it.area >= maxA * 0.15f }
        fun gap(a: Comp, b: Comp): Int = maxOf(0, maxOf(a.l - b.r, b.l - a.r)) + maxOf(0, maxOf(a.t - b.b, b.t - a.b))
        val drop = comps.filter { c -> c.area < maxA * 0.15f && big.none { gap(c, it) <= 6 } }.map { it.id }.toHashSet()
        if (drop.isEmpty()) return 0
        for (y in y0..y1) for (x in 0 until w) { val i = y * w + x; if (lab[i] in drop) { fg[i] = false; px[i] = 0 } }
        return drop.size
    }

    private fun fail(msg: String) = ImportResult(null, emptyList(), emptyList(), msg, 0, "-")

    private fun lum(c: Int): Int = (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10

    /** Toglie linee scure sottili (spessore <= 3 px) rimaste dei riquadri, solo se isolate e lunghe. */
    private fun removeThinLines(fg: BooleanArray, px: IntArray, w: Int, h: Int) {
        fun dark(x: Int, y: Int) = x in 0 until w && y in 0 until h && fg[y * w + x] && lum(px[y * w + x]) < 60
        // orizzontali: run lungo almeno metà larghezza e sopra/sotto (a 4 px) non scuro per gran parte del run
        for (y in 0 until h) {
            var run = 0
            for (x in 0..w) {
                if (x < w && dark(x, y)) run++ else {
                    if (run > w / 2) {
                        val xs = (x - run) until x
                        val thick = xs.count { dark(it, y - 4) || dark(it, y + 4) }
                        if (thick < run * 0.3f) for (k in xs) { fg[y * w + k] = false; px[y * w + k] = 0 }
                    }
                    run = 0
                }
            }
        }
        for (x in 0 until w) {
            var run = 0
            for (y in 0..h) {
                if (y < h && dark(x, y)) run++ else {
                    if (run > h * 0.85f) {
                        val ys = (y - run) until y
                        val thick = ys.count { dark(x - 4, it) || dark(x + 4, it) }
                        if (thick < run * 0.3f) for (k in ys) { fg[k * w + x] = false; px[k * w + x] = 0 }
                    }
                    run = 0
                }
            }
        }
    }

    private inline fun longestRun(n: Int, test: (Int) -> Boolean): Int {
        var best = 0; var run = 0
        for (i in 0 until n) { if (test(i)) { run++; if (run > best) best = run } else run = 0 }
        return best
    }

    /** Raggruppa indici consecutivi e tiene solo i gruppi sottili (<= 6 px): i bordi, non i personaggi. */
    private fun thinGroups(idx: List<Int>): List<Int> {
        if (idx.isEmpty()) return emptyList()
        val out = mutableListOf<Int>(); var g = mutableListOf(idx[0])
        for (i in idx.drop(1)) { if (i - g.last() <= 1) g += i else { if (g.size <= 6) out += g; g = mutableListOf(i) } }
        if (g.size <= 6) out += g
        return out
    }

    private fun segments(count: IntArray, minGap: Int, minCount: Int): List<IntRange> {
        val res = mutableListOf<IntRange>(); var start = -1; var gap = 0
        for (i in count.indices) {
            if (count[i] >= minCount) { if (start < 0) start = i; gap = 0 }
            else if (start >= 0) { gap++; if (gap >= minGap) { res += start..(i - gap); start = -1; gap = 0 } }
        }
        if (start >= 0) res += start..(count.size - 1 - gap)
        return res
    }

    /** Unisce segmenti piccoli (scintille, punte di spada) al vicino più prossimo. */
    private fun mergeSmall(segs: List<IntRange>): List<IntRange> {
        if (segs.size < 2) return segs
        val med = segs.map { it.last - it.first }.sorted()[segs.size / 2]
        val list = segs.toMutableList()
        var i = 0
        while (i < list.size && list.size > 1) {
            val s = list[i]
            if (s.last - s.first < med * 0.3f) {
                val leftGap = if (i > 0) s.first - list[i - 1].last else Int.MAX_VALUE
                val rightGap = if (i < list.size - 1) list[i + 1].first - s.last else Int.MAX_VALUE
                if (leftGap <= rightGap) { list[i - 1] = list[i - 1].first..s.last; list.removeAt(i) }
                else { list[i + 1] = s.first..list[i + 1].last; list.removeAt(i) }
                i = 0
            } else i++
        }
        return list
    }

    private fun cellsFromSeparators(cols: List<Int>, w: Int): List<IntRange> {
        val groups = mutableListOf<IntRange>(); var s = cols.first(); var p = cols.first()
        for (c in cols.drop(1)) { if (c - p > 2) { groups += s..p; s = c }; p = c }
        groups += s..p
        val edges = mutableListOf(0); groups.forEach { edges += it.first; edges += it.last + 1 }; edges += w
        val cells = mutableListOf<IntRange>()
        var k = 0
        while (k < edges.size - 1) { val a = edges[k]; val b = edges[k + 1]; if (b - a > 10) cells += a until b; k += 2 }
        return cells
    }

    private fun diff(a: Bitmap, b: Bitmap): Double {
        var d = 0; var n = 0
        for (y in 0 until C step 2) for (x in 0 until C step 2) {
            val pa = a.getPixel(x, y) ushr 24 > 100; val pb = b.getPixel(x, y) ushr 24 > 100
            if (pa || pb) { n++; if (pa != pb) d++ }
        }
        return if (n == 0) 0.0 else d.toDouble() / n
    }

    private fun downscale(b: Bitmap, max: Int): Bitmap {
        if (b.width <= max && b.height <= max) return b.copy(Bitmap.Config.ARGB_8888, false)
        val k = max.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), false)
    }
}
