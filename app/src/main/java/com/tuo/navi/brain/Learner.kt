package com.tuo.navi.brain

/** Cosa l'utente sta insegnando al Navi. */
sealed class LearnIntent {
    /** implicit = frase detta in chat ("mi piace il caffè"), non un ordine esplicito ("ricorda che..."). */
    data class Remember(val kind: MemKind, val text: String, val implicit: Boolean = false) : LearnIntent()
    data class Name(val name: String) : LearnIntent()
    data class Teach(val trigger: String, val reply: String) : LearnIntent()
    data class Forget(val query: String) : LearnIntent()
    object ForgetAll : LearnIntent()
    object WhatYouKnow : LearnIntent()
    data class Length(val value: String) : LearnIntent()
    data class Correct(val answer: String) : LearnIntent()
}

/** Interprete a regole delle frasi di apprendimento (offline, italiano). Funzioni pure: testabili senza Android. */
object LearnParser {
    private val I = RegexOption.IGNORE_CASE
    private const val Q = "[\"“”«»']"

    private val teach = Regex("^quando (?:ti )?(?:dico|scrivo|chiedo)\\s+$Q?(.+?)$Q?\\s*,?\\s*(?:tu\\s+)?rispondi(?:mi)?\\s*(?:con\\s+)?:?\\s*$Q?(.+?)$Q?[.!]?$", I)
    private val remember = Regex("^(?:ricorda(?:ti)?(?: bene)?(?: che|:)|tieni a mente(?: che)?|impara(?: che|:)|sappi che|memorizza(?: che|:)?|segnati che)\\s*(.+)$", I)
    private val name = Regex("^(?:mi chiamo|il mio nome (?:è|e'|e)|chiamami)\\s+([\\p{L}' ]{2,30}?)[.!]?$", I)
    private val forgetAll = Regex("^(?:dimentica|cancella|scorda(?:ti)?) tutto|^azzera (?:la )?(?:tua )?memoria", I)
    private val forget = Regex("^(?:dimentica|scordati|cancella dalla memoria)(?: che| di| quello che sai su)?\\s+(.+)$", I)
    private val know = Regex("cosa sai di me|che cosa sai di me|cosa ricordi(?: di me)?|cosa hai imparato|cosa ti ho insegnato", I)
    private val short = Regex("(?:rispondi|risposte|parla)\\s+(?:in modo\\s+)?(?:piu |più )?(?:breve|brevi|corte|corto|sintetic\\w*)", I)
    private val long = Regex("(?:rispondi|risposte|parla)\\s+(?:in modo\\s+)?(?:piu |più )?(?:lunghe|lungo|dettagliat\\w*|approfondit\\w*)", I)
    private val correct1 = Regex("^(?:sbagliato|errato|non è così|non e cosi|non è vero|non e vero|hai sbagliato)[,.:!]?\\s+(?:la risposta (?:giusta|corretta) (?:è|e|era)\\s+)?(.{2,})$", I)
    private val correct2 = Regex("^(?:la risposta (?:giusta|corretta) (?:è|e|era)|correzione:?|correggi(?:ti)?:)\\s+(.{2,})$", I)

    // frasi spontanee che il Navi può memorizzare da solo
    private val implicitPatterns = listOf(
        Regex("^(?:a me )?(?:mi piace|mi piacciono|non mi piace|non mi piacciono|adoro|odio|preferisco|detesto|amo)\\s+[^?]{2,}$", I),
        Regex("^[\\p{Lu}][\\p{L}]+ (?:è|e') (?:il |la |l')?(?:mio|mia) [\\p{L}]+[^?]*$"),
        Regex("^(?:mio|mia) [\\p{L}]+ si chiama [\\p{L}]+[^?]*$", I),
        Regex("^il mio compleanno (?:è|e) [^?]+$", I),
        Regex("^sono nat[oa] (?:il|a) [^?]+$", I),
        Regex("^(?:abito|vivo) a [^?]+$", I),
        Regex("^lavoro (?:come|da|in|presso|per) [^?]+$", I),
        Regex("^(?:il mio|la mia) [\\p{L}]+ preferit[oa] (?:è|e) [^?]+$", I)
    )

    fun parse(raw: String): LearnIntent? {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.length < 4) return null
        teach.find(t)?.let { m -> val a = m.groupValues[1].trim(); val b = m.groupValues[2].trim()
            if (a.length >= 2 && b.isNotEmpty()) return LearnIntent.Teach(a, b) }
        if (forgetAll.containsMatchIn(t)) return LearnIntent.ForgetAll
        forget.find(t)?.let { return LearnIntent.Forget(it.groupValues[1].trim()) }
        if (know.containsMatchIn(t)) return LearnIntent.WhatYouKnow
        if (short.containsMatchIn(t)) return LearnIntent.Length("breve")
        if (long.containsMatchIn(t)) return LearnIntent.Length("lunga")
        correct1.find(t)?.let { return LearnIntent.Correct(it.groupValues[1].trim()) }
        correct2.find(t)?.let { return LearnIntent.Correct(it.groupValues[1].trim()) }
        name.find(t)?.let { m -> val n = m.groupValues[1].trim()
            if (!n.contains(Regex("\\d")) && n.split(' ').size <= 3) return LearnIntent.Name(n.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }) }
        remember.find(t)?.let { val body = it.groupValues[1].trim().trimEnd('.')
            if (body.length >= 3) return LearnIntent.Remember(classify(body), body) }
        if (!t.endsWith("?") && implicitPatterns.any { it.matches(t.trimEnd('.', '!')) })
            return LearnIntent.Remember(classify(t), t.trimEnd('.', '!'), implicit = true)
        return null
    }

    fun classify(s: String): MemKind {
        val n = TextIndex.normalize(s)
        return when {
            Regex("compleanno|anniversario|onomastico|nat[oa] il|scadenza").containsMatchIn(n) -> MemKind.DATE
            Regex("\\b(mio|mia|miei|mie) (fratell|sorell|madre|mamma|padre|papa|moglie|marito|figli|amic|collega|capo|ragazz|compagn|cugin|nonn|zi[oa]|fidanzat|suocer|cognat|nipot|vicin)").containsMatchIn(n)
                || n.contains(" si chiama ") -> MemKind.PERSON
            Regex("mi piac|non mi piac|adoro|odio|preferisc|\\bamo\\b|detest|preferit").containsMatchIn(n) -> MemKind.PREFERENCE
            Regex("di solito|ogni (giorno|mattina|sera|lunedi|martedi|mercoledi|giovedi|venerdi|sabato|domenica)|sempre alle").containsMatchIn(n) -> MemKind.HABIT
            else -> MemKind.FACT
        }
    }

    /** La frase è una domanda su di sé o sul Navi a cui la memoria può rispondere anche senza IA. */
    fun isQuestion(raw: String): Boolean {
        val n = TextIndex.normalize(raw)
        return raw.trim().endsWith("?") || Regex("^(chi|come|quando|cosa|che cosa|dove|qual|quale|quanti|quante|perche|ti ricordi|sai)\\b").containsMatchIn(n)
    }

    fun asksMyName(raw: String) = Regex("come mi chiamo|qual (?:e|è) il mio nome|sai il mio nome|chi sono io", I).containsMatchIn(TextIndex.normalize(raw))
}
