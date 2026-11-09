package com.tuo.navi

import android.provider.CallLog
import com.tuo.navi.calls.Blocked
import com.tuo.navi.calls.CallGuard
import com.tuo.navi.calls.CallRec
import com.tuo.navi.calls.CallSummary
import com.tuo.navi.calls.Decision
import org.junit.Assert.*
import org.junit.Test

class CallGuardTest {
    private val day = 86_400_000L
    private val now = 100 * day

    @Test fun normalizza() {
        assertEquals("3331234567", CallGuard.normalize("+39 333 123 4567"))
        assertEquals("3331234567", CallGuard.normalize("0039-333-1234567"))
        assertEquals("0212345678", CallGuard.normalize("02 1234 5678"))
        assertEquals("", CallGuard.normalize(null))
    }

    @Test fun decisione() {
        val b = Blocked("3331234567", "x", "r", 0)
        assertEquals(Decision.ALLOW, CallGuard.decide(null, now, 2, true))
        assertEquals(Decision.REJECT, CallGuard.decide(b, now, 2, true))           // prima chiamata dopo il blocco
        val b2 = b.copy(attempts = listOf(now - day))
        assertEquals(Decision.ANSWER, CallGuard.decide(b2, now, 2, true))           // seconda in 7 giorni
        assertEquals(Decision.REJECT, CallGuard.decide(b2, now, 2, false))          // senza audio o permesso
        assertEquals(Decision.REJECT, CallGuard.decide(b.copy(attempts = listOf(now - 10 * day)), now, 2, true)) // troppo vecchia
        assertEquals(Decision.ANSWER, CallGuard.decide(b.copy(test = true), now, 5, true))
    }

    private fun rec(n: String, type: Int, d: Long, dur: Int = 0, name: String? = null) = CallRec(n, name, type, now - d * day, dur)

    @Test fun suggerimenti() {
        val recs = listOf(
            // molesto: 4 chiamate, mai risposto
            rec("+393401111111", CallLog.Calls.MISSED_TYPE, 1), rec("3401111111", CallLog.Calls.MISSED_TYPE, 3),
            rec("3401111111", CallLog.Calls.REJECTED_TYPE, 5), rec("3401111111", CallLog.Calls.INCOMING_TYPE, 7, 4),
            // amico: l'hai richiamato
            rec("3402222222", CallLog.Calls.MISSED_TYPE, 1), rec("3402222222", CallLog.Calls.MISSED_TYPE, 2),
            rec("3402222222", CallLog.Calls.MISSED_TYPE, 3), rec("3402222222", CallLog.Calls.OUTGOING_TYPE, 3, 60),
            // solo 2 chiamate
            rec("3403333333", CallLog.Calls.MISSED_TYPE, 1), rec("3403333333", CallLog.Calls.MISSED_TYPE, 2),
            // contatto con una sola chiamata persa su 3: non basta
            rec("3404444444", CallLog.Calls.MISSED_TYPE, 1, name = "Zia"), rec("3404444444", CallLog.Calls.MISSED_TYPE, 2, name = "Zia"),
            rec("3404444444", CallLog.Calls.MISSED_TYPE, 3, name = "Zia"))
        val s = CallGuard.suggestions(recs, now, emptySet(), emptyMap())
        assertEquals(listOf("3401111111"), s.map { it.number })
        assertTrue(s[0].reason.contains("4 volte"))
        assertTrue(CallGuard.suggestions(recs, now, setOf("3401111111"), emptyMap()).isEmpty())
        assertTrue(CallGuard.suggestions(recs, now, emptySet(), mapOf("3401111111" to now)).isEmpty())
    }

    @Test fun riassunto() {
        val t = "Buongiorno sono Laura di Enel Energia, la chiamo per un'offerta sulla bolletta della luce e del gas, mi può richiamare al 02 1234 5678"
        val r = CallSummary.summarize(t, 12)
        assertTrue(r, r.contains("luce e gas"))
        assertTrue(r, r.contains("Enel"))
        assertTrue(r, r.contains("0212345678"))
        val scam = CallSummary.summarize("la sua carta è bloccata, mi dica il codice che ha ricevuto per sms", 8)
        assertTrue(scam, scam.contains("truffa"))
        assertTrue(CallSummary.summarize("", 6).contains("6 secondi"))
    }
}
