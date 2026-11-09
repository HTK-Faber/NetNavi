package com.tuo.navi.net

import com.tuo.navi.ai.NetGate
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** Un risultato di ricerca mostrato in chat. */
data class WebHit(val title: String, val url: String, val snippet: String, val trust: Trust = Trust.MEDIUM, val score: Double = 0.0) {
    val domain: String get() = WebRank.domainOf(url)
}

enum class Trust(val label: String) { HIGH("fonte autorevole"), MEDIUM("da verificare"), LOW("poco affidabile") }

data class SearchOutcome(val hits: List<WebHit>, val blocked: Boolean, val adsRemoved: Int)

/**
 * Ricerca dalla chat, solo con l'interruttore Internet acceso.
 * Esce dal telefono soltanto il testo cercato, verso DuckDuckGo (nessun profilo pubblicitario) e Wikipedia.
 * Gli annunci vengono scartati; i risultati sono ordinati per affidabilità della fonte e per posizione.
 */
object WebSearch {
    fun browserUrl(q: String) = "https://duckduckgo.com/?q=" + URLEncoder.encode(q, "UTF-8") + "&kl=it-it"

    fun search(q: String): SearchOutcome {
        val enc = URLEncoder.encode(q, "UTF-8")
        var blocked = false
        var ads = 0
        val web = runCatching {
            val html = NetGate.fetchText("https://html.duckduckgo.com/html/?q=$enc&kl=it-it")
            if (WebParse.isChallenge(html)) { blocked = true; emptyList() }
            else WebParse.ddgHtml(html).also { ads += it.second }.first
        }.getOrElse { emptyList() }.ifEmpty {
            runCatching {
                val html = NetGate.fetchText("https://lite.duckduckgo.com/lite/?q=$enc&kl=it-it")
                if (WebParse.isChallenge(html)) { blocked = true; emptyList() } else { blocked = false; WebParse.ddgLite(html).also { ads += it.second }.first }
            }.getOrElse { emptyList() }
        }
        val wiki = runCatching { WebParse.wikipedia(NetGate.fetchText("https://it.wikipedia.org/w/rest.php/v1/search/page?q=$enc&limit=2")) }.getOrElse { emptyList() }
        return SearchOutcome(WebRank.rank(wiki.take(1) + web), blocked && web.isEmpty(), ads)
    }
}

/** Lettura delle pagine di risultati (funzioni pure, testate). */
object WebParse {
    fun isChallenge(html: String) = html.contains("bots use DuckDuckGo too", true) || html.contains("anomaly-modal", true) ||
        html.contains("challenge-form", true)

