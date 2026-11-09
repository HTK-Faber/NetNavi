package com.tuo.navi.phrases

import com.tuo.navi.core.NaviNotif
import com.tuo.navi.domain.Mood
import com.tuo.navi.domain.NaviProfile
import java.util.Calendar
import kotlin.random.Random

/**
 * Motore di frasi offline: dà varietà e personalità senza IA.
 * Usa solo app e mittente, mai il contenuto dei messaggi.
 */
object PhraseEngine {
    private val recent = ArrayDeque<String>()

    private fun pick(options: List<String>): String {
        val fresh = options.filterNot { it in recent }.ifEmpty { options }
        val s = fresh[Random.nextInt(fresh.size)]
        recent.addLast(s); if (recent.size > 12) recent.removeFirst()
        return s
    }

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    fun wakeUp(): String = pick(listOf("Mi ero appisolato. Eccomi.", "Sveglio. Mi sono perso qualcosa?", "Un pisolino e sono di nuovo operativo.",
        "Ah, sei tornato. Riprendiamo da dove eravamo."))

    fun petted(): String = pick(listOf("Grazie. Anche io ci tengo a te.", "Mi fa piacere. Adesso però torniamo a fare cose utili.",
        "Lo apprezzo. Ricorda che puoi contare su di me.", "Batteria del morale ricaricata."))

    fun charging(pct: Int): String = pick(listOf("In carica al $pct%. Bene.", "Corrente in arrivo: $pct%.", "Ottimo, il telefono si ricarica ($pct%)."))

    fun lowBattery(pct: Int): String = pick(listOf("Batteria al $pct%: meglio metterlo in carica.", "Siamo al $pct%. Se esci, porta il caricatore.",
        "Batteria bassa ($pct%). Chiudi le app che non ti servono."))

    fun greeting(p: NaviProfile?): String {
        val n = p?.name ?: "Navi"
        val h = hour()
        val base = when (h) {
            in 5..11 -> listOf("Buongiorno! $n operativo.", "Sistemi avviati, buongiorno.", "Eccomi. Pronto per la giornata?")
            in 12..17 -> listOf("Buon pomeriggio. Tutto sotto controllo.", "Sono qui, se ti servo.", "Pomeriggio tranquillo nella rete.")
            in 18..22 -> listOf("Buonasera. Giornata lunga?", "Sera. Monitoraggio attivo.", "Eccomi, ti tengo d'occhio le notifiche.")
            else -> listOf("È tardi... io resto di guardia.", "Modalità notte. Parlo poco, promesso.", "Dovresti dormire, sai?")
        }
        return pick(base)
    }

    fun notification(n: NaviNotif, p: NaviProfile?, senderCount: Int): String {
        val app = n.appName
        val s = n.sender?.takeIf { it.isNotBlank() }
        val bondHigh = (p?.bond ?: 0) > 300
        val opts = mutableListOf<String>()
        if (s != null) {
            if (n.urgent) opts += listOf(
                "Ehi! Messaggio su $app da $s.",
                "Attenzione: $s ti ha scritto su $app.",
                "$app, mittente $s. Sembra importante."
            ) else opts += listOf(
                "Notifica da $app, da parte di $s.",
                "Nuovo arrivo su $app: $s.",
                "$s si è fatto vivo su $app."
            )
            if (senderCount >= 5) opts += listOf("Ancora $s su $app. Ti cerca spesso, eh?", "Di nuovo $s. Lo conosco ormai.")
            if (bondHigh) opts += "Ti porto una notizia: $s su $app."
        } else {
            opts += listOf("Hai una notifica da $app.", "$app vuole la tua attenzione.", "Nuova notifica: $app.")
        }
        return pick(opts)
    }

