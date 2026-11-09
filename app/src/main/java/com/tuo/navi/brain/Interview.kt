package com.tuo.navi.brain

import com.tuo.navi.data.ProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** Un argomento su cui il Navi ti fa una domanda per conoscerti meglio. */
data class Topic(
    val id: String, val label: String, val question: String, val kind: MemKind,
    /** Dopo quanti giorni la domanda si può rifare (0 = mai): es. "cosa stai leggendo ora". */
    val refreshDays: Int = 0,
    val ack: String
)

/**
 * Il Navi ti fa domande per conoscerti: al massimo 2 al giorno, ad almeno 3 ore l'una dall'altra.
 * Le risposte finiscono nei Ricordi (con le parole chiave) e rendono il Navi più affine a te.
 * Puoi rispondere "salta" (te la richiede più avanti) o "non chiedermelo più" (smette di fare domande).
 * Lo stato è salvato in navi/brain/interview.json: resta sul telefono e si sposta solo con il file .navi.
 */
object Interview {
    const val MAX_PER_DAY = 2
    const val MIN_GAP_MS = 3 * 3600_000L
    private const val SKIP_RETRY_DAYS = 14

    val topics = listOf(
        Topic("nome", "Come ti chiami", "Posso chiederti una cosa per conoscerti meglio? Come ti chiami, o come preferisci che ti chiami?", MemKind.FACT,
            ack = "Piacere. Da adesso ti chiamo così."),
        Topic("interessi", "Interessi", "Quali sono i tuoi interessi? Le cose che ti appassionano davvero.", MemKind.PREFERENCE,
            ack = "Segnato. Se trovo qualcosa di utile su questi argomenti te lo dico."),
        Topic("libri_letti", "Libri letti", "Quali libri hai letto che ti sono rimasti dentro?", MemKind.PREFERENCE,
            ack = "Bella lista. Me la segno: mi aiuta a capire come ragioni."),
        Topic("libro_ora", "Libro in lettura", "Stai leggendo qualcosa in questo periodo?", MemKind.PREFERENCE, refreshDays = 30,
            ack = "Segnato. Più avanti ti chiederò com'è andata."),
        Topic("musica", "Musica preferita", "Che musica ascolti e ti piace? Generi, artisti, album.", MemKind.PREFERENCE,
            ack = "Me lo segno tra i tuoi gusti."),
        Topic("musica_ora", "Musica del momento", "Cosa stai ascoltando ultimamente?", MemKind.PREFERENCE, refreshDays = 30,
            ack = "Segnato. Vediamo se cambia nel tempo."),
        Topic("film", "Film e serie", "Quali film o serie ami?", MemKind.PREFERENCE,
            ack = "Segnato tra i tuoi gusti."),
        Topic("giochi", "Giochi", "Giochi a qualcosa? Videogiochi, giochi da tavolo, di ruolo.", MemKind.PREFERENCE,
            ack = "Segnato. Da Navi, approvo."),
        Topic("lavoro", "Lavoro o studio", "Di cosa ti occupi? Lavoro, studio, progetti.", MemKind.FACT,
            ack = "Segnato. Così capisco meglio le tue giornate."),
        Topic("obiettivo", "Obiettivo attuale", "C'è un obiettivo su cui stai lavorando adesso? Così posso aiutarti a non perderlo di vista.", MemKind.FACT, refreshDays = 45,
            ack = "Segnato. Ogni tanto ti chiederò a che punto sei: non per controllarti, per aiutarti."),
        Topic("abitudine", "Abitudine da migliorare", "C'è un'abitudine che vorresti migliorare?", MemKind.HABIT, refreshDays = 60,
            ack = "Riconoscerlo è già un passo. Partiamo da una cosa piccola e costante."),
        Topic("routine", "Routine", "A che ora inizia di solito la tua giornata, e quando finisce?", MemKind.HABIT,
            ack = "Segnato. Ne terrò conto per sveglie e promemoria."),
        Topic("cibo", "Cibo preferito", "Qual è il tuo piatto preferito?", MemKind.PREFERENCE,
            ack = "Segnato."),
        Topic("luoghi", "Luoghi", "C'è un posto dove ti piacerebbe andare, o a cui sei legato?", MemKind.PREFERENCE,
            ack = "Segnato. Un buon obiettivo da tenere a mente."),
        Topic("sport", "Movimento", "Fai sport o attività fisica? Anche solo camminare conta.", MemKind.HABIT,
            ack = "Segnato. Se vuoi, posso ricordarti di muoverti un po'.")
    )

    private val file: File get() = File(ProfileStore.baseDir, "brain/interview.json")

    // stato in memoria
    private var asks = mutableListOf<Long>()                 // quando ha fatto le domande
    private val answered = HashMap<String, Long>()           // argomento -> quando hai risposto
    private val skipped = HashMap<String, Long>()            // argomento -> quando l'hai saltato
    var stopped = false; private set
    private var loaded = false