    private fun strip(s: String) = decodeEntities(s.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()

    fun decodeEntities(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }

    /** Toglie il rimando di DuckDuckGo (//duckduckgo.com/l/?uddg=...) e restituisce l'indirizzo vero; null se è un annuncio. */
    fun realUrl(href0: String): String? {
        val href = decodeEntities(href0)
        if (href.contains("duckduckgo.com/y.js") || href.contains("ad_domain=") || href.contains("ad_provider=") || href.contains("bing.com/aclick")) return null
        val u = Regex("[?&]uddg=([^&]+)").find(href)?.let { URLDecoder.decode(it.groupValues[1], "UTF-8") } ?: href
        val full = if (u.startsWith("//")) "https:$u" else u
        return full.takeIf { it.startsWith("http") && !WebRank.domainOf(it).endsWith("duckduckgo.com") }
    }

    /** Versione HTML di DuckDuckGo. Restituisce risultati e numero di annunci scartati. */
    fun ddgHtml(html: String): Pair<List<WebHit>, Int> {
        val out = mutableListOf<WebHit>(); var ads = 0
        val blocks = html.split(Regex("<div[^>]+class=\"[^\"]*\\bresult\\b")).drop(1)
        for (b in blocks) {
            val head = b.substringBefore('>')
            if (head.contains("result--ad") || b.contains("badge--ad")) { ads++; continue }
            val a = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b)
                ?: Regex("<a[^>]*href=\"([^\"]+)\"[^>]*class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b) ?: continue
            val url = realUrl(a.groupValues[1]) ?: run { ads++; null } ?: continue
            val sn = Regex("class=\"result__snippet\"[^>]*>(.*?)</(a|div)>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let(::strip).orEmpty()
            out += WebHit(strip(a.groupValues[2]), url, sn)
        }
        return out to ads
    }

    /** Versione "lite" di DuckDuckGo (tabella). Le righe sponsorizzate vengono scartate. */
    fun ddgLite(html: String): Pair<List<WebHit>, Int> {
        val out = mutableListOf<WebHit>(); var ads = 0
        val rows = html.split(Regex("<tr")).drop(1)
        var pending: WebHit? = null
        for (r in rows) {
            if (r.contains("result-sponsored")) { ads++; pending = null; continue }
            val a = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*class=['\"]result-link['\"][^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(r)
                ?: Regex("<a[^>]*class=['\"]result-link['\"][^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(r)
            if (a != null) {
                pending?.let { out += it }
                val url = realUrl(a.groupValues[1])
                pending = if (url == null) { ads++; null } else WebHit(strip(a.groupValues[2]), url, "")
                continue
            }
            val sn = Regex("class=['\"]result-snippet['\"][^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).find(r)
            if (sn != null && pending != null) { out += pending!!.copy(snippet = strip(sn.groupValues[1])); pending = null }
        }
        pending?.let { out += it }
        return out to ads
    }

    fun wikipedia(json: String): List<WebHit> {
        val a = JSONObject(json).optJSONArray("pages") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
            val desc = p.optString("description").takeIf { it.isNotBlank() && it != "null" }
            WebHit(p.optString("title") + " (Wikipedia)", "https://it.wikipedia.org/wiki/" + URLEncoder.encode(p.optString("key"), "UTF-8").replace("+", "_"),
                desc ?: strip(p.optString("excerpt")), Trust.HIGH)
        }
    }
}

/** Affidabilità delle fonti: elenco di siti autorevoli, siti commerciali o poco controllati, posizione nella pagina. */
object WebRank {
    private val high = listOf("wikipedia.org", "treccani.it", "europa.eu", "who.int", "istat.it", "salute.gov.it", "iss.it", "inps.it", "agenziaentrate.gov.it",
        "ansa.it", "ilpost.it", "rainews.it", "corriere.it", "repubblica.it", "ilsole24ore.com", "lastampa.it", "bbc.com", "bbc.co.uk", "reuters.com", "apnews.com",
        "nature.com", "science.org", "ncbi.nlm.nih.gov", "nih.gov", "britannica.com", "developer.android.com", "android.com", "support.google.com",
        "mozilla.org", "github.com", "stackoverflow.com", "w3.org", "cnr.it", "esa.int", "nasa.gov", "focus.it", "wired.it", "altroconsumo.it",
        "garanteprivacy.it", "gazzettaufficiale.it", "normattiva.it", "meteoam.it", "protezionecivile.gov.it")
    private val low = listOf("pinterest.", "quora.com", "facebook.com", "instagram.com", "tiktok.com", "x.com", "twitter.com", "amazon.", "ebay.", "aliexpress.",
        "temu.com", "shein.", "wish.com", "yahoo.com/answers", "answers.", "scribd.com", "slideshare.", "medium.com")
    private val spammy = Regex("coupon|codice-sconto|offert[ae]|promo|casino|scommess|\\bbet|affiliat|utm_|clickbank|guadagna|soldi-facili", RegexOption.IGNORE_CASE)

    fun domainOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.").lowercase()

    fun trustOf(url: String): Trust {
        val d = domainOf(url)
        return when {
            high.any { d == it || d.endsWith(".$it") } || d.endsWith(".gov") || d.endsWith(".gov.it") || d.endsWith(".edu") || d.endsWith(".edu.it") ||
                d.endsWith(".ac.uk") || d.contains("unibo.it") || d.contains("unimi.it") || d.contains("polimi.it") -> Trust.HIGH
            low.any { d.contains(it) } || spammy.containsMatchIn(url) -> Trust.LOW
            else -> Trust.MEDIUM
        }
    }

    /** Un risultato per sito, ordinati per affidabilità e posizione; al massimo 5, i poco affidabili solo se mancano gli altri. */
    fun rank(hits: List<WebHit>, max: Int = 5): List<WebHit> {
        val scored = hits.mapIndexed { i, h ->
            val t = if (h.trust == Trust.HIGH) Trust.HIGH else trustOf(h.url)
            val s = 10.0 - i * 0.6 + when (t) { Trust.HIGH -> 6.0; Trust.MEDIUM -> 0.0; Trust.LOW -> -6.0 } +
                (if (h.url.startsWith("https")) 1.0 else 0.0) + (if (h.snippet.length > 60) 1.0 else 0.0)
            h.copy(trust = t, score = s)
        }.filter { it.title.isNotBlank() }
        val best = scored.groupBy { it.domain }.map { (_, v) -> v.maxBy { it.score } }.sortedByDescending { it.score }
        val good = best.filter { it.trust != Trust.LOW }
        return (if (good.size >= 3) good else best).take(max)
    }
}
