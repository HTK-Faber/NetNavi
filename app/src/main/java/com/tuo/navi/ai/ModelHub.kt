package com.tuo.navi.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.tuo.navi.core.Ability
import com.tuo.navi.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Modelli di base consigliati (formato .task per MediaPipe, da huggingface.co/litert-community). */
data class CatalogModel(
    val id: String, val title: String, val repo: String, val file: String, val sizeMb: Int,
    val gated: Boolean, val minRamGb: Int, val note: String
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$file"
    val page: String get() = "https://huggingface.co/$repo"
}

object ModelCatalog {
    val models = listOf(
        CatalogModel("gemma3-270m", "Gemma 3 270M", "litert-community/gemma-3-270m-it", "gemma3-270m-it-q8.task", 290, true, 3,
            "Il più leggero e veloce. Risposte semplici, italiano limitato."),
        CatalogModel("gemma3-1b-int4", "Gemma 3 1B int4", "litert-community/Gemma3-1B-IT", "gemma3-1b-it-int4.task", 529, true, 4,
            "Il migliore per 4 GB: buon italiano, qualche secondo per risposta."),
        CatalogModel("qwen25-05b", "Qwen 2.5 0.5B", "litert-community/Qwen2.5-0.5B-Instruct", "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task", 521, false, 4,
            "Non serve un account Hugging Face. Italiano discreto."),
        CatalogModel("gemma3-1b-q8", "Gemma 3 1B q8", "litert-community/Gemma3-1B-IT", "Gemma3-1B-IT_multi-prefill-seq_q8_ekv2048.task", 1024, true, 6,
            "Più preciso dell'int4, serve più memoria."),
        CatalogModel("qwen25-15b", "Qwen 2.5 1.5B", "litert-community/Qwen2.5-1.5B-Instruct", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task", 1524, false, 8,
            "Il più capace della lista, per telefoni da 8 GB o più.")
    )
    fun byId(id: String) = models.firstOrNull { it.id == id }
}

/** Potenza del telefono: decide quale modello di base consigliare. */
object DeviceTier {
    var ramMb = 0L; private set
    var cores = 0; private set
    var soc = ""; private set

    fun init(ctx: Context) {
        val mi = ActivityManager.MemoryInfo(); ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        ramMb = mi.totalMem / (1 shl 20)
        cores = Runtime.getRuntime().availableProcessors()
        soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else Build.HARDWARE
    }

    /** I telefoni da 4 GB riportano circa 3,6-3,8 GB: si arrotonda per eccesso. */
    val ramGb: Int get() = ((ramMb + 600) / 1024).toInt()

    fun recommended(): List<CatalogModel> = when {
        ramGb <= 3 -> listOf("gemma3-270m")
        ramGb <= 4 -> listOf("gemma3-1b-int4", "qwen25-05b", "gemma3-270m")
        ramGb <= 6 -> listOf("gemma3-1b-q8", "gemma3-1b-int4", "qwen25-05b")
        else -> listOf("qwen25-15b", "gemma3-1b-q8", "gemma3-1b-int4")
    }.mapNotNull { ModelCatalog.byId(it) }

    fun tooBig(m: CatalogModel) = m.minRamGb > ramGb

    fun describe() = "$ramGb GB di RAM (${ramMb} MB), $cores core, ${soc.ifBlank { "processore sconosciuto" }}"
}

/**
 * Unico punto da cui l'app può uscire su Internet. Se l'interruttore è spento non parte nessuna connessione.
 * Anche quando è acceso, l'app parla solo con Hugging Face e non invia nessun tuo dato: solo la richiesta del file.
 */
object NetGate {
    /** Download dei modelli: solo Hugging Face. */
    private val hosts = listOf("huggingface.co", "hf.co")
    /** Altri indirizzi consentiti, sempre e solo con l'interruttore Internet acceso:
     *  ricerca (DuckDuckGo, Wikipedia) ed elenco delle app spia note (GitHub, progetto Echap). */
    private val otherHosts = listOf("html.duckduckgo.com", "lite.duckduckgo.com", "it.wikipedia.org", "raw.githubusercontent.com")
    fun allowed() = SettingsStore.internetAllowed
    fun hostOk(h: String) = hosts.any { h == it || h.endsWith(".$it") }
    fun anyHostOk(h: String) = hostOk(h) || h in otherHosts
    fun require() { check(allowed()) { "Internet è disattivato nelle Impostazioni del Navi" } }

