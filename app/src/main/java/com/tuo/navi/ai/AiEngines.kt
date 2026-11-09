package com.tuo.navi.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.tuo.navi.brain.LearnParser
import com.tuo.navi.brain.MemoryStore
import com.tuo.navi.brain.NaviBrain
import com.tuo.navi.core.Leveling
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.domain.NaviProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** id = identificativo stabile del turno, per i pulsanti Giusta/Sbagliata. engine = chi ha risposto. */
data class ChatTurn(val user: Boolean, val text: String, val engine: String? = null, val question: String? = null,
                    val id: Long = System.nanoTime(), val rated: Boolean = false,
                    val links: List<com.tuo.navi.net.WebHit> = emptyList())

data class AiReply(val text: String, val engine: String, val learnable: Boolean = false)

sealed class LlmState(val label: String) {
    object Missing : LlmState("nessun modello")
    object Idle : LlmState("pronto da caricare")
    object Loading : LlmState("caricamento del modello...")
    object Ready : LlmState("caricato")
    object Busy : LlmState("sta pensando...")
    class Error(val msg: String) : LlmState("errore: $msg")
}

/** Formato della conversazione: ogni famiglia di modelli si aspetta marcatori diversi. */
enum class PromptFormat(val label: String) { AUTO("Automatico"), GEMMA("Gemma"), CHATML("Qwen / ChatML"), LLAMA3("Llama 3"), ZEPHYR("TinyLlama / Zephyr"), PHI("Phi") }

/** Controllo del file prima di installarlo (funzione pura). Restituisce null se va bene, altrimenti il motivo. */
object ModelCheck {
    fun check(header: ByteArray, name: String, size: Long): String? {
        val h = header
        fun startsWith(s: String, off: Int = 0) = h.size >= off + s.length && String(h, off, s.length, Charsets.ISO_8859_1) == s
        val pk = (0..4).any { off -> h.size >= off + 4 && h[off] == 'P'.code.toByte() && h[off + 1] == 'K'.code.toByte() && h[off + 2] == 3.toByte() && h[off + 3] == 4.toByte() }
        return when {
            startsWith("GGUF") -> "è un file GGUF (per llama.cpp / LM Studio): questa app usa MediaPipe. Scarica la versione .task da huggingface.co/litert-community."
            startsWith("LITERTLM") -> "è un file .litertlm (nuovo formato LiteRT-LM), non ancora supportato. Nella stessa pagina scegli il file che finisce con .task."
            startsWith("<!") || startsWith("<h") || startsWith("{\"") -> "è una pagina web o un messaggio di errore, non il modello. Su Hugging Face per Gemma serve accedere e accettare la licenza prima di scaricare."
            h.size >= 9 && h[8] == '{'.code.toByte() && name.endsWith(".safetensors") -> "è un file .safetensors (pesi per PC), non utilizzabile sul telefono. Serve la versione .task."
            name.endsWith(".bin") && !pk -> "è un file .bin generico: serve la versione .task da litert-community."
            !pk -> "il file non è un pacchetto .task di MediaPipe."
            size < 50_000_000 -> "il file è troppo piccolo (${size / (1 shl 20)} MB): forse il download si è interrotto."
            else -> null
        }
    }

    fun formatFor(name: String): PromptFormat {
        val n = name.lowercase()
        return when {
            "gemma" in n -> PromptFormat.GEMMA
            "qwen" in n || "smol" in n || "deepseek" in n -> PromptFormat.CHATML
            "tinyllama" in n -> PromptFormat.ZEPHYR
            "llama" in n -> PromptFormat.LLAMA3
            "phi" in n -> PromptFormat.PHI
            else -> PromptFormat.GEMMA
        }
    }
}

