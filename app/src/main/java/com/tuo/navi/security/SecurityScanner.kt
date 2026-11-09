package com.tuo.navi.security

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.tuo.navi.data.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

enum class Sev(val label: String) { OK("tutto a posto"), INFO("da sapere"), WARN("da controllare"), DANGER("pericolo") }

/** Cosa può fare l'utente per sistemare un problema. Il Navi non disinstalla né cambia nulla da solo: apre la schermata giusta. */
enum class FixKind(val label: String) { APP_INFO("Dettagli app"), UNINSTALL("Disinstalla"), SETTINGS("Apri impostazioni"), PLAY_PROTECT("Play Protect"), FILES("Libera spazio"), NONE("") }

data class Finding(val sev: Sev, val title: String, val detail: String, val fix: FixKind = FixKind.NONE, val target: String = "")

data class ScanReport(val at: Long, val durationMs: Long, val appsChecked: Int, val iocCount: Int, val iocUpdatedAt: Long, val findings: List<Finding>, val nightly: Boolean) {
    val worst: Sev get() = findings.maxOfOrNull { it.sev } ?: Sev.OK
    val problems: Int get() = findings.count { it.sev >= Sev.WARN }
    fun summary(): String {
        val d = findings.count { it.sev == Sev.DANGER }; val w = findings.count { it.sev == Sev.WARN }
        return when {
            d > 0 -> "Controllo del sistema: $d ${if (d == 1) "pericolo" else "pericoli"} e $w ${if (w == 1) "punto" else "punti"} da controllare. Apri Controllo sistema, ti dico come sistemare."
            w > 0 -> "Controllo del sistema: nessuna app spia nota, ma $w ${if (w == 1) "cosa da sistemare" else "cose da sistemare"}. Quando hai un minuto guardiamole."
            else -> "Controllo del sistema fatto: nessuna app spia nota e niente di preoccupante."
        }
    }
}

sealed class ScanState {
    object Idle : ScanState()
    data class Running(val progress: Float, val label: String) : ScanState()
    data class Done(val report: ScanReport) : ScanState()
    data class Failed(val msg: String) : ScanState()
}

/**
 * Controllo del sistema stile antivirus, tutto in locale. Limiti onesti: un'app normale su Android non può leggere
 * dentro le altre app né la memoria di sistema, quindi il controllo si basa su elenchi di app spia note, permessi
 * pericolosi, provenienza delle app e impostazioni di sicurezza del telefono.
 */
object SecurityCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state
    private val _last = MutableStateFlow<ScanReport?>(null)
    val last: StateFlow<ScanReport?> = _last
    private val file: File get() = File(ProfileStore.baseDir, "security/last_report.json")

    fun loadLast() { if (_last.value == null) _last.value = runCatching { fromJson(JSONObject(file.readText())) }.getOrNull() }

    fun start(ctx: Context, nightly: Boolean = false, onDone: (ScanReport) -> Unit = {}) {
        if (_state.value is ScanState.Running) return
        val app = ctx.applicationContext
        _state.value = ScanState.Running(0f, "Preparo il controllo")
        scope.launch {
            runCatching { SecurityScanner.run(app, nightly) { p, l -> _state.value = ScanState.Running(p, l) } }
                .onSuccess { r -> save(r); _last.value = r; _state.value = ScanState.Done(r); onDone(r) }
                .onFailure { _state.value = ScanState.Failed(it.message ?: "Errore") }
        }
    }

    /** Esegue il controllo in modo bloccante (per il controllo notturno). */
    fun runBlocking(ctx: Context, nightly: Boolean): ScanReport {
        val r = SecurityScanner.run(ctx.applicationContext, nightly) { _, _ -> }
        save(r); _last.value = r; return r
    }

    fun reset() { _state.value = ScanState.Idle }

    private fun save(r: ScanReport) = runCatching {
        file.parentFile?.mkdirs(); file.writeText(toJson(r).toString())
    }

    private fun toJson(r: ScanReport) = JSONObject().put("at", r.at).put("dur", r.durationMs).put("apps", r.appsChecked).put("ioc", r.iocCount)
        .put("iocAt", r.iocUpdatedAt).put("nightly", r.nightly).put("f", JSONArray().apply {
            r.findings.forEach { put(JSONObject().put("s", it.sev.name).put("t", it.title).put("d", it.detail).put("k", it.fix.name).put("g", it.target)) }
        })

    private fun fromJson(o: JSONObject): ScanReport {
        val a = o.getJSONArray("f")
        return ScanReport(o.getLong("at"), o.optLong("dur"), o.optInt("apps"), o.optInt("ioc"), o.optLong("iocAt"),
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Finding(Sev.valueOf(it.getString("s")), it.getString("t"), it.getString("d"), FixKind.valueOf(it.optString("k", "NONE")), it.optString("g"))
            }, o.optBoolean("nightly"))
    }
}

