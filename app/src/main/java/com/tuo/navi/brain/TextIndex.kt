package com.tuo.navi.brain

import kotlin.math.ln

/**
 * Ricerca testuale on-device (BM25) con parole vuote italiane e radici semplici.
 * È leggera (nessun modello da scaricare) e basta per ritrovare i fatti che il Navi ha imparato.
 */
object TextIndex {
    private val stop = setOf(
        "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "di", "a", "da", "in", "con", "su", "per", "tra", "fra",
        "e", "ed", "o", "ma", "che", "chi", "non", "si", "no", "mi", "ti", "ci", "vi", "me", "te", "se", "del", "della",
        "dello", "dei", "degli", "delle", "al", "allo", "alla", "ai", "agli", "alle", "dal", "dalla", "dai", "nel",
        "nella", "nei", "sul", "sulla", "sui", "è", "e'", "sono", "sei", "ho", "hai", "ha", "abbiamo", "come", "cosa",
        "quando", "dove", "quale", "qual", "quanto", "mio", "mia", "miei", "mie", "tuo", "tua", "suo", "sua", "questo",
        "questa", "quello", "quella", "anche", "poi", "piu", "più", "molto", "gia", "già", "sai", "sapere", "dimmi",
        "ricorda", "ricordi", "ricordati", "l", "un'", "dell", "all", "nell", "sull", "c", "ce", "c'è", "lui", "lei"
    )

    fun normalize(s: String): String = s.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e').replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
        .replace(Regex("[^a-z0-9' ]"), " ").replace("'", "' ").replace(Regex("\\s+"), " ").trim()

    /** Radice approssimata: toglie le desinenze più comuni (fratello/fratelli -> fratell). */
    fun stem(w: String): String {
        if (w.length <= 4) return w
        val suf = listOf("issimo", "issima", "mente", "zione", "zioni", "ando", "endo", "are", "ere", "ire", "ato", "ata",
            "ati", "ate", "ito", "ita", "iti", "ite", "uto", "uta", "a", "e", "i", "o")
        for (s in suf) if (w.endsWith(s) && w.length - s.length >= 4) return w.dropLast(s.length)
        return w
    }

    fun tokens(s: String): List<String> = normalize(s).split(' ')
        .map { it.trim('\'') }.filter { it.length > 1 && it !in stop }.map(::stem)

    fun jaccard(a: String, b: String): Double {
        val x = tokens(a).toSet(); val y = tokens(b).toSet()
        if (x.isEmpty() || y.isEmpty()) return if (normalize(a) == normalize(b)) 1.0 else 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    /** Restituisce gli indici dei documenti ordinati per pertinenza (score > 0). */
    fun rank(query: String, docs: List<String>, k: Int = 5): List<Pair<Int, Double>> {
        val q = tokens(query).distinct(); if (q.isEmpty() || docs.isEmpty()) return emptyList()
        val dt = docs.map { tokens(it) }
        val avg = dt.map { it.size }.average().coerceAtLeast(1.0)
        val n = docs.size
        val df = q.associateWith { t -> dt.count { t in it } }
        val k1 = 1.4; val b = 0.7
        return dt.mapIndexed { i, d ->
            var s = 0.0
            for (t in q) {
                val f = d.count { it == t }; if (f == 0) continue
                val idf = ln(1 + (n - df[t]!! + 0.5) / (df[t]!! + 0.5))
                s += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * d.size / avg))
            }
            i to s
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(k)
    }
}
