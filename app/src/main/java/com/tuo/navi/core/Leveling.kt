package com.tuo.navi.core

import com.tuo.navi.BuildConfig
import com.tuo.navi.domain.NaviProfile

/**
 * Abilità del Navi sbloccate dal livello (sviluppo da gioco di ruolo).
 * Le soglie sono PROVVISORIE: in questa build di sviluppo il Navi è al livello 100 e tutto è sbloccato.
 */
enum class Ability(val minLevel: Int, val title: String, val desc: String) {
    NOTIFY(1, "Avvisi notifiche", "Ti avvisa con un fumetto: quale app e chi scrive. Non legge mai i messaggi."),
    COMMANDS(1, "Comandi", "Sveglie, timer, note e appuntamenti in calendario."),
    HOME_WALK(3, "Passeggiata sulla Home", "Gira tra le pagine della Home, sale sulle icone, salta."),
    APP_LOOK(5, "Osserva le app", "Si ferma sulle icone e le commenta."),
    MEMORY(8, "Memoria", "Ricorda quello che gli insegni: \"ricorda che...\", \"mi chiamo...\", \"Marco è mio fratello\"."),
    UNUSED_APPS(12, "App inutilizzate", "Ti segnala le app ferme da molto e ti propone di eliminarle."),
    TAUGHT_REPLIES(15, "Risposte insegnate", "\"Quando dico X rispondi Y\": impara risposte tue."),
    HABITS(20, "Abitudini", "Suggerisce l'app che di solito apri a quell'ora."),
    CORRECTIONS(25, "Impara dagli errori", "Pulsanti Giusta/Sbagliata: ricorda le risposte approvate e le correzioni."),
    AUTO_LEARN(30, "Auto-apprendimento", "Impara da solo dai comandi: orari delle sveglie, persone negli appuntamenti."),
    STYLE(40, "Stile personale", "Come ti chiama, risposte brevi o lunghe."),
    BATTLE_ART(50, "Pixel art da combattimento", "Puoi caricare le righe di battaglia (8-14)."),
    EXTRA_SPRITES(60, "Sprite extra", "Puoi caricare il teletrasporto e le animazioni della vita quotidiana (righe 16-24)."),
    BIG_MODELS(70, "Modelli IA più grandi", "Può usare modelli oltre quello consigliato per il tuo telefono."),
    IN_APP_DOWNLOAD(80, "Download modelli dall'app", "Scarica i modelli da Hugging Face senza browser (serve l'interruttore Internet)."),
    FULL(100, "Navi completo", "Tutte le abilità attive.");

    val unlocked: Boolean get() = Leveling.level() >= minLevel
}

object Leveling {
    const val MAX_LEVEL = 100

    /** Build di sviluppo: livello forzato (100). In una build normale vale 0 e conta il livello reale. */
    val devLevel: Int get() = BuildConfig.NAVI_DEV_LEVEL
    val isDevBuild: Boolean get() = devLevel > 0

    fun effective(p: NaviProfile?): Int = maxOf(p?.level ?: 1, devLevel).coerceAtMost(MAX_LEVEL)
    fun level(): Int = effective(com.tuo.navi.data.ProfileStore.profile.value)
    fun has(a: Ability) = a.unlocked

    /** Quanta EXP vale ogni azione: il livello cresce soprattutto quando il Navi impara o lavora davvero. */
    object Exp {
        const val CHAT = 1
        const val COMMAND = 3
        const val LEARNED_FACT = 5
        const val TAUGHT_REPLY = 4
        const val CORRECTION = 4
        const val FEEDBACK = 2
        const val AUTO_FACT = 2
        const val HABIT_ACCEPTED = 2
    }

    val expTable = listOf(
        "Chat" to Exp.CHAT, "Comando eseguito" to Exp.COMMAND, "Fatto imparato" to Exp.LEARNED_FACT,
        "Risposta insegnata" to Exp.TAUGHT_REPLY, "Correzione" to Exp.CORRECTION, "Giusta/Sbagliata" to Exp.FEEDBACK,
        "Imparato da solo" to Exp.AUTO_FACT, "Suggerimento accettato" to Exp.HABIT_ACCEPTED
    )
}
