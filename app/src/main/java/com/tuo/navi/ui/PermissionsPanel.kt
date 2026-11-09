package com.tuo.navi.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuo.navi.calls.NaviCallScreening
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.habits.HabitTracker
import com.tuo.navi.home.HomeNavi
import com.tuo.navi.home.HomeScene
import com.tuo.navi.media.MediaScanner
import kotlinx.coroutines.delay

private val Ok = Color(0xFF7CFF6B)
private val Warn = Color(0xFFFFC857)

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
fun callPermissions(): Array<String> = arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
    Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)

/**
 * Tutti i permessi in un posto solo, spiegati in parole semplici. Ogni riga dice a cosa serve e ha un solo pulsante:
 * si apre la schermata giusta di Android e, tornando qui, lo stato si aggiorna da solo.
 */
@Composable
fun PermissionsPanel(onCalls: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    var overlay by remember { mutableStateOf(SettingsStore.overlayEnabled) }
    var expanded by remember { mutableStateOf(false) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    val listener = remember(tick) { Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true }
    val acc = remember(tick) { HomeScene.accessibilityOn(ctx) }
    val usage = remember(tick) { HabitTracker.hasUsageAccess(ctx) }
    val battery = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val post = remember(tick) { Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS) }
    val media = remember(tick) { MediaScanner.permissions().any { granted(ctx, it) } }
    val mic = remember(tick) { granted(ctx, Manifest.permission.RECORD_AUDIO) }
    val calls = remember(tick) { callPermissions().all { granted(ctx, it) } && NaviCallScreening.hasRole(ctx) }

    val essentialMissing = listOf(listener, acc).count { !it }
    val optionalMissing = listOf(usage, battery, post, media, mic).count { !it }
    LaunchedEffect(essentialMissing) { if (essentialMissing > 0) expanded = true }

    fun open(action: String, pkgUri: Boolean = false) = runCatching {
        ctx.startActivity(if (pkgUri) Intent(action, Uri.parse("package:${ctx.packageName}")) else Intent(action))
    }.onFailure { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }

    @Composable
    fun Row1(title: String, why: String, ok: Boolean, needed: Boolean, action: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(if (ok) Ok else if (needed) Color(0xFFFF6B6B) else Warn, RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title + if (!needed) " · facoltativo" else "", color = if (ok) Ok else Color.White, fontSize = 15.sp)
                Label(if (ok) "Attivo. $why" else why)
            }
            if (!ok) Button(onClick = action, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Attiva") }
        }
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Permessi e accesso", color = NaviCyan)
                Text(when {
                    essentialMissing > 0 -> "Mancano $essentialMissing permessi importanti"
                    optionalMissing > 0 -> "Tutto pronto · $optionalMissing facoltativi da attivare"
                    else -> "Tutto pronto"
                }, color = if (essentialMissing > 0) Color(0xFFFF6B6B) else Ok, fontSize = 14.sp)
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Nascondi" else "Mostra") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Vedi il Navi sulla Home", color = Color.White, fontSize = 15.sp)
                Label(if (!acc) "Serve il permesso \"Navi sulla Home\" qui sotto." else if (overlay) "Cammina sulla Home e sparisce quando apri un'app." else "Nascosto: resta solo nell'app.")
            }
            Switch(overlay, { overlay = it; SettingsStore.overlayEnabled = it; HomeNavi.refreshSettings() })
        }
        if (!expanded) return@Panel
        HorizontalDivider(color = Color(0x33FFFFFF))
        Label("Tocca \"Attiva\", accendi NetNavi nella schermata che si apre e torna indietro: il pallino diventa verde da solo.")
        Row1("Navi sulla Home", "Lo fa camminare sopra le icone della tua Home. Legge solo nome e posizione delle icone, mai il contenuto delle altre app.", acc, true) {
            open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        Row1("Avvisi delle notifiche", "Per dirti \"messaggio su WhatsApp da Marco\". Il testo dei messaggi non viene letto.", listener, true) {
            open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        if (!acc || !listener) Column(Modifier.fillMaxWidth().background(Color(0x22FFC857), RoundedCornerShape(8.dp)).padding(10.dp)) {
            Text("Interruttore grigio o \"Impostazione con restrizioni\"?", color = Warn, fontSize = 14.sp)
            Label("Succede con le app installate da file APK. Si sblocca una volta sola:\n1. tocca \"Apri Info app\" qui sotto\n2. tocca i tre puntini in alto a destra\n" +
                "3. scegli \"Consenti impostazioni con restrizioni\" e conferma\n4. torna qui e tocca di nuovo \"Attiva\"")
            TextButton(onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("Apri Info app NetNavi") }
            Label("Nell'elenco Accessibilità cerca \"NetNavi - Navi sulla Home\" (a volte è dentro \"App installate\" o \"Servizi scaricati\").")
        }
        Row1("Dati di utilizzo", "Per imparare le tue abitudini e trovare le app che non usi più.", usage, false) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        Row1("Niente risparmio batteria", "Così Android non lo spegne in background e le sveglie del Navi arrivano puntuali.", battery, false) {
            open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true) }
        Row1("Notifiche di NetNavi", "Per i promemoria, i risultati del controllo notturno e i download.", post, false) {
            if (Build.VERSION.SDK_INT >= 33) runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
        Row1("Microfono", "Per dettare i comandi a voce e per il riassunto delle chiamate moleste. Niente viene registrato.", mic, false) {
            runtime.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
        Row1("Foto e video", "Per trovare i file pesanti e le foto doppie. I file non escono dal telefono.", media, false) { runtime.launch(MediaScanner.permissions()) }
        Row1("Chiamate moleste", "Black list, rifiuto automatico e segreteria con il tuo audio. Si configura nella sua schermata.", calls, false) { onCalls() }
        if (listOf(usage, battery, post, mic, media).any { !it }) Label("Se un permesso è stato negato due volte Android non lo chiede più: in quel caso apri Info app NetNavi > Autorizzazioni.")
        TextButton(onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("Tutte le autorizzazioni in Info app") }
    }
}