object SecurityScanner {
    /** Permessi che, insieme, permettono di spiare. */
    private val sensitive = mapOf(
        "android.permission.RECORD_AUDIO" to "microfono", "android.permission.CAMERA" to "fotocamera",
        "android.permission.ACCESS_FINE_LOCATION" to "posizione precisa", "android.permission.ACCESS_BACKGROUND_LOCATION" to "posizione in background",
        "android.permission.READ_SMS" to "SMS", "android.permission.RECEIVE_SMS" to "SMS in arrivo", "android.permission.READ_CALL_LOG" to "registro chiamate",
        "android.permission.READ_CONTACTS" to "contatti", "android.permission.SYSTEM_ALERT_WINDOW" to "disegno sopra le app",
        "android.permission.PROCESS_OUTGOING_CALLS" to "chiamate in uscita", "android.permission.READ_PHONE_STATE" to "stato del telefono",
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "tutti i file", "android.permission.REQUEST_INSTALL_PACKAGES" to "installare altre app")

    private val trustedInstallers = setOf("com.android.vending", "com.google.android.packageinstaller.vending", "com.motorola.ccc.ota",
        "com.motorola.appdirectedsmsproxy", "com.google.android.feedback", "com.sec.android.app.samsungapps", "com.huawei.appmarket")

    fun run(ctx: Context, nightly: Boolean, progress: (Float, String) -> Unit): ScanReport {
        val t0 = System.currentTimeMillis()
        val out = mutableListOf<Finding>()
        val pm = ctx.packageManager
        val ioc = IocStore.load()
        progress(0.05f, "Leggo l'elenco delle app")
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
        val pkgs: List<PackageInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.getInstalledPackages(flags)
        }.getOrDefault(emptyList())

