package com.tuo.navi.data

import android.content.Context
import android.content.SharedPreferences

/** Impostazioni modificabili (non riguardano l'aspetto dell'avatar). */
object SettingsStore {
    private lateinit var p: SharedPreferences
    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        // Gemini rimosso (privacy): cancella eventuali chiavi salvate dalle versioni precedenti
        if (p.contains("geminiKey") || p.contains("geminiModel")) p.edit().remove("geminiKey").remove("geminiModel").apply()
    }

    var voiceEnabled: Boolean get() = p.getBoolean("voice", true); set(v) = p.edit().putBoolean("voice", v).apply()
    var speakScreenOff: Boolean get() = p.getBoolean("speakOff", false); set(v) = p.edit().putBoolean("speakOff", v).apply()
    var overlayEnabled: Boolean get() = p.getBoolean("overlay", true); set(v) = p.edit().putBoolean("overlay", v).apply()
    var dailyBudget: Int get() = p.getInt("budget", 40); set(v) = p.edit().putInt("budget", v).apply()
    /** Interruttore Internet: spento di default. Quando è spento l'app non apre nessuna connessione. */
    var internetAllowed: Boolean get() = p.getBoolean("internet", false); set(v) = p.edit().putBoolean("internet", v).apply()
    /** Token Hugging Face (solo per i modelli Gemma, che richiedono l'accettazione della licenza). Resta sul telefono. */
    var hfToken: String get() = p.getString("hfToken", "") ?: ""; set(v) = p.edit().putString("hfToken", v.trim()).apply()
    var modelName: String get() = p.getString("modelName", "") ?: ""; set(v) = p.edit().putString("modelName", v).apply()
    var modelFormat: String get() = p.getString("modelFormat", "AUTO") ?: "AUTO"; set(v) = p.edit().putString("modelFormat", v).apply()
    var useLocalModel: Boolean get() = p.getBoolean("local", true); set(v) = p.edit().putBoolean("local", v).apply()
    var mutedPackages: Set<String> get() = p.getStringSet("muted", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("muted", v).apply()
    var neverAskUninstall: Set<String> get() = p.getStringSet("keepApps", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("keepApps", v).apply()

    /** App poco usate rinviate: "pacchetto|scadenza in ms". */
    var snoozedApps: Set<String> get() = p.getStringSet("snoozedApps", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("snoozedApps", v).apply()
    fun snoozeApp(pkg: String, days: Int) {
        val until = System.currentTimeMillis() + days * 86_400_000L
        snoozedApps = snoozedApps.filterNot { it.substringBefore('|') == pkg }.toSet() + "$pkg|$until"
    }
    fun snoozedNow(now: Long = System.currentTimeMillis()): Set<String> =
        snoozedApps.mapNotNull { e -> val t = e.substringAfter('|').toLongOrNull() ?: 0L; if (t > now) e.substringBefore('|') else null }.toSet()

    // File grandi: soglie e intervallo delle foto in serie
    var photoMinMb: Int get() = p.getInt("photoMinMb", 5); set(v) = p.edit().putInt("photoMinMb", v).apply()
    var videoMinMb: Int get() = p.getInt("videoMinMb", 100); set(v) = p.edit().putInt("videoMinMb", v).apply()
    var burstSeconds: Int get() = p.getInt("burstSec", 5); set(v) = p.edit().putInt("burstSec", v).apply()

    var naviSizeDp: Int get() = p.getInt("naviSize", 64); set(v) = p.edit().putInt("naviSize", v).apply()
    var lastUnusedPromptDay: Long get() = p.getLong("unusedDay", -1); set(v) = p.edit().putLong("unusedDay", v).apply()
    /** Controllo del sistema pianificato di notte (03:00-05:00). */
    var nightlyScan: Boolean get() = p.getBoolean("nightlyScan", true); set(v) = p.edit().putBoolean("nightlyScan", v).apply()
    /** Avviso sulla privacy mostrato alla prima ricerca su Internet. */
    var searchWarned: Boolean get() = p.getBoolean("searchWarned", false); set(v) = p.edit().putBoolean("searchWarned", v).apply()
    /** Ultimo giorno in cui il Navi ha commentato l'orario tardo (al massimo una volta al giorno). */
    var lateNoteDay: Long get() = p.getLong("lateNoteDay", -1); set(v) = p.edit().putLong("lateNoteDay", v).apply()
    // Chiamate moleste
    var callGuard: Boolean get() = p.getBoolean("callGuard", false); set(v) = p.edit().putBoolean("callGuard", v).apply()
    /** Dalla N-esima chiamata (in 7 giorni) di un numero bloccato il Navi risponde con l'audio scelto. */
    var callAnswerAfter: Int get() = p.getInt("callAnswerAfter", 2); set(v) = p.edit().putInt("callAnswerAfter", v).apply()
    /** Secondi di ascolto dopo l'audio, per il riassunto (0 = riaggancia subito). */
    var callListenSec: Int get() = p.getInt("callListenSec", 20); set(v) = p.edit().putInt("callListenSec", v).apply()
    var callMaxPlaySec: Int get() = p.getInt("callMaxPlay", 60); set(v) = p.edit().putInt("callMaxPlay", v).apply()
    var greetingName: String get() = p.getString("greetingName", "") ?: ""; set(v) = p.edit().putString("greetingName", v).apply()
    var greetingText: String get() = p.getString("greetingText", DEFAULT_GREETING) ?: DEFAULT_GREETING; set(v) = p.edit().putString("greetingText", v).apply()
    const val DEFAULT_GREETING = "Salve, questo numero non accetta chiamate commerciali o di disturbo. La preghiamo di cancellarlo dalle sue liste. Se vuole, lasci un breve messaggio dopo questo avviso."

    var setupDone: Boolean get() = p.getBoolean("setupDone", false); set(v) = p.edit().putBoolean("setupDone", v).apply()

    // Budget giornaliero degli interventi
    fun consumeBudget(): Boolean {
        val day = (System.currentTimeMillis() / 86_400_000L).toInt()
        if (p.getInt("budgetDay", -1) != day) p.edit().putInt("budgetDay", day).putInt("budgetUsed", 0).apply()
        val used = p.getInt("budgetUsed", 0)
        if (used >= dailyBudget) return false
        p.edit().putInt("budgetUsed", used + 1).apply(); return true
    }
}