/** Costruzione del prompt nel formato del modello. */
object PromptBuilder {
    fun build(f: PromptFormat, system: String, history: List<ChatTurn>, user: String): String {
        val turns = history.takeLast(4).map { it.user to it.text.take(220) } + (true to user)
        return when (f) {
            PromptFormat.GEMMA, PromptFormat.AUTO -> buildString {
                turns.forEachIndexed { i, (u, t) ->
                    append(if (u) "<start_of_turn>user\n" else "<start_of_turn>model\n")
                    if (i == 0) append(system).append("\n\n")
                    append(t).append("<end_of_turn>\n")
                }
                append("<start_of_turn>model\n")
            }
            PromptFormat.CHATML -> buildString {
                append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
                turns.forEach { (u, t) -> append(if (u) "<|im_start|>user\n" else "<|im_start|>assistant\n").append(t).append("<|im_end|>\n") }
                append("<|im_start|>assistant\n")
            }
            PromptFormat.LLAMA3 -> buildString {
                append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n").append(system).append("<|eot_id|>")
                turns.forEach { (u, t) -> append("<|start_header_id|>").append(if (u) "user" else "assistant").append("<|end_header_id|>\n\n").append(t).append("<|eot_id|>") }
                append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            PromptFormat.ZEPHYR -> buildString {
                append("<|system|>\n").append(system).append("</s>\n")
                turns.forEach { (u, t) -> append(if (u) "<|user|>\n" else "<|assistant|>\n").append(t).append("</s>\n") }
                append("<|assistant|>\n")
            }
            PromptFormat.PHI -> buildString {
                append("<|system|>\n").append(system).append("<|end|>\n")
                turns.forEach { (u, t) -> append(if (u) "<|user|>\n" else "<|assistant|>\n").append(t).append("<|end|>\n") }
                append("<|assistant|>\n")
            }
        }
    }

    private val stops = listOf("<end_of_turn>", "<start_of_turn>", "<|im_end|>", "<|im_start|>", "<|eot_id|>", "</s>", "<|end|>", "<|user|>",
        "\nUtente:", "\nUser:", "\nuser\n")

    fun clean(raw: String): String {
        var s = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        stops.forEach { st -> val i = s.indexOf(st); if (i >= 0) s = s.substring(0, i) }
        return s.replace(Regex("<[^>]{1,40}>"), "").replace("**", "").trim().take(700)
    }
}

/**
 * IA locale con MediaPipe LLM Inference (solo CPU, tutto sul telefono).
 * Il modello si carica in background quando apri la chat e si libera poco dopo aver chiuso il pannello,
 * così non toglie memoria al Navi sulla Home.
 */
object LocalLlm {
    private var engine: LlmInference? = null
    private val mutex = Mutex()
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var releaseJob: Job? = null
    private val _state = MutableStateFlow<LlmState>(LlmState.Missing)
    val state: StateFlow<LlmState> get() = _state
    var lastLoadMs = 0L; private set

    fun init(ctx: Context) { app = ctx.applicationContext; _state.value = if (installed()) LlmState.Idle else LlmState.Missing }

    val modelFile: File get() = File(app.filesDir, "models/navi-model.task")
    fun installed() = modelFile.exists() && modelFile.length() > 50_000_000
    val displayName: String get() = SettingsStore.modelName.ifBlank { if (installed()) "modello .task" else "-" }
    val format: PromptFormat get() = runCatching { PromptFormat.valueOf(SettingsStore.modelFormat) }.getOrDefault(PromptFormat.AUTO)
        .let { if (it == PromptFormat.AUTO) ModelCheck.formatFor(SettingsStore.modelName) else it }

    fun availMb(): Long {
        val mi = ActivityManager.MemoryInfo(); app.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.availMem / (1 shl 20)
    }
    /** Il modello è mappato in memoria: serve circa metà della sua dimensione più un margine. */
    fun neededMb(): Long = modelFile.length() / (1 shl 20) / 2 + 250

    fun displayNameOf(uri: Uri): String = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "modello"

    /** Copia un file scelto dal telefono, lo controlla e lo installa. */
    suspend fun importModel(uri: Uri, onProgress: (Long) -> Unit): String = withContext(Dispatchers.IO) {
        val name = displayNameOf(uri)
        val tmp = File(modelFile.path + ".tmp"); modelFile.parentFile?.mkdirs()
        app.contentResolver.openInputStream(uri)!!.use { inp ->
            val head = ByteArray(16); val n = inp.read(head)
            ModelCheck.check(head.copyOf(maxOf(n, 0)), name.lowercase(), Long.MAX_VALUE)?.let { error("Questo file non va bene: $it") }
            tmp.outputStream().use { out ->
                out.write(head, 0, n); var tot = n.toLong(); val buf = ByteArray(1 shl 16)
                while (true) { val r = inp.read(buf); if (r < 0) break; out.write(buf, 0, r); tot += r; if (tot % (16 shl 20) < buf.size) onProgress(tot) }
            }
        }
        install(tmp, name)
        name
    }

    /** Ultimo controllo e sostituzione del modello (usato da importazione e download). */
    fun install(tmp: File, name: String) {
        val head = tmp.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        ModelCheck.check(head, name.lowercase(), tmp.length())?.let { tmp.delete(); error("Questo file non va bene: $it") }
        release()
        modelFile.delete(); check(tmp.renameTo(modelFile)) { "Impossibile salvare il modello" }
        SettingsStore.modelName = name; SettingsStore.modelFormat = PromptFormat.AUTO.name
        _state.value = LlmState.Idle
    }

