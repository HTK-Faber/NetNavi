package com.tuo.navi.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Un numero in black list. [attempts] = istanti delle chiamate ricevute dopo il blocco. */
data class Blocked(val number: String, val label: String, val reason: String, val addedAt: Long, val attempts: List<Long> = emptyList(), val test: Boolean = false)

/** Un numero che il Navi propone di bloccare. */
data class Suggestion(val number: String, val label: String, val reason: String, val calls: Int, val inContacts: Boolean, val score: Int)

/** Una chiamata gestita dal Navi: niente audio né trascrizione, solo l'esito e il riassunto. */
data class Handled(val at: Long, val number: String, val label: String, val action: String, val seconds: Int, val summary: String)

/** Riga del registro chiamate usata per i suggerimenti. */
data class CallRec(val number: String, val name: String?, val type: Int, val date: Long, val duration: Int)

enum class Decision { ALLOW, REJECT, ANSWER }

/**
 * Black list delle chiamate moleste. Si riempie con i suggerimenti del Navi (decidi tu se accettarli).
 * I numeri bloccati vengono rifiutati in silenzio; se insistono (di default dalla 2ª chiamata in 7 giorni)
 * il Navi risponde con l'audio che hai scelto, come una segreteria, e poi riaggancia.
 * Tutto resta in navi/calls/ sul telefono.
 */
object CallGuard {
    const val WINDOW_MS = 7 * 86_400_000L
    private val dir: File get() = File(ProfileStore.baseDir, "calls").apply { mkdirs() }
    private val listFile get() = File(dir, "blocklist.json")
    private val logFile get() = File(dir, "handled.json")
    private val ignFile get() = File(dir, "ignored.json")

    private val _blocked = MutableStateFlow<List<Blocked>>(emptyList()); val blocked: StateFlow<List<Blocked>> = _blocked
    private val _log = MutableStateFlow<List<Handled>>(emptyList()); val log: StateFlow<List<Handled>> = _log
    private var ignored = mutableMapOf<String, Long>()
    private var announced = mutableSetOf<String>()
    @Volatile private var loaded = false
    private val recent = mutableMapOf<String, Long>()

    val greetingFile: File get() = File(dir, "greeting.audio")
    fun hasGreeting() = greetingFile.exists() && greetingFile.length() > 0

    @Synchronized fun load() {
        if (loaded) return
        _blocked.value = runCatching {
            val a = JSONArray(listFile.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                Blocked(o.getString("n"), o.optString("l"), o.optString("r"), o.optLong("t"),
                    o.optJSONArray("a")?.let { x -> (0 until x.length()).map { x.getLong(it) } } ?: emptyList(), o.optBoolean("test"))
            }
        }.getOrDefault(emptyList()).filterNot { it.test && System.currentTimeMillis() - it.addedAt > 86_400_000L }
        _log.value = runCatching {
            val a = JSONArray(logFile.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { o -> Handled(o.getLong("t"), o.getString("n"), o.optString("l"), o.optString("a"), o.optInt("s"), o.optString("m")) }
        }.getOrDefault(emptyList())
        runCatching { val o = JSONObject(ignFile.readText()); o.optJSONObject("i")?.let { i -> i.keys().forEach { k -> ignored[k] = i.getLong(k) } }
            o.optJSONArray("ann")?.let { a -> (0 until a.length()).forEach { announced += a.getString(it) } } }
        loaded = true
    }

    private fun saveList() = runCatching {
        val a = JSONArray(); _blocked.value.forEach { b ->
            a.put(JSONObject().put("n", b.number).put("l", b.label).put("r", b.reason).put("t", b.addedAt).put("a", JSONArray(b.attempts.takeLast(20))).put("test", b.test)) }
        listFile.writeText(a.toString())
    }
    private fun saveLog() = runCatching {
        val a = JSONArray(); _log.value.takeLast(100).forEach { h ->
            a.put(JSONObject().put("t", h.at).put("n", h.number).put("l", h.label).put("a", h.action).put("s", h.seconds).put("m", h.summary)) }
        logFile.writeText(a.toString())
    }
    private fun saveIgn() = runCatching { ignFile.writeText(JSONObject().put("i", JSONObject(ignored as Map<*, *>)).put("ann", JSONArray(announced.toList())).toString()) }

    // ---------- numeri ----------
    /** Forma confrontabile: solo cifre, senza prefisso italiano. */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var d = raw.filter { it.isDigit() || it == '+' }
        if (d.startsWith("+39")) d = d.drop(3) else if (d.startsWith("0039")) d = d.drop(4)
        return d.filter { it.isDigit() }
    }
    fun pretty(n: String) = if (n.length == 10 && n.startsWith("3")) "${n.take(3)} ${n.drop(3).take(3)} ${n.drop(6)}" else n

    fun find(number: String?): Blocked? { load(); val n = normalize(number); return if (n.isEmpty()) null else _blocked.value.firstOrNull { it.number == n } }

    @Synchronized fun block(s: Suggestion) {
        load(); if (find(s.number) != null) return
        _blocked.value = _blocked.value + Blocked(s.number, s.label, s.reason, System.currentTimeMillis()); saveList()
    }
    @Synchronized fun addTest(number: String) {
        load(); val n = normalize(number); if (n.length < 5 || find(n) != null) return
        _blocked.value = _blocked.value + Blocked(n, "Numero di prova", "Prova della risposta automatica (si toglie da solo dopo 24 ore)", System.currentTimeMillis(), test = true); saveList()
    }
    @Synchronized fun unblock(number: String) { load(); _blocked.value = _blocked.value.filterNot { it.number == number }; saveList() }
    @Synchronized fun ignore(number: String) { load(); ignored[number] = System.currentTimeMillis(); saveIgn() }
    @Synchronized fun clearLog() { _log.value = emptyList(); saveLog() }