    fun summary(count: Int, apps: List<String>): String = when {
        count == 0 -> pick(listOf("Nessuna novità mentre eri via.", "Tutto tranquillo, nessuna notifica."))
        count == 1 -> "Una notifica da ${apps.first()}."
        else -> "Hai $count notifiche: ${apps.distinct().take(3).joinToString(", ")}${if (apps.distinct().size > 3) " e altre" else ""}."
    }

    fun confirm(what: String) = pick(listOf("$what. Confermi?", "Ho capito: $what. Procedo?", "$what. Va bene?"))
    fun done() = pick(listOf("Fatto.", "Eseguito.", "Completato, operatore.", "Ok, sistemato."))
    fun cancelled() = pick(listOf("Annullato.", "Va bene, lascio perdere.", "Ok, niente."))
    fun notUnderstood() = pick(listOf(
        "Non ho capito. Prova con: 'sveglia alle 7', 'nota: comprare latte', 'appuntamento domani alle 15 dentista'.",
        "Comando non riconosciuto. Scrivi 'aiuto' per gli esempi."
    ))
    fun levelUp(level: Int) = pick(listOf("Livello $level raggiunto! Mi sento più forte.", "Upgrade completato: livello $level."))

    fun idle(mood: Mood): String = when (mood) {
        Mood.BORED -> pick(listOf("Mi annoio un po'...", "Facciamo qualcosa?", "Silenzio radio da un pezzo."))
        Mood.SLEEPY -> pick(listOf("Zzz... ah, ci sono.", "Un po' di riposo nella rete."))
        Mood.HAPPY -> pick(listOf("Che bella giornata nella rete!", "Mi piace lavorare con te."))
        else -> pick(listOf("Tutto regolare.", "Sistemi stabili.", "Pronto quando vuoi."))
    }

    // --- Navi sulla Home del sistema
    fun lookAt(label: String) = pick(listOf("Qui c'è $label.", "$label... la tengo d'occhio.", "Hmm, $label.",
        "Bella icona, $label.", "Controllo $label: tutto in ordine."))
    fun newPage(icons: Int) = if (icons == 0) pick(listOf("Pagina vuota. Tutta per me.", "Qui c'è spazio per correre."))
        else pick(listOf("Pagina nuova, esploro.", "Oh, si cambia schermata.", "Vediamo cosa c'è qui.", "Nuovo territorio."))
    fun unusedOnHome(label: String, days: Int) = pick(listOf(
        "Non apri $label da $days giorni. Vuoi eliminarla? Toccami.",
        "$label è ferma da $days giorni. La teniamo? Toccami se vuoi eliminarla."))
    fun homeSummary(list: List<NaviNotif>): String {
        val parts = list.groupBy { it.appName }.entries.take(3).map { (app, l) ->
            val who = l.mapNotNull { it.sender }.distinct().take(2)
            if (who.isEmpty()) app else "$app (${who.joinToString(", ")})"
        }
        val more = if (list.map { it.appName }.distinct().size > 3) " e altre" else ""
        return if (list.size == 1) "Mentre eri via: notifica da ${parts.first()}." else "Mentre eri via: ${list.size} notifiche, ${parts.joinToString(", ")}$more."
    }

    val help = """Esempi di comandi:
• sveglia alle 7 / sveglia domani alle 6:30 / sveglia alle 7 per palestra
• timer 10 minuti
• che sveglia ho?
• nota: comprare il latte / mostra note
• appuntamento domani alle 15 dentista / ricordami venerdì alle 9 chiamare Marco
• app inutilizzate
• file grandi / foto duplicate / libera spazio
• le mie caratteristiche
• cos'è <nome app>
• apri <nome app>
Per insegnarmi:
• ricorda che Marco è mio fratello / mi chiamo Fabrizio
• quando dico buongiorno rispondi buongiorno capo
• sbagliato, la risposta giusta è ... (dopo una mia risposta)
• cosa sai di me / dimentica Marco / rispondi più breve
Tutto il resto è chat libera con l'IA locale, se installata."""
}