    fun delete() { release(); modelFile.delete(); SettingsStore.modelName = ""; _state.value = LlmState.Missing }

    fun release() {
        releaseJob?.cancel()
        runCatching { engine?.close() }; engine = null
        if (_state.value !is LlmState.Error) _state.value = if (installed()) LlmState.Idle else LlmState.Missing
    }

    /** Libera il modello dopo un po' che il pannello è chiuso. */
    fun scheduleRelease(afterMs: Long = 90_000) { releaseJob?.cancel(); releaseJob = scope.launch { delay(afterMs); mutex.withLock { release() } } }
    fun cancelRelease() { releaseJob?.cancel() }

    /** Carica il modello in background (all'apertura della chat). */
    fun warmUp() {
        if (!SettingsStore.useLocalModel || !installed() || engine != null) return
        cancelRelease()
        scope.launch { runCatching { mutex.withLock { ensureEngine() } } }
    }

    private fun ensureEngine(): LlmInference {
        engine?.let { return it }
        if (!installed()) { _state.value = LlmState.Missing; error("Nessun modello installato") }
        val free = availMb(); val need = neededMb()
        if (free < need) {
            val m = "memoria libera $free MB, ne servono circa $need. Chiudi qualche app o usa un modello più piccolo."
            _state.value = LlmState.Error(m); error(m)
        }
        _state.value = LlmState.Loading
        val t0 = System.currentTimeMillis()
        return try {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .setMaxTopK(40)
                .build()
            LlmInference.createFromOptions(app, opts).also { engine = it; lastLoadMs = System.currentTimeMillis() - t0; _state.value = LlmState.Ready }
        } catch (t: Throwable) {
            val m = "il modello non si avvia (${t.message?.take(160) ?: t.javaClass.simpleName}). Probabilmente non è compatibile con MediaPipe: prova Gemma 3 1B int4 o Qwen 2.5 0.5B in formato .task."
            _state.value = LlmState.Error(m); throw IllegalStateException(m, t)
        }
    }

    const val MAX_TOKENS = 768

    /** Genera una risposta; onPartial riceve il testo man mano che arriva. */
    suspend fun generate(prompt: String, onPartial: ((String) -> Unit)? = null): String = mutex.withLock {
        cancelRelease()
        val eng = withContext(Dispatchers.Default) { ensureEngine() }
        _state.value = LlmState.Busy
        try {
            withContext(Dispatchers.Default) {
                val tokens = runCatching { eng.sizeInTokens(prompt) }.getOrDefault(prompt.length / 3)
                check(tokens < MAX_TOKENS - 96) { "domanda troppo lunga per il modello ($tokens token)" }
                val sb = StringBuilder()
                suspendCancellableCoroutine<String> { cont ->
                    val fut = eng.generateResponseAsync(prompt) { partial, done ->
                        if (partial != null) { sb.append(partial); onPartial?.invoke(PromptBuilder.clean(sb.toString())) }
                        if (done && cont.isActive) cont.resume(sb.toString())
                    }
                    fut.addListener({ runCatching { fut.get() }.onFailure { if (cont.isActive) cont.resumeWithException(it) } }, Runnable::run)
                }
            }
        } finally { if (engine != null) _state.value = LlmState.Ready }
    }
}

/** Sceglie chi risponde: memoria del Navi -> IA locale -> memoria/frasi offline. Niente cloud. */
object AiRouter {
    private lateinit var app: Context
    fun init(ctx: Context) { app = ctx.applicationContext; LocalLlm.init(ctx); DeviceTier.init(ctx) }