        val acc = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').mapNotNull { it.substringBefore('/').takeIf { s -> s.isNotBlank() } }.toSet()
        val listeners = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            .split(':').mapNotNull { it.substringBefore('/').takeIf { s -> s.isNotBlank() } }.toSet()
        val admins = runCatching { (ctx.getSystemService(DevicePolicyManager::class.java)).activeAdmins.orEmpty().map { it.packageName }.toSet() }.getOrDefault(emptySet())
        val launchable = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())

        var spyFound = 0
        pkgs.forEachIndexed { i, p ->
            if (i % 10 == 0) progress(0.1f + 0.7f * i / pkgs.size.coerceAtLeast(1), "Controllo le app (${i + 1}/${pkgs.size})")
            val pkg = p.packageName
            if (pkg == ctx.packageName) return@forEachIndexed
            val ai = p.applicationInfo ?: return@forEachIndexed
            val system = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
            val certs = certSha1(p)
            IocStore.match(ioc, pkg, certs)?.let { e ->
                spyFound++
                out += Finding(Sev.DANGER, "App spia nota: $label", "Corrisponde a \"${e.name}\" (${e.type.ifBlank { "stalkerware" }}) nell'elenco pubblico delle app spia. " +
                    "Prima di disinstallarla pensa se qualcuno potrebbe accorgersene: se temi per la tua sicurezza, chiedi aiuto (in Italia 1522, gratuito e attivo 24 ore su 24).",
                    FixKind.UNINSTALL, pkg)
                return@forEachIndexed
            }
            if (system) return@forEachIndexed
            val installer = installerOf(pm, pkg)
            val sideloaded = installer == null || installer !in trustedInstallers
            val granted = p.requestedPermissions.orEmpty().filterIndexed { k, _ ->
                (p.requestedPermissionsFlags?.getOrNull(k) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            }.mapNotNull { sensitive[it] }
            val r = riskScore(pkg in acc, pkg in listeners, pkg in admins, granted.size, sideloaded, pkg !in launchable)
            val why = buildList {
                if (pkg in acc) add("controlla lo schermo (accessibilità)")
                if (pkg in listeners) add("legge le notifiche")
                if (pkg in admins) add("è amministratore del dispositivo")
                if (granted.isNotEmpty()) add("accede a " + granted.take(5).joinToString(", "))
                if (sideloaded) add(if (installer == null) "installata fuori dal Play Store" else "installata da $installer")
                if (pkg !in launchable) add("non ha icona nel menu")
            }.joinToString("; ")
            when {
                r >= 7 -> out += Finding(Sev.WARN, "Da controllare: $label", "Ha poteri che potrebbero servire a spiare: $why. Se non la riconosci o non ti serve, disinstallala.", FixKind.APP_INFO, pkg)
                r >= 5 && sideloaded -> out += Finding(Sev.INFO, "Da sapere: $label", "$why. Se la conosci va bene così.", FixKind.APP_INFO, pkg)
            }
        }

        progress(0.85f, "Controllo le impostazioni di sicurezza")
        out += systemChecks(ctx)
        progress(0.97f, "Preparo il rapporto")
        if (ioc.isEmpty()) out += Finding(Sev.INFO, "Elenco delle app spia non scaricato",
            "Accendi Internet e premi \"Aggiorna elenco\": il controllo delle app spia note funziona solo con l'elenco sul telefono.", FixKind.NONE)
        else if (spyFound == 0) out += Finding(Sev.OK, "Nessuna app spia nota", "Ho confrontato ${pkgs.size} app con ${ioc.size} famiglie di app spia conosciute.")
        if (out.none { it.sev >= Sev.WARN && it.title.startsWith("Da controllare") }) out += Finding(Sev.OK, "Nessuna app con poteri sospetti", "Nessuna app non di sistema somma permessi pericolosi.")
        val sorted = out.sortedByDescending { it.sev }
        return ScanReport(System.currentTimeMillis(), System.currentTimeMillis() - t0, pkgs.size, ioc.size, IocStore.updatedAt(), sorted, nightly)
    }

    /** Punteggio di rischio di un'app non di sistema (funzione pura, testata). */
    fun riskScore(accessibility: Boolean, notifListener: Boolean, deviceAdmin: Boolean, sensitivePerms: Int, sideloaded: Boolean, hidden: Boolean): Int {
        var s = 0
        if (accessibility) s += 3
        if (notifListener) s += 2
        if (deviceAdmin) s += 3
        s += when { sensitivePerms >= 6 -> 3; sensitivePerms >= 4 -> 2; sensitivePerms >= 2 -> 1; else -> 0 }
        if (sideloaded) s += 2
        if (hidden) s += 2
        return s
    }

    /** Mesi trascorsi dalla patch di sicurezza (formato AAAA-MM-GG). */
    fun patchAgeMonths(patch: String, nowMs: Long = System.currentTimeMillis()): Int? = runCatching {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(patch)!!.time
        ((nowMs - d) / (30.44 * 86_400_000)).toInt()
    }.getOrNull()

    private fun systemChecks(ctx: Context): List<Finding> {
        val f = mutableListOf<Finding>()
        val cr = ctx.contentResolver
        patchAgeMonths(Build.VERSION.SECURITY_PATCH)?.let { m ->
            when {
                m >= 12 -> f += Finding(Sev.WARN, "Aggiornamenti di sicurezza vecchi", "L'ultima patch è del ${Build.VERSION.SECURITY_PATCH} ($m mesi fa). Cerca aggiornamenti di sistema; se il telefono non ne riceve più, evita app fuori dal Play Store.", FixKind.SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS)
                m >= 4 -> f += Finding(Sev.INFO, "Patch di sicurezza di $m mesi fa", "Patch del ${Build.VERSION.SECURITY_PATCH}. Controlla se c'è un aggiornamento.", FixKind.SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS)
                else -> f += Finding(Sev.OK, "Sistema aggiornato", "Patch di sicurezza del ${Build.VERSION.SECURITY_PATCH}.")
            }
        }
        val km = ctx.getSystemService(KeyguardManager::class.java)
        if (km != null && !km.isDeviceSecure) f += Finding(Sev.WARN, "Nessun blocco schermo", "Chiunque prenda il telefono può aprirlo. Imposta un PIN, una sequenza o l'impronta.", FixKind.SETTINGS, Settings.ACTION_SECURITY_SETTINGS)
        val adb = runCatching { Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        val dev = runCatching { Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false)
        if (adb) f += Finding(Sev.WARN, "Debug USB attivo", "Collegato a un computer, il telefono può essere controllato da fuori. Se non lo usi, spegnilo nelle Opzioni sviluppatore.", FixKind.SETTINGS, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        else if (dev) f += Finding(Sev.INFO, "Opzioni sviluppatore attive", "Non è un pericolo, ma se non ti servono puoi spegnerle.", FixKind.SETTINGS, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        if (isRooted()) f += Finding(Sev.WARN, "Telefono con permessi di root", "Il root toglie molte protezioni di Android. Se non l'hai fatto tu, è un segnale grave.", FixKind.NONE)
        runCatching {
            val st = StatFs(Environment.getDataDirectory().path)
            val pct = st.availableBytes * 100 / st.totalBytes.coerceAtLeast(1)
            when {
                pct < 5 -> f += Finding(Sev.WARN, "Memoria quasi piena ($pct% libero)", "Con poco spazio il telefono rallenta e gli aggiornamenti falliscono. Liberiamo qualcosa.", FixKind.FILES)
                pct < 10 -> f += Finding(Sev.INFO, "Poco spazio libero ($pct%)", "Conviene fare un po' di pulizia.", FixKind.FILES)
                else -> f += Finding(Sev.OK, "Spazio libero sufficiente ($pct%)", "")
            }
        }
        runCatching {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val health = b?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
            val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            when (health) {
                BatteryManager.BATTERY_HEALTH_DEAD, BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE, BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE ->
                    f += Finding(Sev.WARN, "Batteria danneggiata", "Il sistema segnala un guasto della batteria: falla controllare da un centro assistenza.", FixKind.NONE)
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> f += Finding(Sev.WARN, "Batteria surriscaldata", "Togli la cover, scollega il caricatore e lascialo raffreddare.", FixKind.NONE)
            }
            if (temp >= 45f && health != BatteryManager.BATTERY_HEALTH_OVERHEAT) f += Finding(Sev.WARN, "Batteria calda (${temp.toInt()} °C)", "Chiudi le app pesanti e tienilo lontano dal sole.", FixKind.NONE)
        }
        f += Finding(Sev.INFO, "Google Play Protect", "Per un controllo in più, Play Protect esamina le app con i server di Google. È una scelta tua: il Navi non invia nulla.", FixKind.PLAY_PROTECT)
        return f
    }

    private fun isRooted(): Boolean {
        val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/data/local/xbin/su", "/data/local/bin/su", "/data/adb/magisk", "/system/app/Superuser.apk")
        return paths.any { runCatching { File(it).exists() }.getOrDefault(false) } || Build.TAGS?.contains("test-keys") == true
    }

    private fun installerOf(pm: PackageManager, pkg: String): String? = runCatching {
        pm.getInstallSourceInfo(pkg).let { it.installingPackageName ?: it.initiatingPackageName }
    }.getOrNull()

    private fun certSha1(p: PackageInfo): List<String> = runCatching {
        val si = p.signingInfo ?: return emptyList()
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02X".format(it) } }
    }.getOrDefault(emptyList())
}