    // ---------- decisione ----------
    /** Logica pura: cosa fare con una chiamata da un numero bloccato. */
    fun decide(b: Blocked?, now: Long, answerAfter: Int, canAnswer: Boolean): Decision {
        if (b == null) return Decision.ALLOW
        val recentCalls = b.attempts.count { now - it < WINDOW_MS } + 1
        return if (canAnswer && (b.test || recentCalls >= answerAfter)) Decision.ANSWER else Decision.REJECT
    }

    /** Chiamata in arrivo: registra il tentativo e restituisce la decisione. Non agisce due volte sulla stessa chiamata. */
    @Synchronized fun onIncoming(ctx: Context, number: String?, now: Long = System.currentTimeMillis()): Decision {
        load()
        if (!SettingsStore.callGuard) return Decision.ALLOW
        val n = normalize(number); if (n.isEmpty()) return Decision.ALLOW
        recent[n]?.let { if (now - it < 45_000L) return Decision.ALLOW }
        val b = find(n) ?: return Decision.ALLOW
        val d = decide(b, now, SettingsStore.callAnswerAfter, CallResponder.canAnswer(ctx))
        recent[n] = now
        _blocked.value = _blocked.value.map { if (it.number == n) it.copy(attempts = it.attempts + now) else it }; saveList()
        if (d == Decision.REJECT) record(Handled(now, n, b.label, "Rifiutata in silenzio", 0, ""))
        return d
    }

    @Synchronized fun record(h: Handled) { load(); _log.value = (_log.value + h).takeLast(100); saveLog() }

    // ---------- suggerimenti ----------
    fun hasLogAccess(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun readLog(ctx: Context, days: Int = 45): List<CallRec> {
        if (!hasLogAccess(ctx)) return emptyList()
        val since = System.currentTimeMillis() - days * 86_400_000L
        val out = mutableListOf<CallRec>()
        runCatching {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} > ?", arrayOf(since.toString()), "${CallLog.Calls.DATE} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 3000) out += CallRec(c.getString(0) ?: "", c.getString(1), c.getInt(2), c.getLong(3), c.getInt(4))
            }
        }
        return out
    }

    /**
     * Logica pura dei suggerimenti. Numero sconosciuto: almeno 3 chiamate in arrivo negli ultimi 30 giorni,
     * almeno 2 senza risposta o chiuse entro 10 secondi, e mai richiamato da te. Contatto in rubrica: almeno 5 chiamate,
     * l'80% senza risposta e nessuna conversazione vera. Si escludono i numeri già bloccati o che hai detto di ignorare.
     */
    fun suggestions(recs: List<CallRec>, now: Long, blocked: Set<String>, ignoredAt: Map<String, Long>): List<Suggestion> {
        val month = now - 30 * 86_400_000L
        return recs.groupBy { normalize(it.number) }.filterKeys { it.length >= 5 && it !in blocked }.mapNotNull { (n, list) ->
            ignoredAt[n]?.let { ig -> if (list.none { it.date > ig && it.type != CallLog.Calls.OUTGOING_TYPE }) return@mapNotNull null }
            val inc = list.filter { it.date > month && it.type != CallLog.Calls.OUTGOING_TYPE }
            val outs = list.count { it.type == CallLog.Calls.OUTGOING_TYPE }
            if (inc.isEmpty() || outs > 0) return@mapNotNull null
            val short = inc.count { it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE || it.type == CallLog.Calls.BLOCKED_TYPE ||
                (it.type == CallLog.Calls.INCOMING_TYPE && it.duration < 10) }
            val talked = inc.any { it.type == CallLog.Calls.INCOMING_TYPE && it.duration >= 45 }
            val name = list.firstNotNullOfOrNull { it.name?.takeIf { s -> s.isNotBlank() } }
            val contact = name != null
            val days = ((inc.maxOf { it.date } - inc.minOf { it.date }) / 86_400_000L + 1).toInt()
            val ok = if (contact) inc.size >= 5 && short * 10 >= inc.size * 8 && !talked else inc.size >= 3 && short >= 2 && !talked
            if (!ok) return@mapNotNull null
            val reason = "Ti ha chiamato ${inc.size} volte in $days ${if (days == 1) "giorno" else "giorni"}, $short senza risposta o chiuse subito, e non l'hai mai richiamato" +
                if (contact) ". È nei tuoi contatti: bloccalo solo se ti disturba davvero." else "."
            Suggestion(n, name ?: "Numero sconosciuto", reason, inc.size, contact, inc.size * 2 + short * 3 - (if (contact) 6 else 0))
        }.sortedByDescending { it.score }.take(15)
    }

    fun currentSuggestions(ctx: Context): List<Suggestion> {
        load()
        return suggestions(readLog(ctx), System.currentTimeMillis(), _blocked.value.map { it.number }.toSet(), ignored)
    }

    /** Frase per il fumetto sulla Home quando c'è un nuovo numero da proporre (una sola volta per numero). */
    @Synchronized fun newSuggestionLine(ctx: Context): String? {
        if (!SettingsStore.callGuard) return null
        val s = currentSuggestions(ctx).firstOrNull { it.number !in announced } ?: return null
        announced += s.number; saveIgn()
        return "${if (s.inContacts) s.label else "Il numero " + pretty(s.number)} ti ha chiamato ${s.calls} volte e riattacca o non rispondi mai. Vuoi metterlo in black list? Lo trovi in Chiamate moleste."
    }
}
