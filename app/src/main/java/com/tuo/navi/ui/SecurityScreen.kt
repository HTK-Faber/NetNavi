package com.tuo.navi.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Cream = Color(0xFFF3E6C8)
private val Muted = Color(0xFFBFC8DA)
private fun sevColor(s: Sev) = when (s) { Sev.OK -> Color(0xFF66BB6A); Sev.INFO -> Color(0xFF64B5F6); Sev.WARN -> Color(0xFFFFC857); Sev.DANGER -> Color(0xFFEF5350) }

/** Controllo del sistema stile antivirus: tutto in locale, con l'elenco delle app spia note scaricato solo se vuoi. */
@Composable
fun SecurityScreen(vm: NaviViewModel, onBack: () -> Unit, nav: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by SecurityCenter.state.collectAsState()
    val last by SecurityCenter.last.collectAsState()
    val pixel = remember { ProfileStore.loadPixel() }
    var iocAt by remember { mutableStateOf(0L) }
    var iocN by remember { mutableStateOf(0) }
    var upd by remember { mutableStateOf<String?>(null) }
    var nightly by remember { mutableStateOf(SettingsStore.nightlyScan) }
    val fmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ITALIAN) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { SecurityCenter.loadLast(); iocAt = IocStore.updatedAt(); iocN = IocStore.load().size }
        if (vm.consumeAutoScan()) SecurityCenter.start(ctx)
    }
    LaunchedEffect(st) { if (st is ScanState.Done) { vm.gainExpPublic(com.tuo.navi.core.Leveling.Exp.COMMAND); SecurityCenter.reset() } }

    fun open(i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun fix(f: Finding) {
        when (f.fix) {
            FixKind.APP_INFO -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${f.target}")))
            FixKind.UNINSTALL -> open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${f.target}")))
            FixKind.SETTINGS -> open(Intent(f.target.ifBlank { Settings.ACTION_SETTINGS })).onFailure { open(Intent(Settings.ACTION_SETTINGS)) }
            FixKind.PLAY_PROTECT -> open(Intent("com.google.android.gms.settings.VERIFY_APPS_SETTINGS")).onFailure { open(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            FixKind.FILES -> nav(Screen.FILES)
            FixKind.NONE -> {}
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Controllo sistema", color = Gold, fontFamily = SerifFont, fontSize = 21.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Indietro", color = Cream) }
        }

        // Stato e pulsante principale
        Row(Modifier.fillMaxWidth().goldFrame(st is ScanState.Running).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val running = st as? ScanState.Running
            NaviSprite(if (running != null) "scan" else if ((last?.worst ?: Sev.OK) >= Sev.WARN) "alert" else "idle", true, 72.dp, pixel)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                when {
                    running != null -> {
                        Text(running.label, color = Cream, fontSize = 14.sp)
                        LinearProgressIndicator(progress = { running.progress }, Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp), color = NaviCyan, trackColor = Color(0xFF1A2440))
                    }
                    st is ScanState.Failed -> Text("Controllo non riuscito: ${(st as ScanState.Failed).msg}", color = Color(0xFFEF5350), fontSize = 14.sp)
                    last != null -> {
                        val r = last!!
                        Text(r.summary(), color = Cream, fontSize = 14.sp)
                        Text("Ultimo controllo: ${fmt.format(Date(r.at))}${if (r.nightly) " (notturno)" else ""} · ${r.appsChecked} app", color = Muted, fontSize = 12.sp)
                    }
                    else -> Text("Non ho ancora controllato il telefono. Ci vuole meno di un minuto e resta tutto qui.", color = Cream, fontSize = 14.sp)
                }
                Button(onClick = { SecurityCenter.start(ctx) }, enabled = running == null, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (running != null) "Controllo in corso" else "Avvia controllo")
                }
            }
        }

        // Elenco app spia
        Column(Modifier.fillMaxWidth().goldFrame().padding(12.dp)) {
            Text("Elenco delle app spia note", color = Gold, fontSize = 15.sp)
            Text(if (iocN > 0) "$iocN famiglie, aggiornato il ${fmt.format(Date(iocAt))}." else "Non ancora scaricato.", color = Cream, fontSize = 13.sp)
            Text("Fonte pubblica: progetto stalkerware-indicators (Echap). Si scarica solo l'elenco: il confronto con le tue app avviene sul telefono e nessun dato esce.",
                color = Muted, fontSize = 12.sp)
            upd?.let { Text(it, color = NaviCyan, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (!SettingsStore.internetAllowed) { upd = "Internet è spento: accendilo nelle Impostazioni per scaricare l'elenco."; return@TextButton }
                    upd = "Scarico l'elenco..."
                    scope.launch {
                        upd = withContext(Dispatchers.IO) { runCatching { IocStore.update() } }.fold({ n -> iocAt = IocStore.updatedAt(); iocN = n; "Elenco aggiornato: $n famiglie." }, { "Aggiornamento non riuscito: ${it.message}" })
                    }
                }) { Text("Aggiorna elenco", color = NaviCyan) }
            }
        }

        // Controllo notturno
        Row(Modifier.fillMaxWidth().goldFrame().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Controllo notturno", color = Gold, fontSize = 15.sp)
                Text("Ogni notte tra le 3 e le 5. Se Internet è acceso aggiorna l'elenco una volta a settimana. Al mattino te lo dico con un fumetto; una notifica solo in caso di pericolo.",
                    color = Muted, fontSize = 12.sp)
            }
            Switch(nightly, { nightly = it; SettingsStore.nightlyScan = it; NightlyScan.schedule(ctx) })
        }

        // Risultati
        last?.let { r ->
            Text("Risultati", color = Gold, fontFamily = SerifFont, fontSize = 17.sp)
            r.findings.forEach { f ->
                Row(Modifier.fillMaxWidth().goldFrame(f.sev == Sev.DANGER).padding(10.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 4.dp).size(12.dp).background(sevColor(f.sev), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.title, color = Cream, fontSize = 14.sp)
                        Text(f.sev.label, color = sevColor(f.sev), fontSize = 11.sp)
                        if (f.detail.isNotBlank()) Text(f.detail, color = Muted, fontSize = 12.sp)
                        if (f.fix != FixKind.NONE) TextButton(onClick = { fix(f) }, contentPadding = PaddingValues(0.dp)) { Text(f.fix.label, color = NaviCyan) }
                    }
                }
            }
        }

        // Limiti onesti
        Column(Modifier.fillMaxWidth().goldFrame().padding(12.dp)) {
            Text("Cosa posso e non posso fare", color = Gold, fontSize = 15.sp)
            Text("Su Android un'app normale non può guardare dentro le altre app né la memoria di sistema, quindi non sono un antivirus completo. " +
                "Controllo: app spia note (per nome e firma), app con permessi pericolosi o installate fuori dal Play Store, aggiornamenti di sicurezza, blocco schermo, " +
                "debug USB, root, spazio libero e salute della batteria. Non disinstallo e non cambio nulla da solo: ti apro la schermata giusta e decidi tu.",
                color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
    }
}