    /** Scarica un testo (https, max 3 MB). Non invia cookie, account o dati del telefono: solo l'indirizzo richiesto. */
    fun fetchText(url: String, timeoutMs: Int = 15_000, maxBytes: Int = 3 shl 20): String {
        require()
        val u = java.net.URL(url)
        check(u.protocol == "https") { "Solo connessioni cifrate" }
        check(anyHostOk(u.host)) { "Indirizzo non consentito: ${u.host}" }
        val con = (u.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = timeoutMs; instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "it-IT,it;q=0.9,en;q=0.6")
            useCaches = false
        }
        try {
            val code = con.responseCode
            check(code in 200..299) { "Risposta del server: $code" }
            con.inputStream.use { inp ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 shl 10); var tot = 0
                while (true) { val n = inp.read(buf); if (n < 0) break; tot += n; check(tot <= maxBytes) { "Risposta troppo grande" }; out.write(buf, 0, n) }
                return out.toString("UTF-8")
            }
        } finally { con.disconnect() }
    }
}

sealed class DlState {
    object Idle : DlState()
    data class Running(val name: String, val done: Long, val total: Long) : DlState()
    data class Done(val name: String) : DlState()
    data class Failed(val msg: String) : DlState()
}

/** Download di un modello da Hugging Face, con ripresa se si interrompe. */
object ModelDownloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stop = false
    private val _state = MutableStateFlow<DlState>(DlState.Idle)
    val state: StateFlow<DlState> get() = _state

    fun normalizeUrl(u: String): String = u.trim().replace("/blob/", "/resolve/").let { if (it.contains("?")) it else it }

    fun start(ctx: Context, url: String, name: String) {
        if (job?.isActive == true) return
        stop = false
        job = scope.launch {
            runCatching {
                NetGate.require()
                check(Ability.IN_APP_DOWNLOAD.unlocked) { "Abilità \"${Ability.IN_APP_DOWNLOAD.title}\" non ancora sbloccata" }
                val tmp = File(ctx.filesDir, "models/dl-${name.hashCode().toUInt()}.part").apply { parentFile?.mkdirs() }
                download(normalizeUrl(url), tmp, name)
                _state.value = DlState.Running(name, tmp.length(), tmp.length())
                LocalLlm.install(tmp, name)
            }.onSuccess { _state.value = DlState.Done(name) }
                .onFailure { _state.value = DlState.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    fun cancel() { stop = true; _state.value = DlState.Failed("Download annullato (riprendendolo riparte da dove era arrivato)") }

    private fun download(start: String, tmp: File, name: String) {
        var url = URL(start); var hops = 0
        while (true) {
            check(url.protocol == "https") { "Solo collegamenti https" }
            check(NetGate.hostOk(url.host)) { "Indirizzo non consentito: ${url.host}. Il Navi scarica solo da Hugging Face." }
            val have = tmp.length()
            val con = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "NetNavi")
                // il token va solo a huggingface.co, mai ai server di distribuzione dei file
                if (SettingsStore.hfToken.isNotBlank() && url.host == "huggingface.co") setRequestProperty("Authorization", "Bearer ${SettingsStore.hfToken}")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = con.responseCode
            if (code in 300..399) {
                val loc = con.getHeaderField("Location") ?: error("Reindirizzamento senza destinazione")
                con.disconnect(); url = URL(url, loc); check(++hops < 8) { "Troppi reindirizzamenti" }; continue
            }
            when (code) {
                401, 403 -> error("Accesso negato ($code). Per i modelli Gemma: accedi a Hugging Face dal browser, accetta la licenza nella pagina del modello e incolla qui un token di lettura.")
                404 -> error("File non trovato (404): controlla il collegamento.")
                416 -> { con.disconnect(); return } // già completo
            }
            check(code == 200 || code == 206) { "Errore del server: $code" }
            val append = code == 206
            val total = (con.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L).let { if (it > 0 && append) it + have else it }
            con.inputStream.use { inp ->
                FileOutputStream(tmp, append).use { out ->
                    val buf = ByteArray(1 shl 16); var done = if (append) have else 0L; var last = 0L
                    while (!stop) {
                        val r = inp.read(buf); if (r < 0) break
                        out.write(buf, 0, r); done += r
                        if (done - last > 2 shl 20) { last = done; _state.value = DlState.Running(name, done, total) }
                    }
                }
            }
            con.disconnect()
            check(!stop) { "Download annullato" }
            return
        }
    }
}
