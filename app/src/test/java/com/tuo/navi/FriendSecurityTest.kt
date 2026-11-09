package com.tuo.navi

import com.tuo.navi.brain.Interview
import com.tuo.navi.net.Trust
import com.tuo.navi.net.WebHit
import com.tuo.navi.net.WebParse
import com.tuo.navi.net.WebRank
import com.tuo.navi.security.IocStore
import com.tuo.navi.security.NightlyScan
import com.tuo.navi.security.SecurityScanner
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class FriendSecurityTest {
    private fun at(h: Int, m: Int = 0, day: Int = 10) = Calendar.getInstance().apply { set(2026, 10, day, h, m, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun interviewLimits() {
        assertTrue(Interview.allowed(emptyList(), at(10), false))
        assertFalse(Interview.allowed(emptyList(), at(10), true))
        assertFalse(Interview.allowed(listOf(at(9)), at(11), false))          // meno di 3 ore
        assertTrue(Interview.allowed(listOf(at(9)), at(12, 1), false))
        assertFalse(Interview.allowed(listOf(at(8), at(12)), at(18), false))  // già 2 oggi
        assertTrue(Interview.allowed(listOf(at(8), at(12)), at(9, day = 11), false))
    }

    @Test fun interviewTopics() {
        val first = Interview.nextTopic(emptyMap(), emptyMap(), at(10))
        assertEquals("nome", first?.id)
        val all = Interview.topics.associate { it.id to at(10) }
        assertNull(Interview.nextTopic(all, emptyMap(), at(11)))
        assertEquals(Interview.topics[1].id, Interview.nextTopic(mapOf("nome" to 0L), mapOf(), at(10))?.id)
    }

    @Test fun interviewParsing() {
        assertEquals("Fabrizio", Interview.extractName("mi chiamo fabrizio"))
        assertEquals("Fabrizio", Interview.extractName("Fabrizio."))
        val k = Interview.keywords("il fantasy, la fantascienza e i giochi di ruolo")
        assertTrue(k.size >= 3)
    }

    @Test fun ddgHtmlParsingSkipsAds() {
        val html = """
            <div class="result results_links results_links_deep result--ad"><a class="result__a" href="https://duckduckgo.com/y.js?ad_domain=shop.it">Compra ora</a></div>
            <div class="result results_links results_links_deep web-result "><div class="links_main">
              <h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fit.wikipedia.org%2Fwiki%2FMegaman&amp;rut=abc">Mega Man &amp; NetNavi</a></h2>
              <a class="result__snippet" href="x">Serie di <b>videogiochi</b> Capcom.</a></div></div>
            <div class="result results_links web-result "><h2><a class="result__a" href="https://www.pinterest.com/pin/1">Pin</a></h2></div>
        """.trimIndent()
        val (hits, ads) = WebParse.ddgHtml(html)
        assertEquals(1, ads)
        assertEquals(2, hits.size)
        assertEquals("https://it.wikipedia.org/wiki/Megaman", hits[0].url)
        assertEquals("Mega Man & NetNavi", hits[0].title)
        assertEquals("Serie di videogiochi Capcom.", hits[0].snippet)
        assertTrue(WebParse.isChallenge("<p>Unfortunately, bots use DuckDuckGo too.</p>"))
    }

    @Test fun ddgLiteParsing() {
        val html = """
            <table><tr class="result-sponsored"><td><a rel="nofollow" href="https://duckduckgo.com/y.js?x" class='result-link'>Ad</a></td></tr>
            <tr><td><a rel="nofollow" href="https://www.ansa.it/a" class='result-link'>Notizia</a></td></tr>
            <tr><td class='result-snippet'>Testo della notizia</td></tr></table>
        """.trimIndent()
        val (hits, ads) = WebParse.ddgLite(html)
        assertEquals(1, ads); assertEquals(1, hits.size); assertEquals("Testo della notizia", hits[0].snippet)
    }

    @Test fun rankingPrefersTrustedAndDedupes() {
        val hits = listOf(
            WebHit("Pin", "https://www.pinterest.it/a", ""), WebHit("Blog", "https://blog.example.com/a", "x".repeat(80)),
            WebHit("Blog 2", "https://blog.example.com/b", ""), WebHit("ANSA", "https://www.ansa.it/n", "notizia"),
            WebHit("Coupon", "https://sconti.example.org/codice-sconto", ""), WebHit("Uni", "https://www.unibo.it/x", ""))
        val r = WebRank.rank(hits)
        assertEquals("ansa.it", r[0].domain)
        assertEquals(1, r.count { it.domain == "blog.example.com" })
        assertTrue(r.none { it.trust == Trust.LOW })
        assertEquals(Trust.HIGH, WebRank.trustOf("https://www.salute.gov.it/x"))
        assertEquals(Trust.MEDIUM, WebRank.trustOf("https://www.unieuro.it/x"))
    }

    @Test fun iocYamlParsing() {
        val y = """
            # commento
            - name: TheTruthSpy
              type: stalkerware
              names:
              - TheTruthSpy
              packages:
              - com.systemservice
              - com.sgsdsf.ss
              certificates:
              - 31a6ddce1f0a1e9f7d5d6a7c1a2b3c4d5e6f7a8b
              websites:
              - thetruthspy.com
            - name: Vuoto
              type: watchware
              websites:
              - x.com
            - name: mSpy
              type: stalkerware
              packages:
              - "core.update.framework"
        """.trimIndent()
        val e = IocStore.parseYaml(y)
        assertEquals(2, e.size)
        assertEquals(listOf("com.systemservice", "com.sgsdsf.ss"), e[0].packages)
        assertEquals("31A6DDCE1F0A1E9F7D5D6A7C1A2B3C4D5E6F7A8B", e[0].certs[0])
        assertEquals("mSpy", IocStore.match(e, "core.update.framework", emptyList())?.name)
        assertEquals("TheTruthSpy", IocStore.match(e, "x.y", listOf("31A6DDCE1F0A1E9F7D5D6A7C1A2B3C4D5E6F7A8B"))?.name)
        assertNull(IocStore.match(e, "com.whatsapp", emptyList()))
    }

    @Test fun iocRealFile() {
        val txt = javaClass.classLoader!!.getResource("ioc_sample.yaml")!!.readText()
        val e = IocStore.parseYaml(txt)
        assertTrue(e.size > 100)
        assertNotNull(IocStore.match(e, "com.systemservice", emptyList()))
        assertTrue(e.flatMap { it.packages }.size > 300)
    }

    @Test fun riskAndSchedule() {
        assertTrue(SecurityScanner.riskScore(true, true, false, 5, true, true) >= 7)
        assertTrue(SecurityScanner.riskScore(false, false, false, 2, false, false) < 5)
        assertEquals(12, SecurityScanner.patchAgeMonths("2025-11-01", at(10))?.let { it } ?: -1)
        val next = Calendar.getInstance().apply { timeInMillis = NightlyScan.nextAt(at(23)) }
        assertEquals(3, next.get(Calendar.HOUR_OF_DAY)); assertEquals(11, next.get(Calendar.DAY_OF_MONTH))
        val same = Calendar.getInstance().apply { timeInMillis = NightlyScan.nextAt(at(1)) }
        assertEquals(10, same.get(Calendar.DAY_OF_MONTH))
    }
}
