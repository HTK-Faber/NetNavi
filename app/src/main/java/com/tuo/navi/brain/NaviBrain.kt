package com.tuo.navi.brain

import com.tuo.navi.actions.NotesStore
import com.tuo.navi.core.Ability
import com.tuo.navi.core.Leveling

/** Esito di una frase di apprendimento. continueChat = la frase va comunque passata alla chat. */
data class LearnOutcome(val reply: String, val exp: Int, val continueChat: Boolean = false, val askConfirmForgetAll: Boolean = false)

/**
 * Il "cervello" del Navi: decide cosa imparare, cosa ricordare e quali ricordi passare all'IA.
 * Tutto avviene sul telefono.
 */
object NaviBrain {

    fun learn(raw: String, lastQuestion: String?, awaitingCorrection: String?): LearnOutcome? {
        // risposta giusta dopo aver premuto "Sbagliata"
        if (awaitingCorrection != null && Leveling.has(Ability.CORRECTIONS)) {
            val intent = LearnParser.parse(raw)
            val answer = (intent as? LearnIntent.Correct)?.answer ?: raw.trim()
            MemoryStore.add(MemKind.CORRECTION, answer, key = awaitingCorrection)
            return LearnOutcome("Corretto. Se me lo richiedi risponderò così: \"${answer.take(120)}\".", Leveling.Exp.CORRECTION)
        }
        val intent = LearnParser.parse(raw) ?: return null
        return when (intent) {
            is LearnIntent.Teach -> {
                if (!Leveling.has(Ability.TAUGHT_REPLIES)) locked(Ability.TAUGHT_REPLIES)
                else { MemoryStore.add(MemKind.RULE, intent.reply, key = intent.trigger)
                    LearnOutcome("Imparato. Quando mi dirai \"${intent.trigger}\" risponderò \"${intent.reply}\".", Leveling.Exp.TAUGHT_REPLY) }
            }
            is LearnIntent.Remember -> {
                if (!Leveling.has(Ability.MEMORY)) return if (intent.implicit) null else locked(Ability.MEMORY)
                if (intent.implicit && !Leveling.has(Ability.AUTO_LEARN)) return null
                val isNew = MemoryStore.add(intent.kind, intent.text)
                if (intent.implicit) LearnOutcome(if (isNew) "Me lo segno: ${intent.kind.label.lowercase()}." else "", if (isNew) Leveling.Exp.AUTO_FACT else 0, continueChat = true)
                else LearnOutcome(if (isNew) "Ricordato (${intent.kind.label.lowercase()}): \"${intent.text.take(140)}\"." else "Lo sapevo già, ho aggiornato il ricordo.",
                    if (isNew) Leveling.Exp.LEARNED_FACT else 1)
            }
            is LearnIntent.Name -> {
                if (!Leveling.has(Ability.STYLE)) return locked(Ability.STYLE)
                MemoryStore.setStyle(MemoryStore.style.value.copy(userName = intent.name))
                MemoryStore.add(MemKind.FACT, "L'utente si chiama ${intent.name}")
                LearnOutcome("Piacere, ${intent.name}. Ti chiamerò così.", Leveling.Exp.LEARNED_FACT)
            }
            is LearnIntent.Length -> {
                if (!Leveling.has(Ability.STYLE)) return locked(Ability.STYLE)
                MemoryStore.setStyle(MemoryStore.style.value.copy(replyLength = intent.value))
                LearnOutcome(if (intent.value == "breve") "Va bene, risposte brevi." else "Va bene, risponderò in modo più completo.", 1)
            }
            is LearnIntent.Forget -> {
                val gone = MemoryStore.forget(intent.query)
                if (gone.isEmpty()) LearnOutcome("Non trovo ricordi su \"${intent.query}\".", 0)
                else LearnOutcome("Dimenticato: " + gone.take(3).joinToString("; ") { it.text.take(60) } + if (gone.size > 3) " e altri ${gone.size - 3}." else ".", 0)
            }
            LearnIntent.ForgetAll -> LearnOutcome("Vuoi davvero che dimentichi tutto quello che mi hai insegnato? Non si può annullare.", 0, askConfirmForgetAll = true)
            LearnIntent.WhatYouKnow -> LearnOutcome(summary(), 0)
            is LearnIntent.Correct -> {
                if (lastQuestion == null) LearnOutcome("Su quale domanda? Fammi prima la domanda, poi correggimi.", 0)
                else if (!Leveling.has(Ability.CORRECTIONS)) locked(Ability.CORRECTIONS)
                else { MemoryStore.add(MemKind.CORRECTION, intent.answer, key = lastQuestion)
                    LearnOutcome("Grazie, correggo: alla domanda \"${lastQuestion.take(60)}\" risponderò \"${intent.answer.take(120)}\".", Leveling.Exp.CORRECTION) }
            }
        }
    }

    private fun locked(a: Ability) = LearnOutcome("Questa abilità (${a.title}) si sblocca al livello ${a.minLevel}.", 0)