    @Synchronized
    private fun ensure() {
        if (loaded) return
        runCatching {
            val o = JSONObject(file.readText())
            asks = o.optJSONArray("asks")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toMutableList() } ?: mutableListOf()
            o.optJSONObject("answered")?.let { j -> j.keys().forEach { answered[it] = j.getLong(it) } }
            o.optJSONObject("skipped")?.let { j -> j.keys().forEach { skipped[it] = j.getLong(it) } }
            stopped = o.optBoolean("stopped", false)
        }
        loaded = true
    }

    @Synchronized
    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("asks", JSONArray(asks.takeLast(20)))
            .put("answered", JSONObject(answered.mapValues { it.value }))
            .put("skipped", JSONObject(skipped.mapValues { it.value }))
            .put("stopped", stopped).toString())
    }

    /** Logica pura (testabile): si può fare una domanda adesso? */
    fun allowed(asksAt: List<Long>, now: Long, stopped: Boolean): Boolean {
        if (stopped) return false
        val last = asksAt.maxOrNull()
        if (last != null && now - last < MIN_GAP_MS) return false
        val day = dayOf(now)
        return asksAt.count { dayOf(it) == day } < MAX_PER_DAY
    }

    private fun dayOf(t: Long): Int = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    /** Logica pura: il prossimo argomento da chiedere. */
    fun nextTopic(answered: Map<String, Long>, skipped: Map<String, Long>, now: Long): Topic? {
        fun skippedRecently(t: Topic) = skipped[t.id]?.let { now - it < SKIP_RETRY_DAYS * 86_400_000L } == true
        topics.firstOrNull { it.id !in answered && !skippedRecently(it) }?.let { return it }
        return topics.firstOrNull { t -> t.refreshDays > 0 && !skippedRecently(t) && answered[t.id]?.let { now - it > t.refreshDays * 86_400_000L } == true }
    }

    /** Domanda da fare adesso, oppure null. [force] = l'hai chiesta tu ("fammi una domanda"). */
    @Synchronized
    fun questionNow(force: Boolean = false, now: Long = System.currentTimeMillis()): Topic? {
        ensure()
        if (!force && !allowed(asks, now, stopped)) return null
        val t = nextTopic(answered, skipped, now) ?: return null
        asks.add(now); if (force) stopped = false; save()
        return t
    }

    @Synchronized fun skip(t: Topic) { ensure(); skipped[t.id] = System.currentTimeMillis(); save() }
    @Synchronized fun stop() { ensure(); stopped = true; save() }

    /** Salva la risposta nei Ricordi con le parole chiave; restituisce la frase di conferma del Navi. */
    @Synchronized
    fun answer(t: Topic, raw: String): String {
        ensure()
        val text = raw.trim().trimEnd('.')
        if (t.id == "nome") {
            val name = extractName(text)
            if (name.isNotBlank()) MemoryStore.setStyle(MemoryStore.style.value.copy(userName = name))
            MemoryStore.replaceByKey(MemKind.FACT, "profilo:nome", "L'utente si chiama $name")
            answered[t.id] = System.currentTimeMillis(); save()
            return "Piacere, $name. Da adesso ti chiamo così."
        }
        val kws = keywords(text)
        val line = "${t.label}: " + (if (kws.size >= 2) kws.joinToString(", ") else text)
        if (t.refreshDays > 0) MemoryStore.replaceByKey(t.kind, "profilo:${t.id}", line)
        else MemoryStore.add(t.kind, line, "profilo:${t.id}", source = "domanda")
        answered[t.id] = System.currentTimeMillis(); skipped.remove(t.id); save()
        return t.ack
    }

    /** Quanto il Navi ti conosce: argomenti con risposta sul totale (0-100). */
    fun affinity(): Int { ensure(); return answered.keys.count { k -> topics.any { it.id == k } } * 100 / topics.size }
    fun answeredCount(): Int { ensure(); return answered.size }

    @Synchronized fun reset() { asks.clear(); answered.clear(); skipped.clear(); stopped = false; loaded = true; save() }

    // ------------------------------------------------------------------ testo

    private val stop = setOf("e", "ed", "o", "di", "il", "lo", "la", "i", "gli", "le", "un", "una", "uno", "mi", "piace", "piacciono", "ascolto",
        "leggo", "guardo", "gioco", "a", "da", "in", "con", "su", "per", "tra", "fra", "che", "ho", "letto", "sto", "leggendo", "soprattutto", "molto", "anche")

    /** Parole chiave: separa per virgole, "e", punto e virgola; toglie le parole vuote all'inizio. */
    fun keywords(text: String): List<String> =
        text.split(Regex("[,;/\\n]|\\s+e\\s+|\\s+ed\\s+|\\s+oppure\\s+", RegexOption.IGNORE_CASE))
            .map { part -> part.trim().split(Regex("\\s+")).dropWhile { it.lowercase() in stop }.joinToString(" ").trim(' ', '.', '!', '?') }
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase() }
            .take(12)

    fun extractName(text: String): String {
        val t = text.trim().trimEnd('.', '!')
        val m = Regex("(?i)(?:mi chiamo|chiamami|sono|il mio nome (?:è|e))\\s+([\\p{L}' ]{2,30})").find(t)
        val n = (m?.groupValues?.get(1) ?: t).trim().split(Regex("\\s+")).take(2).joinToString(" ")
        return n.replaceFirstChar { it.uppercase() }
    }
}
