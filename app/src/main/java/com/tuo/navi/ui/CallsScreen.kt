package com.tuo.navi.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuo.navi.calls.CallGuard
import com.tuo.navi.calls.CallResponder
import com.tuo.navi.calls.NaviCallScreening
import com.tuo.navi.calls.Suggestion
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.voice.NaviVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OkC = Color(0xFF7CFF6B)
private val WarnC = Color(0xFFFFC857)
private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/**
 * Chiamate moleste: il Navi propone i numeri da bloccare (decidi tu), rifiuta in silenzio quelli in black list
 * e, se insistono, risponde con l'audio che hai scelto e poi riaggancia. Salva solo esito e riassunto, mai l'audio.
 */
@Composable
fun CallsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { CallGuard.load(); while (true) { delay(2000); tick++ } }
    val blocked by CallGuard.blocked.collectAsState()
    val log by CallGuard.log.collectAsState()
    var on by remember { mutableStateOf(SettingsStore.callGuard) }
    var after by remember { mutableIntStateOf(SettingsStore.callAnswerAfter) }
    var listen by remember { mutableIntStateOf(SettingsStore.callListenSec) }
    var greetName by remember { mutableStateOf(SettingsStore.greetingName) }
    var greetText by remember { mutableStateOf(SettingsStore.greetingText) }
    var hasGreeting by remember { mutableStateOf(CallGuard.hasGreeting()) }
    var playing by remember { mutableStateOf(false) }
    var sugg by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Suggestion?>(null) }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.ITALIAN) }

    val perms = remember(tick) { callPermissions().all { has(ctx, it) } }
    val role = remember(tick) { NaviCallScreening.hasRole(ctx) }
    val mic = remember(tick) { has(ctx, Manifest.permission.RECORD_AUDIO) }
    fun refresh() { loading = true; scope.launch { sugg = withContext(Dispatchers.IO) { CallGuard.currentSuggestions(ctx) }; loading = false } }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++; refresh() }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    LaunchedEffect(perms) { if (perms) refresh() }
    DisposableEffect(Unit) { onDispose { CallResponder.stopPreview() } }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { i -> CallGuard.greetingFile.outputStream().use { i.copyTo(it) } }
                }
                val name = runCatching { ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
                name ?: "Audio scelto"
            }.onSuccess { greetName = it; SettingsStore.greetingName = it; hasGreeting = true; msg = "Audio della segreteria impostato: $it" }
                .onFailure { msg = "Non sono riuscito a copiare l'audio: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TopBar("Chiamate moleste", onBack)

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Protezione dalle chiamate moleste", color = NaviCyan)
                    Label(if (on) "Attiva: i numeri in black list vengono fermati." else "Spenta: nessuna chiamata viene toccata.")
                }
                Switch(on, { on = it; SettingsStore.callGuard = it; if (it && !perms) permLauncher.launch(callPermissions()) })
            }
            Label("Come funziona: il Navi guarda il registro chiamate (solo sul telefono) e ti propone i numeri che chiamano spesso e riattaccano. " +
                "Decidi tu chi bloccare. Un numero bloccato viene rifiutato senza farti squillare il telefono. Se richiama, il Navi risponde con il tuo audio, " +
                "ascolta qualche secondo per farti un riassunto e riaggancia. Non viene registrato niente.")
        }

        Panel {
            Text("Cosa serve", color = NaviCyan)
            StatusRow("Registro chiamate, rubrica e gestione chiamate", "Per i suggerimenti e per rifiutare o rispondere da solo.", perms) { permLauncher.launch(callPermissions()) }
            StatusRow("Filtro chiamate di Android", "Ferma i numeri sconosciuti prima che squilli. Android chiede di scegliere NetNavi come app \"ID chiamante e spam\".", role) {
                NaviCallScreening.roleIntent(ctx)?.let { roleLauncher.launch(it) } ?: run { msg = "Questo telefono non offre il filtro chiamate: userò il rifiuto automatico." } }
            StatusRow("Microfono · facoltativo", "Solo per il riassunto di chi lascia un messaggio. L'audio non viene salvato.", mic) { permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
            Label("Per i contatti in rubrica il filtro di Android di solito non interviene: in quel caso il telefono squilla un istante e poi il Navi chiude o risponde.")
        }

        Panel {
            Text("Segreteria del Navi", color = NaviCyan)
            Label(if (hasGreeting) "Audio attuale: ${greetName.ifBlank { "impostato" }}" else "Nessun audio: senza audio il Navi rifiuta e basta, non risponde mai.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }) { Text("Scegli canzone o audio") }
                if (hasGreeting) OutlinedButton(onClick = { playing = CallResponder.previewToggle(ctx) }) { Text(if (playing) "Ferma" else "Ascolta") }
            }
            Text("Oppure fallo dire alla voce del Navi", color = Color.White, fontSize = 14.sp)
            OutlinedTextField(greetText, { greetText = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("Messaggio") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = greetText.isNotBlank(), onClick = {
                    SettingsStore.greetingText = greetText
                    if (!NaviVoice.isReady) { msg = "La voce italiana non è pronta: controlla la sintesi vocale di Android."; return@OutlinedButton }
                    msg = "Preparo il messaggio..."
                    NaviVoice.synthToFile(greetText, CallGuard.greetingFile) { ok ->
                        scope.launch(Dispatchers.Main) {
                            if (ok) { greetName = "Voce del Navi"; SettingsStore.greetingName = greetName; hasGreeting = true; msg = "Messaggio registrato con la voce del Navi." }
                            else msg = "Non sono riuscito a creare il messaggio."
                        }
                    }
                }) { Text("Usa la voce del Navi") }
                TextButton(onClick = { greetText = SettingsStore.DEFAULT_GREETING }) { Text("Testo predefinito") }
            }
            Text("Risponde dalla chiamata numero", color = Color.White, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2, 3, 5).forEach { n -> FilterChip(after == n, { after = n; SettingsStore.callAnswerAfter = n }, { Text("$n") }) }
            }
            Label("Esempio con $after: le prime ${after - 1} ${if (after == 2) "chiamata viene rifiutata" else "chiamate vengono rifiutate"} in silenzio; dalla ${after}ª in 7 giorni risponde il Navi.")
            Text("Ascolto per il riassunto", color = Color.White, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "No", 15 to "15 s", 30 to "30 s").forEach { (v, l) -> FilterChip(listen == v, { listen = v; SettingsStore.callListenSec = v }, { Text(l) }) }
            }
            Label("Dopo il tuo audio il Navi ascolta per questo tempo e ti dice solo l'essenziale (tema, azienda, numero citato, possibile truffa). Il testo resta in memoria pochi secondi e viene cancellato.")
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Suggerimenti del Navi", color = NaviCyan, modifier = Modifier.weight(1f))
                TextButton(enabled = perms && !loading, onClick = { refresh() }) { Text("Aggiorna") }
            }
            if (!perms) Label("Serve l'accesso al registro chiamate.")
            else if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (sugg.isEmpty()) Label("Nessun numero sospetto negli ultimi 30 giorni.")
            sugg.forEach { s ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${s.label} · ${CallGuard.pretty(s.number)}", color = Color.White, fontSize = 15.sp)
                    Label(s.reason)
                    Row {
                        Button(onClick = { if (s.inContacts) confirm = s else { CallGuard.block(s); sugg = sugg - s } }) { Text("Blocca") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { CallGuard.ignore(s.number); sugg = sugg - s }) { Text("Non è molesto") }
                    }
                }
            }
        }

        Panel {
            Text("Black list (${blocked.size})", color = NaviCyan)
            if (blocked.isEmpty()) Label("Vuota. Si riempie solo con i suggerimenti che accetti.")
            blocked.forEach { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${b.label} · ${CallGuard.pretty(b.number)}", color = Color.White, fontSize = 14.sp)
                        Label("Bloccato il ${fmt.format(Date(b.addedAt))} · ha richiamato ${b.attempts.size} volte")
                    }
                    TextButton(onClick = { CallGuard.unblock(b.number) }) { Text("Sblocca") }
                }
            }
            HorizontalDivider(color = Color(0x33FFFFFF))
            Text("Prova la segreteria", color = Color.White, fontSize = 14.sp)
            Label("Metti il numero di un amico o un tuo secondo telefono: resta in black list per 24 ore e alla prima chiamata risponde subito il Navi.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(test, { test = it }, Modifier.weight(1f), singleLine = true, label = { Text("Numero") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Spacer(Modifier.width(8.dp))
                Button(enabled = CallGuard.normalize(test).length >= 5, onClick = { CallGuard.addTest(test); test = ""; msg = "Numero di prova aggiunto per 24 ore." }) { Text("Aggiungi") }
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chiamate gestite", color = NaviCyan, modifier = Modifier.weight(1f))
                if (log.isNotEmpty()) TextButton(onClick = { CallGuard.clearLog() }) { Text("Svuota") }
            }
            if (log.isEmpty()) Label("Ancora nessuna.")
            log.asReversed().take(30).forEach { h ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("${fmt.format(Date(h.at))} · ${h.label.ifBlank { CallGuard.pretty(h.number) }}", color = Color.White, fontSize = 14.sp)
                    Label(h.action + if (h.seconds > 0) " · ${h.seconds} s" else "")
                    if (h.summary.isNotBlank()) Text(h.summary, color = Color(0xFFE6EDF7), fontSize = 13.sp)
                }
            }
        }

        Panel {
            Text("Limiti da sapere", color = WarnC)
            Label("Android non permette alle app di mandare audio direttamente nella linea: il tuo audio esce dal vivavoce e arriva all'altra persona dal microfono, quindi si sente come in vivavoce. " +
                "Il riassunto dipende dal riconoscimento vocale offline del telefono e dalla qualità della chiamata: a volte riuscirò solo a dirti per quanto tempo ha parlato. " +
                "I numeri nascosti non si possono riconoscere.")
        }
        msg?.let { Text(it, color = NaviCyan) }
    }

    confirm?.let { s ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Bloccare un contatto?") },
            text = { Text("${s.label} è nella tua rubrica. Bloccalo solo se ti disturba davvero: le sue chiamate non ti arriveranno più.") },
            confirmButton = { TextButton(onClick = { CallGuard.block(s); sugg = sugg - s; confirm = null }) { Text("Blocca") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annulla") } })
    }
}

@Composable
private fun StatusRow(title: String, why: String, ok: Boolean, action: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (ok) "[OK] " else "") + title, color = if (ok) OkC else Color.White, fontSize = 14.sp)
            Label(why)
        }
        if (!ok) Button(onClick = action) { Text("Attiva") }
    }
