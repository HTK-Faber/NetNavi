package com.tuo.navi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tuo.navi.actions.ActionExecutor
import com.tuo.navi.actions.NotesStore
import com.tuo.navi.ai.AiRouter
import com.tuo.navi.ai.ChatTurn
import com.tuo.navi.brain.LearnParser
import com.tuo.navi.brain.LearnIntent
import com.tuo.navi.brain.MemoryStore
import com.tuo.navi.brain.NaviBrain
import com.tuo.navi.core.Leveling
import com.tuo.navi.core.NaviBus
import com.tuo.navi.core.NaviEvent
import com.tuo.navi.core.NotifLog
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.domain.Mood
import com.tuo.navi.habits.AppEntry
import com.tuo.navi.habits.AppRepo
import com.tuo.navi.habits.HabitSuggestion
import com.tuo.navi.habits.HabitTracker
import com.tuo.navi.habits.UnusedApp
import com.tuo.navi.nlp.CommandParser
import com.tuo.navi.nlp.NaviCommand
import com.tuo.navi.phrases.PhraseEngine
import com.tuo.navi.voice.NaviVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class NaviViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    val profile = ProfileStore.profile
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList()); val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()
    private val _chat = MutableStateFlow<List<ChatTurn>>(emptyList()); val chat: StateFlow<List<ChatTurn>> = _chat.asStateFlow()
    private val _line = MutableStateFlow<Pair<Int, String>?>(null); val line: StateFlow<Pair<Int, String>?> = _line.asStateFlow()
    private val _pending = MutableStateFlow<NaviCommand?>(null); val pending: StateFlow<NaviCommand?> = _pending.asStateFlow()
    private val _busy = MutableStateFlow(false); val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _suggestion = MutableStateFlow<HabitSuggestion?>(null); val suggestion: StateFlow<HabitSuggestion?> = _suggestion.asStateFlow()
    private val _unused = MutableStateFlow<List<UnusedApp>>(emptyList()); val unused: StateFlow<List<UnusedApp>> = _unused.asStateFlow()
    private val _nextAlarm = MutableStateFlow(""); val nextAlarm: StateFlow<String> = _nextAlarm.asStateFlow()
    /** Testo della risposta mentre l'IA locale la sta scrivendo. */
    private val _partial = MutableStateFlow<String?>(null); val partial: StateFlow<String?> = _partial.asStateFlow()
    private val _askForgetAll = MutableStateFlow(false); val askForgetAll: StateFlow<Boolean> = _askForgetAll.asStateFlow()
    private var awaitingCorrection: String? = null
    private var lastQuestion: String? = null
    val notes = NotesStore.notes

    private var lineCounter = 0
    private var lastVisible = System.currentTimeMillis()
    private var lastInteraction = System.currentTimeMillis()
    private val dismissedSuggestions = mutableSetOf<String>()

    init {
        NotesStore.load()
        MemoryStore.load()
        refreshApps()
        val nm = ProfileStore.profile.value?.name ?: "Navi"
        val who = MemoryStore.style.value.userName.let { if (it.isBlank()) "" else ", $it" }
        addTurn(false, "Ciao$who, sono $nm. Comandi: \"sveglia domani alle 7\", \"timer 10 minuti\", \"nota: comprare il latte\", \"ricordami venerdì alle 9 di chiamare Marco\". " +
            "Per insegnarmi qualcosa: \"ricorda che Marco è mio fratello\", \"quando dico buongiorno rispondi buongiorno capo\". Scrivi \"aiuto\" per l'elenco.")
        say("Ciao, sono $nm. Toccami per parlare, o usa i pulsanti qui sotto.", speak = false)
        viewModelScope.launch {
            NaviBus.events.collect { e ->
                when (e) {
                    is NaviEvent.Notification -> {
                        val p = ProfileStore.profile.value
                        if (e.n.urgent) ProfileStore.update { it.copy(mood = Mood.ALERT) }
                        say(PhraseEngine.notification(e.n, p, 0), speak = false)
                    }
                    is NaviEvent.Say -> say(e.text, e.speak)
                    NaviEvent.Summon -> say(PhraseEngine.greeting(ProfileStore.profile.value))
                }
            }
        }
    }

    fun refreshApps() = viewModelScope.launch { _apps.value = withContext(Dispatchers.IO) { AppRepo.load(ctx) } }

    /** Chiamato quando la home torna visibile: riassunto notifiche, sveglia, abitudini. */
    fun onHomeVisible() {
        NotifLog.homeVisible = true
        _nextAlarm.value = ActionExecutor.describeNextAlarm(ctx)
        updateMood()
        val missed = NotifLog.since(lastVisible)
        if (missed.isNotEmpty()) say(PhraseEngine.summary(missed.size, missed.map { it.appName }), speak = false)
        else if (System.currentTimeMillis() - lastVisible > 30 * 60_000L) say(PhraseEngine.greeting(ProfileStore.profile.value), speak = false)
        _suggestion.value = HabitTracker.suggestion(exclude = dismissedSuggestions)
    }

    fun onHomeHidden() { NotifLog.homeVisible = false; lastVisible = System.currentTimeMillis() }

    private fun updateMood() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val idle = System.currentTimeMillis() - lastInteraction
        val m = when {
            h >= 23 || h < 6 -> Mood.SLEEPY
            idle > 6 * 3600_000L -> Mood.BORED
            (ProfileStore.profile.value?.bond ?: 0) > 200 -> Mood.HAPPY
            else -> Mood.CALM
        }
        ProfileStore.update { it.copy(mood = m) }
    }

    fun say(text: String, speak: Boolean = true) {
        _line.value = (++lineCounter) to text
        if (speak) NaviVoice.say(text)
    }

    private fun addTurn(user: Boolean, text: String, engine: String? = null, question: String? = null) {
        _chat.value = (_chat.value + ChatTurn(user, text, engine, question)).takeLast(40)
    }

    fun gainExpPublic(n: Int) = gainExp(n)
    private fun gainExp(n: Int) {
        val before = ProfileStore.profile.value?.level ?: 1
        ProfileStore.addExp(n)
        val after = ProfileStore.profile.value?.level ?: 1
        if (after > before) reply(PhraseEngine.levelUp(after) + if (Leveling.isDevBuild) " (livello reale: la build di sviluppo resta al ${Leveling.devLevel})." else "")
    }

    private fun reply(text: String) { addTurn(false, text); say(text) }

    fun send(raw: String) {
        val text = raw.trim(); if (text.isEmpty()) return
        lastInteraction = System.currentTimeMillis()
        addTurn(true, text)
        val norm = CommandParser.normalize(text)
        if (_pending.value != null && norm in setOf("si", "sì", "ok", "conferma", "vai", "certo")) { confirm(true); return }
        if (_pending.value != null && norm in setOf("no", "annulla", "lascia stare")) { confirm(false); return }
        if (_askForgetAll.value) { forgetAll(norm in setOf("si", "sì", "ok", "conferma", "vai", "certo")); return }

        lateNightNote()
        awaitingTopic?.let { t ->
            awaitingTopic = null
            when {
                norm in skipWords -> { com.tuo.navi.brain.Interview.skip(t); reply("Va bene, te lo chiedo un'altra volta."); return }
                stopAsk.containsMatchIn(norm) -> { com.tuo.navi.brain.Interview.stop(); reply("D'accordo, smetto di farti domande. Se cambi idea scrivi \"fammi una domanda\"."); return }
                !looksLikeCommand(text, norm) -> {
                    addTurn(false, com.tuo.navi.brain.Interview.answer(t, text), "memoria"); gainExp(Leveling.Exp.LEARNED_FACT); return
                }
                else -> com.tuo.navi.brain.Interview.skip(t)
            }
        }
        if (stopAsk.containsMatchIn(norm)) { com.tuo.navi.brain.Interview.stop(); reply("D'accordo, niente più domande. Se cambi idea scrivi \"fammi una domanda\"."); return }
        if (askMe.containsMatchIn(norm) && norm.length < 40) {
            val t = com.tuo.navi.brain.Interview.questionNow(force = true)
            if (t == null) reply("Per ora mi hai già raccontato tutto quello che volevo sapere. Più avanti avrò nuove domande.")
            else { awaitingTopic = t; addTurn(false, t.question, "domanda"); say(t.question) }
            return
        }
        if (callsCmd.containsMatchIn(norm) && norm.length < 60) {
            reply("Apro Chiamate moleste: lì trovi i numeri che ti propongo di bloccare, la black list e la tua segreteria.")
            _navTo.value = Screen.CALLS; return
        }
        if (securityCmd.containsMatchIn(norm) && norm.length < 60) {
            reply("Apro Controllo sistema e avvio il controllo. Resta tutto sul telefono.")
            autoScan = true; _navTo.value = Screen.SECURITY; return
        }
        searchCmd.find(text.trim())?.let { m -> webSearch(m.groupValues[3].trim().trimEnd('?', '.')); return }

        if (filesCmd.containsMatchIn(norm)) { reply("Apro Foto e video pesanti: lì puoi filtrare, inviare a Drive o Dropbox, oppure eliminare."); _navTo.value = Screen.FILES; return }
        if (traitsCmd.containsMatchIn(norm) && norm.length < 40) { reply("Ecco le mie caratteristiche."); _navTo.value = Screen.TRAITS; return }

        // 1) il Navi impara (ricorda che..., quando dico X rispondi Y, correzioni, dimentica...)
        val corr = awaitingCorrection; awaitingCorrection = null
        NaviBrain.learn(text, lastQuestion, corr)?.let { out ->
            if (out.askConfirmForgetAll) _askForgetAll.value = true
            var r = out.reply
            val intent = LearnParser.parse(text)
            if (intent is LearnIntent.Remember && !intent.implicit && CommandParser.parseTime(norm) != null)
                r += " Se vuoi anche un avviso all'ora giusta scrivi \"ricordami ...\"."
            if (r.isNotBlank()) { if (out.continueChat) addTurn(false, r, "memoria") else reply(r) }
            if (out.exp > 0) gainExp(out.exp)
            if (!out.continueChat) return
        }
        // 2) comandi e chat
        viewModelScope.launch { handle(CommandParser.parse(text)) }
    }

    private suspend fun handle(cmd: NaviCommand) {
        when (cmd) {
            is NaviCommand.SetAlarm -> {
                if (cmd.dayOffset >= 2) {
                    val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, cmd.dayOffset); set(Calendar.HOUR_OF_DAY, cmd.hour); set(Calendar.MINUTE, cmd.minute); set(Calendar.SECOND, 0) }
                    val ev = NaviCommand.AddEvent(c.timeInMillis, c.timeInMillis + 15 * 60_000L, cmd.label ?: "Sveglia", false)
                    _pending.value = ev
                    reply("L'Orologio accetta solo la prossima occorrenza di un orario. Ti creo invece un promemoria in calendario: ${ev.describe()}. Confermi?")
                    return
                }
                val now = Calendar.getInstance()
                val todayStillAhead = cmd.hour * 60 + cmd.minute > now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                val warn = if (cmd.dayOffset == 1 && todayStillAhead) " Attenzione: l'Orologio la farà suonare alla prossima occorrenza, cioè già oggi." else ""
                _pending.value = cmd; reply(PhraseEngine.confirm(cmd.describe()) + warn)
            }
            is NaviCommand.Timer, is NaviCommand.AddEvent, is NaviCommand.AddNote -> { _pending.value = cmd; reply(PhraseEngine.confirm(cmd.describe())) }
            NaviCommand.ShowNotes -> {
                val n = NotesStore.notes.value
                reply(if (n.isEmpty()) "Non hai note." else "Ultime note:\n" + n.take(5).joinToString("\n") { "• " + it.text })
            }
            NaviCommand.NextAlarm -> reply(ActionExecutor.describeNextAlarm(ctx))
            NaviCommand.ShowAlarms -> ActionExecutor.showAlarms(ctx).onFailure { reply(it.message ?: "Errore") }
            NaviCommand.Help -> reply(PhraseEngine.help)
            NaviCommand.Status -> {
                val p = ProfileStore.profile.value
                if (p != null) {
                    val lv = Leveling.effective(p); val s = p.stats(lv)
                    val dev = if (Leveling.isDevBuild) " Build di sviluppo: livello $lv, livello reale ${p.level} (${p.exp}/${p.expToNext} EXP)." else " ${p.exp}/${p.expToNext} EXP."
                    reply("${p.name}, ${p.cls.label} LV $lv.$dev HP ${s.hp}, ATK ${s.atk}, DEF ${s.def}, SPD ${s.spd}. Umore: ${p.mood.label}. IA: ${AiRouter.mode()}. Ricordi: ${MemoryStore.count()}.")
                }
            }
            NaviCommand.UnusedApps -> checkUnused(verbose = true)
            is NaviCommand.WhatIsApp -> {
                val a = AppRepo.find(_apps.value, cmd.name)
                if (a == null) reply("Non trovo un'app chiamata \"${cmd.name}\".") else explainApp(a)
            }
            is NaviCommand.OpenApp -> {
                val a = AppRepo.find(_apps.value, cmd.name)
                if (a == null) reply("Non trovo \"${cmd.name}\".") else { launch(a); say("Apro ${a.label}.", speak = false) }
            }
            is NaviCommand.OpenWebSearch -> { _pending.value = cmd; reply(PhraseEngine.confirm(cmd.describe())) }
            is NaviCommand.Chat -> {
                _busy.value = true; _partial.value = null
                lastQuestion = cmd.text
                val hist = _chat.value.dropLast(1).filter { it.engine != "memoria" || it.user }
                val r = runCatching { AiRouter.chat(hist, cmd.text) { part -> _partial.value = part } }
                    .getOrElse { com.tuo.navi.ai.AiReply("Errore: ${it.message}", "errore") }
                _busy.value = false; _partial.value = null
                addTurn(false, r.text, r.engine, if (r.engine != "offline" && r.engine != "errore") cmd.text else null)
                say(r.text); gainExp(Leveling.Exp.CHAT)
            }
        }
    }

    /** Azione dai pulsanti rapidi: il modulo compilato vale già come conferma. */
    fun runDirect(cmd: NaviCommand) {
        addTurn(true, cmd.describe())
        if (cmd is NaviCommand.SetAlarm || cmd is NaviCommand.Timer || cmd is NaviCommand.AddEvent || cmd is NaviCommand.AddNote) {
            _pending.value = cmd; confirm(true)
        } else viewModelScope.launch { handle(cmd) }
    }

    fun confirm(yes: Boolean) {
        val cmd = _pending.value ?: return
        _pending.value = null
        if (!yes) { reply(PhraseEngine.cancelled()); return }
        val r: Result<Unit> = when (cmd) {
            is NaviCommand.SetAlarm -> ActionExecutor.setAlarm(ctx, cmd.hour, cmd.minute, cmd.label)
            is NaviCommand.Timer -> ActionExecutor.setTimer(ctx, cmd.seconds, cmd.label)
            is NaviCommand.AddEvent -> ActionExecutor.addEvent(ctx, cmd.startMs, cmd.endMs, cmd.title, cmd.allDay)
            is NaviCommand.AddNote -> runCatching { NotesStore.add(cmd.text); Unit }
            is NaviCommand.OpenWebSearch -> runCatching {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(com.tuo.navi.net.WebSearch.browserUrl(cmd.query)))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); Unit }
            else -> Result.success(Unit)
        }
        r.onSuccess {
            reply(if (cmd is NaviCommand.AddEvent) "Ti apro il calendario: controlla e salva." else PhraseEngine.done())
            if (cmd is NaviCommand.SetAlarm) shortSleepNote(cmd.hour, cmd.minute)?.let { addTurn(false, it) }
            ProfileStore.update { it.copy(mood = Mood.HAPPY) }; gainExp(Leveling.Exp.COMMAND)
            val seen = when (cmd) {
                is NaviCommand.SetAlarm -> NaviBrain.observedAlarm(cmd.hour, cmd.minute)
                is NaviCommand.Timer -> NaviBrain.observedTimer(cmd.seconds)
                is NaviCommand.AddEvent -> NaviBrain.observedEvent(cmd.title)
                else -> null
            }
            seen?.let { addTurn(false, "Ho notato una cosa e me la segno: ${it.lowercase()}.", "memoria"); gainExp(Leveling.Exp.AUTO_FACT) }
            viewModelScope.launch { kotlinx.coroutines.delay(1500); _nextAlarm.value = ActionExecutor.describeNextAlarm(ctx) }
        }.onFailure { reply("Non ci sono riuscito: ${it.message}") }
    }

    /** Pulsanti Giusta / Sbagliata sotto le risposte del Navi. */
    fun rate(turnId: Long, good: Boolean) {
        val t = _chat.value.firstOrNull { it.id == turnId } ?: return
        if (t.rated || t.question == null) return
        _chat.value = _chat.value.map { if (it.id == turnId) it.copy(rated = true) else it }
        if (good) { NaviBrain.approve(t.question, t.text); addTurn(false, "Bene, terrò questa risposta come esempio.", "memoria") }
        else { awaitingCorrection = t.question; addTurn(false, "Dimmi tu la risposta giusta: la ricorderò per la prossima volta.", "memoria") }
        gainExp(Leveling.Exp.FEEDBACK)
    }

    fun forgetAll(yes: Boolean) {
        _askForgetAll.value = false
        if (yes) { MemoryStore.clear(); com.tuo.navi.brain.Interview.reset(); reply("Fatto. Ho dimenticato tutto quello che mi avevi insegnato.") } else reply("Va bene, non dimentico niente.")
    }

    fun launch(a: AppEntry) {
        HabitTracker.recordLaunch(a.pkg)
        ActionExecutor.openApp(ctx, a.pkg).onFailure { say(it.message ?: "Errore") }
        lastInteraction = System.currentTimeMillis()
    }

    fun acceptSuggestion() {
        val s = _suggestion.value ?: return
        _apps.value.firstOrNull { it.pkg == s.pkg }?.let { launch(it); gainExp(Leveling.Exp.HABIT_ACCEPTED) }
        _suggestion.value = null
    }
    fun dismissSuggestion() { _suggestion.value?.let { dismissedSuggestions += it.pkg }; _suggestion.value = null }

    fun explainApp(a: AppEntry) = viewModelScope.launch {
        say("Analizzo ${a.label}...", speak = false); _busy.value = true
        val r = AiRouter.explainApp(a.label, a.pkg, a.category)
        _busy.value = false; addTurn(false, r); say(r)
    }

    fun checkUnused(verbose: Boolean) = viewModelScope.launch {
        if (!HabitTracker.hasUsageAccess(ctx)) { if (verbose) reply("Mi serve l'accesso ai dati di utilizzo: attivalo dalle Impostazioni del Navi."); return@launch }
        val list = withContext(Dispatchers.IO) { HabitTracker.unusedApps(ctx) }
        _unused.value = list
        if (verbose) reply(if (list.isEmpty()) "Nessuna app inutilizzata da almeno 60 giorni." else
            "Non usi queste app da molto: " + list.take(5).joinToString(", ") { "${it.label} (${it.daysUnused} gg)" } + ". Controlla bene prima di eliminarle: alcune servono di rado ma sono importanti.")
    }

    fun uninstall(pkg: String) { ActionExecutor.requestUninstall(ctx, pkg); _unused.value = _unused.value.filterNot { it.pkg == pkg } }
    fun keepApp(pkg: String) {
        SettingsStore.neverAskUninstall = SettingsStore.neverAskUninstall + pkg
        _unused.value = _unused.value.filterNot { it.pkg == pkg }
    }
    /** "Ricordamelo tra 3 giorni": l'app sparisce dall'elenco e torna dopo 3 giorni se ancora inutilizzata. */
    fun remindLater(pkg: String, days: Int = 3) {
        SettingsStore.snoozeApp(pkg, days)
        _unused.value = _unused.value.filterNot { it.pkg == pkg }
    }

    /** Richieste di apertura di una sezione (es. comando "file grandi"). */
    private val _navTo = MutableStateFlow<Screen?>(null); val navTo: StateFlow<Screen?> = _navTo.asStateFlow()
    fun navHandled() { _navTo.value = null }
    private val filesCmd = Regex("(file|foto|video)\\s+(grandi|grossi|pesanti|duplicat\\w*|doppi\\w*|in serie|simili)|libera(re)?\\s+spazio|spazio\\s+(occupato|pieno)|memoria\\s+piena", RegexOption.IGNORE_CASE)
    private val traitsCmd = Regex("caratteristiche|scheda (del )?personaggio|status gdr", RegexOption.IGNORE_CASE)

    // --- Domande per conoscerti (max 2 al giorno, a 3 ore di distanza) ---
    private var awaitingTopic: com.tuo.navi.brain.Topic? = null
    private val skipWords = setOf("salta", "passo", "non ora", "dopo", "piu tardi", "un' altra volta", "un altra volta", "preferisco non rispondere", "non lo so")
    private val stopAsk = Regex("non chiedermel\\w* piu|basta (con le )?domande|smetti(la)? di fare domande|niente (piu )?domande")
    private val askMe = Regex("fammi una domanda|conoscimi|chiedimi qualcosa|fammi delle domande")

    /** Apertura della chat: se è il momento, il Navi fa una domanda. */
    fun maybeAsk() {
        if (awaitingTopic != null || _pending.value != null || _busy.value || _askForgetAll.value) return
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { com.tuo.navi.brain.Interview.questionNow() } ?: return@launch
            awaitingTopic = t
            addTurn(false, t.question + " (Se non ti va scrivi \"salta\".)", "domanda")
        }
    }

    private fun looksLikeCommand(text: String, norm: String): Boolean =
        CommandParser.parse(text) !is NaviCommand.Chat || searchCmd.containsMatchIn(text.trim()) || securityCmd.containsMatchIn(norm) || callsCmd.containsMatchIn(norm) ||
            filesCmd.containsMatchIn(norm) || norm.startsWith("ricorda") || norm.startsWith("dimentica") || norm.startsWith("quando dico")

    // --- Commenti costruttivi (non sempre accondiscendente) ---
    private fun today() = System.currentTimeMillis() / 86_400_000L
    private fun lateNightNote() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (h !in 1..4 || SettingsStore.lateNoteDay == today()) return
        SettingsStore.lateNoteDay = today()
        addTurn(false, "Sono le $h passate. Ti aiuto volentieri, ma da amico te lo dico: dormire poco ti toglie lucidità domani. Chiudiamo questa cosa e poi a letto?")
    }
    /** Sveglia impostata di notte che lascia meno di 6 ore di sonno. */
    private fun shortSleepNote(hour: Int, minute: Int): String? {
        val now = Calendar.getInstance(); val h = now.get(Calendar.HOUR_OF_DAY)
        if (h in 5..21) return null
        val target = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_MONTH, 1) }
        val hrs = (target.timeInMillis - now.timeInMillis) / 3_600_000.0
        return if (hrs < 6) "Fatto, ma così dormi circa ${"%.1f".format(hrs).replace('.', ',')} ore. Se domani è importante, prova ad andare a letto adesso." else null
    }

    // --- Controllo del sistema ---
    private var autoScan = false
    private val callsCmd = Regex("chiamat\\w* (moleste|spam|indesiderat\\w*)|black ?list|numer\\w* bloccat\\w*|blocca\\w* (un |il |questo )?numer\\w*|segreteria|call center|telemarketing")
    private val securityCmd = Regex("controll\\w* (il |del )?(sistema|telefono|cellulare)|scansion\\w*|antivirus|\\bvirus\\b|malware|spyware|app spia|stalkerware")
    fun consumeAutoScan(): Boolean = autoScan.also { autoScan = false }

    // --- Ricerca su Internet ---
    private val searchCmd = Regex("^(cerca|cercami|ricerca|cerca mi)( su internet| sul web| online| in rete| in internet| su google)?[:,]?\\s+(.+)$", RegexOption.IGNORE_CASE)
    private fun webSearch(q: String) {
        if (q.isBlank()) { reply("Cosa vuoi che cerchi?"); return }
        if (!SettingsStore.internetAllowed) {
            _pending.value = NaviCommand.OpenWebSearch(q)
            reply("Internet è spento nel Navi, quindi non cerco da qui. Vuoi che apra la ricerca \"$q\" nel browser del telefono? Uscirebbe solo la frase cercata.")
            return
        }
        if (!SettingsStore.searchWarned) {
            SettingsStore.searchWarned = true
            addTurn(false, "Nota sulla privacy: per cercare invio a DuckDuckGo e Wikipedia solo la frase che scrivi, nient'altro. I risultati restano qui e non li uso per addestrare nessuno.", "memoria")
        }
        viewModelScope.launch {
            _busy.value = true
            val r = withContext(Dispatchers.IO) { runCatching { com.tuo.navi.net.WebSearch.search(q) } }
            _busy.value = false
            r.onFailure { reply("La ricerca non è riuscita (${it.message}). Controlla la connessione.") }
            r.onSuccess { o ->
                when {
                    o.hits.isEmpty() && o.blocked -> { _pending.value = NaviCommand.OpenWebSearch(q); reply("DuckDuckGo mi chiede una verifica anti-robot. Apro la ricerca nel browser?") }
                    o.hits.isEmpty() -> reply("Non ho trovato niente per \"$q\". Prova con parole diverse.")
                    else -> {
                        val good = o.hits.count { it.trust == com.tuo.navi.net.Trust.HIGH }
                        val txt = "Ecco i risultati più attendibili per \"$q\"" + (if (o.adsRemoved > 0) ", senza ${o.adsRemoved} annunci" else "") + ". " +
                            (if (good > 0) "In verde le fonti autorevoli." else "Nessuna fonte autorevole tra questi: verifica bene prima di fidarti.") + " Tocca un risultato per aprirlo."
                        _chat.value = (_chat.value + ChatTurn(false, txt, "ricerca", links = o.hits)).takeLast(40)
                        say("Ho trovato ${o.hits.size} risultati.", speak = false); gainExp(Leveling.Exp.CHAT)
                    }
                }
            }
        }
    }

    fun mute(pkg: String, label: String) {
        SettingsStore.mutedPackages = SettingsStore.mutedPackages + pkg; say("Non ti avviserò più per $label.", speak = false)
    }
}