    fun summary(): String {
        val items = MemoryStore.items.value
        val st = MemoryStore.style.value
        if (items.isEmpty() && st.userName.isBlank()) return "Non mi hai ancora insegnato niente. Prova: \"ricorda che Marco è mio fratello\" o \"mi chiamo ...\"."
        val head = if (st.userName.isNotBlank()) "Ti chiami ${st.userName}. " else ""
        val byKind = items.groupBy { it.kind }.entries.joinToString(", ") { "${it.value.size} ${it.key.label.lowercase()}" }
        val some = items.filter { it.kind !in setOf(MemKind.RULE, MemKind.EXAMPLE, MemKind.CORRECTION) }.take(5).joinToString("\n") { "• " + it.text.take(90) }
        return head + "Ricordi: ${items.size} ($byKind)." + (if (some.isNotEmpty()) "\n$some" else "") + "\nLi vedi tutti in \"Cosa sa di me\"."
    }

    /** Risposta immediata senza IA: risposta insegnata, correzione o esempio approvato. */
    fun direct(user: String): MemoryItem? {
        if (!Leveling.has(Ability.TAUGHT_REPLIES) && !Leveling.has(Ability.CORRECTIONS)) return null
        return MemoryStore.directAnswer(user)?.also { MemoryStore.touch(listOf(it.id)) }
    }

    /** Ricordi e note pertinenti da passare all'IA come contesto. */
    fun context(user: String, k: Int = 4): List<String> {
        if (!Leveling.has(Ability.MEMORY)) return emptyList()
        val facts = MemoryStore.search(user, k, kinds = setOf(MemKind.FACT, MemKind.PERSON, MemKind.PREFERENCE, MemKind.DATE, MemKind.HABIT))
        MemoryStore.touch(facts.map { it.first.id })
        val notes = NotesStore.notes.value
        val noteHits = TextIndex.rank(user, notes.map { it.text }, 2).map { "Nota dell'utente: " + notes[it.first].text }
        return facts.map { it.first.text } + noteHits
    }

    /** Esempi approvati simili, per mostrare all'IA come rispondere (few-shot). */
    fun examples(user: String): List<Pair<String, String>> {
        if (!Leveling.has(Ability.CORRECTIONS)) return emptyList()
        return MemoryStore.search(user, 2, kinds = setOf(MemKind.EXAMPLE, MemKind.CORRECTION)).filter { it.second > 1.0 }.map { it.first.key to it.first.text }
    }

    /** Senza IA: risponde con il ricordo più pertinente, se la domanda riguarda qualcosa che sa. */
    fun memoryReply(user: String): String? {
        if (LearnParser.asksMyName(user)) {
            val n = MemoryStore.style.value.userName
            return if (n.isNotBlank()) "Ti chiami $n." else "Non me l'hai ancora detto. Scrivi \"mi chiamo ...\"."
        }
        if (!LearnParser.isQuestion(user) || !Leveling.has(Ability.MEMORY)) return null
        val hits = MemoryStore.search(user, 3).filter { it.second >= 1.2 && it.first.kind !in setOf(MemKind.RULE) }
        if (hits.isEmpty()) {
            val notes = NotesStore.notes.value
            val nh = TextIndex.rank(user, notes.map { it.text }, 1).firstOrNull { it.second >= 1.2 } ?: return null
            return "Nelle tue note c'è: \"${notes[nh.first].text}\"."
        }
        MemoryStore.touch(hits.map { it.first.id })
        return if (hits.size == 1) "Mi hai detto: \"${hits[0].first.text}\"."
        else "Ecco cosa ricordo:\n" + hits.joinToString("\n") { "• " + it.first.text }
    }

    /** Pulsante "Giusta": la risposta diventa un esempio da seguire. */
    fun approve(question: String, answer: String) {
        if (Leveling.has(Ability.CORRECTIONS)) MemoryStore.add(MemKind.EXAMPLE, answer, key = question)
    }

    // ---- auto-apprendimento dai comandi ----
    fun observedAlarm(h: Int, m: Int): String? = observe("alarm:%02d:%02d".format(h, m), 3, MemKind.HABIT, "Di solito l'utente mette la sveglia alle %02d:%02d".format(h, m))
    fun observedTimer(sec: Int): String? = if (sec % 60 != 0) null else observe("timer:$sec", 3, MemKind.HABIT, "L'utente usa spesso un timer di ${sec / 60} minuti")
    fun observedEvent(title: String): String? {
        val who = Regex("\\bcon ([\\p{Lu}][\\p{L}]+)").find(title)?.groupValues?.get(1) ?: return null
        return observe("person:$who", 2, MemKind.PERSON, "$who compare spesso negli appuntamenti dell'utente")
    }

    private fun observe(key: String, threshold: Int, kind: MemKind, fact: String): String? {
        if (!Leveling.has(Ability.AUTO_LEARN)) return null
        val n = MemoryStore.bump(key)
        return if (n == threshold && MemoryStore.add(kind, fact, source = "osservato")) fact else null
    }
}