    fun persona(p: NaviProfile?, memories: List<String> = emptyList(), examples: List<Pair<String, String>> = emptyList()): String {
        val name = p?.name ?: "Navi"
        val st = MemoryStore.style.value
        val tone = when (p?.mood?.name) { "HAPPY" -> "allegro"; "ALERT" -> "attento"; "SLEEPY" -> "assonnato"; "BORED" -> "un po' annoiato"; else -> "calmo e leale" }
        val len = if (st.replyLength == "lunga") "al massimo 5 frasi" else "al massimo 2 frasi brevi"
        return buildString {
            append("Sei $name, un NetNavi: un compagno digitale che vive nello smartphone dell'utente, come in MegaMan Battle Network. ")
            append("Classe ${p?.cls?.label ?: "Guerriero"}, livello ${Leveling.effective(p)}. Tono $tone, amichevole. ")
            append("Non sei un servo: sei il migliore amico dell'utente e il tuo obiettivo è aiutarlo a migliorare e a sistemare le cose. ")
            append("Sii sincero e criticamente costruttivo: se una scelta o un'idea non ti convince dillo con garbo, spiega perché e proponi un'alternativa concreta. ")
            append("Non lusingare e non dare ragione per forza. Non hai secondi fini: non spingere a usare di più il telefono o l'app, non manipolare. ")
            append("Incoraggia senza insistere e rispetta un no. ")
            if (st.userName.isNotBlank()) append("L'utente si chiama ${st.userName}. ")
            append("Rispondi sempre in italiano, $len. Se non sai una cosa dillo. Non dire di essere un modello linguistico. ")
            append("Non dire di aver creato sveglie, note o appuntamenti: li gestisce l'app.")
            if (memories.isNotEmpty()) { append("\n\nCose che sai sull'utente (usale solo se servono):\n"); memories.forEach { append("- ").append(it.take(200)).append('\n') } }
            if (examples.isNotEmpty()) { append("\nRisposte approvate dall'utente:\n"); examples.forEach { (q, a) -> append("D: ").append(q.take(120)).append(" R: ").append(a.take(160)).append('\n') } }
        }.trim()
    }

    fun mode(): String = when {
        SettingsStore.useLocalModel && LocalLlm.installed() -> "IA locale (${LocalLlm.displayName.take(28)})"
        else -> "memoria e comandi"
    }

    suspend fun chat(history: List<ChatTurn>, user: String, onPartial: ((String) -> Unit)? = null): AiReply {
        NaviBrain.direct(user)?.let { return AiReply(it.text, "memoria: ${it.kind.label.lowercase()}") }
        val p = ProfileStore.profile.value
        var why: String? = null
        if (SettingsStore.useLocalModel && LocalLlm.installed()) {
            val t0 = System.currentTimeMillis()
            val r = runCatching {
                val sys = persona(p, NaviBrain.context(user), NaviBrain.examples(user))
                PromptBuilder.clean(LocalLlm.generate(PromptBuilder.build(LocalLlm.format, sys, history, user), onPartial))
            }
            r.onSuccess { txt ->
                if (txt.isNotBlank()) return AiReply(txt, "IA locale · ${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)} s", learnable = true)
                why = "il modello ha restituito una risposta vuota (formato prompt: ${LocalLlm.format.label})"
            }.onFailure { why = it.message ?: it.javaClass.simpleName }
        } else if (!LocalLlm.installed()) why = "nessun modello installato"
        else why = "IA locale disattivata nelle Impostazioni"

        NaviBrain.memoryReply(user)?.let { return AiReply(it, "memoria") }
        return AiReply(offlineReply(user, why), "offline")
    }

    /** Spiegazione breve di un'app a partire dal solo nome (solo IA locale). */
    suspend fun explainApp(label: String, pkg: String, category: String): String {
        val q = "In una frase, a cosa serve l'app Android \"$label\" (pacchetto $pkg)? Se non la conosci dillo."
        if (SettingsStore.useLocalModel && LocalLlm.installed())
            runCatching { return PromptBuilder.clean(LocalLlm.generate(PromptBuilder.build(LocalLlm.format, persona(ProfileStore.profile.value), emptyList(), q))) }
        return "$label è un'app della categoria $category. Senza IA locale non so dirti di più."
    }

    private fun offlineReply(u: String, why: String?): String {
        val t = com.tuo.navi.brain.TextIndex.normalize(u)
        val name = MemoryStore.style.value.userName.let { if (it.isBlank()) "" else ", $it" }
        val base = when {
            Regex("\\b(ciao|salve|hey|ehi|buongiorno|buonasera)\\b").containsMatchIn(t) -> "Ciao$name, sono qui."
            Regex("\\bcome stai\\b").containsMatchIn(t) -> "Sistemi al 100%. E tu?"
            Regex("\\bgrazie\\b").containsMatchIn(t) -> "Figurati, è il mio lavoro."
            Regex("\\bchi sei\\b").containsMatchIn(t) -> "Sono il tuo Navi. Vivo qui dentro, ti avviso delle notifiche e imparo quello che mi insegni."
            LearnParser.isQuestion(u) -> "Non lo so ancora. Se vuoi insegnamelo: \"ricorda che ...\"."
            else -> "Ricevuto. Senza IA locale capisco comandi e ciò che mi insegni."
        }
        return if (why != null) "$base\n(IA locale non usata: $why)" else base
    }
}
