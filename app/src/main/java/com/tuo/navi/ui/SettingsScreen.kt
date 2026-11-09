package com.tuo.navi.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuo.navi.ai.AiRouter
import com.tuo.navi.ai.DeviceTier
import com.tuo.navi.ai.DlState
import com.tuo.navi.ai.LocalLlm
import com.tuo.navi.ai.ModelCatalog
import com.tuo.navi.ai.ModelDownloader
import com.tuo.navi.ai.PromptBuilder
import com.tuo.navi.ai.PromptFormat
import com.tuo.navi.avatar.Segmenter
import com.tuo.navi.core.Ability
import com.tuo.navi.data.ProfileStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.horizontalScroll
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.home.HomeNavi
import com.tuo.navi.home.HomeScene
import com.tuo.navi.transfer.NaviTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f), fontSize = 14.sp); Switch(value, onChange)
    }

@Composable
fun SettingsScreen(onBack: () -> Unit, onGuide: () -> Unit, onCalls: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var voice by remember { mutableStateOf(SettingsStore.voiceEnabled) }
    var speakOff by remember { mutableStateOf(SettingsStore.speakScreenOff) }
    var overlay by remember { mutableStateOf(SettingsStore.overlayEnabled) }
    var size by remember { mutableFloatStateOf(SettingsStore.naviSizeDp.toFloat()) }
    var budget by remember { mutableFloatStateOf(SettingsStore.dailyBudget.toFloat()) }
    var useLocal by remember { mutableStateOf(SettingsStore.useLocalModel) }
    var internet by remember { mutableStateOf(SettingsStore.internetAllowed) }
    var askInternet by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(SettingsStore.hfToken) }
    var customUrl by remember { mutableStateOf("") }
    var fmt by remember { mutableStateOf(SettingsStore.modelFormat) }
    val llm by LocalLlm.state.collectAsState()
    val dl by ModelDownloader.state.collectAsState()
    val installed = llm !is com.tuo.navi.ai.LlmState.Missing && LocalLlm.installed()
    var progress by remember { mutableStateOf<String?>(null) }
    var testOut by remember { mutableStateOf<String?>(null) }
    var pw1 by remember { mutableStateOf("") }; var pw2 by remember { mutableStateOf("") }
    var askExport by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var hasFull by remember { mutableStateOf(ProfileStore.hasFull()) }
    var fullBusy by remember { mutableStateOf(false) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            progress = "Controllo e copia del modello..."
            runCatching { LocalLlm.importModel(uri) { b -> progress = "Copiati ${b / (1 shl 20)} MB" } }
                .onSuccess { progress = "Modello installato: $it"; fmt = SettingsStore.modelFormat }
                .onFailure { progress = it.message }
        }
    }
    var customBg by remember { mutableStateOf(com.tuo.navi.data.BackdropStore.isCustom()) }
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { com.tuo.navi.data.BackdropStore.set(ctx, uri) } }
                .onSuccess { customBg = true; msg = "Sfondo della dashboard cambiato." }
                .onFailure { msg = "Sfondo non impostato: ${it.message}" }
        }
    }
    val fullPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val src = uri?.let { decodeImage(ctx, it) } ?: return@rememberLauncherForActivityResult
        fullBusy = true
        scope.launch {
            runCatching { ProfileStore.setFull(fullForBackdrop(Segmenter.trim(Segmenter.cutout(src)))) }
                .onSuccess { hasFull = true; backdropVersion.intValue++; msg = "Immagine della scheda del personaggio cambiata." }
                .onFailure { msg = it.message }
            fullBusy = false
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NaviTransfer.export(ctx, uri, pw1) } }
                .onSuccess { msg = "Avatar esportato e rimosso da questo telefono." }
                .onFailure { msg = "Esportazione fallita: ${it.message}" }
            pw1 = ""; pw2 = ""
        }
    }
    fun openUrl(u: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TopBar("Impostazioni", onBack)
        OutlinedButton(onClick = onGuide, Modifier.fillMaxWidth()) { Text("Guida, regole degli sprite e prompt") }
        PermissionsPanel(onCalls)
        ChangeAppearancePanel()
        Panel {
            Text("Sfondo della dashboard", color = NaviCyan)
            Label(if (customBg) "Stai usando una tua foto. Puoi cambiarla quante volte vuoi." else "Stai usando lo scenario predefinito. Puoi sostituirlo con una tua foto quante volte vuoi.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { bgPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Cambia sfondo") }
                if (customBg) OutlinedButton(onClick = { com.tuo.navi.data.BackdropStore.reset(); customBg = false }) { Text("Ripristina") }
            }
        }
        Panel {
            Text("Immagine della scheda del personaggio", color = NaviCyan)
            Label(if (hasFull) "In \"Le mie caratteristiche\" vedi la figura intera che hai caricato. Puoi cambiarla quando vuoi (viene ritagliata dallo sfondo)."
                  else "In \"Le mie caratteristiche\" ora vedi il busto del Navi. Puoi caricare un'immagine a figura intera quando vuoi (viene ritagliata dallo sfondo).")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !fullBusy, onClick = { fullPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (hasFull) "Cambia immagine" else "Carica immagine") }
                if (hasFull) OutlinedButton(enabled = !fullBusy, onClick = { ProfileStore.clearFull(); hasFull = false; backdropVersion.intValue++; msg = "La scheda ora mostra il busto." }) { Text("Usa il busto") }
            }
            if (fullBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Panel {
            Text("Presenza e voce", color = NaviCyan)
            Toggle("Voce del Navi", voice) { voice = it; SettingsStore.voiceEnabled = it }
            Toggle("Parla anche a schermo spento", speakOff) { speakOff = it; SettingsStore.speakScreenOff = it }
            val density = ctx.resources.displayMetrics.density
            Text("Dimensione del Navi sulla Home: ${size.toInt()} dp (circa ${(size * density).toInt()} pixel sul tuo schermo)", color = Color.White, fontSize = 14.sp)
            Slider(size, { size = (it / 8f).toInt() * 8f }, onValueChangeFinished = { SettingsStore.naviSizeDp = size.toInt(); HomeNavi.refreshSettings() }, valueRange = 48f..256f)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(64 to "Piccolo", 96 to "Medio", 128 to "Grande", 192 to "Molto grande", 256 to "Enorme").forEach { (v, l) ->
                    FilterChip(size.toInt() == v, { size = v.toFloat(); SettingsStore.naviSizeDp = v; HomeNavi.refreshSettings() }, { Text("$l $v", fontSize = 12.sp) })
                }
            }
            Label("Oltre 128 dp il Navi copre più icone: utile se vuoi vederlo bene, ma salta meno sulle icone piccole.")
            Text("Interventi massimi al giorno: ${budget.toInt()}", color = Color.White, fontSize = 14.sp)
            Slider(budget, { budget = it; SettingsStore.dailyBudget = it.toInt() }, valueRange = 5f..120f)
            Row {
                TextButton(onClick = { HomeNavi.sayOnReturn("Test fumetto: eccomi. Così ti avviserò delle notifiche."); msg = "Torna alla Home per vedere il fumetto di prova." }) { Text("Prova fumetto") }
                TextButton(onClick = onCalls) { Text("Chiamate moleste") }
            }
        }
        Panel {
            Text("Privacy e Internet", color = NaviCyan)
            Label("Tutti i dati (memoria, note, abitudini, avatar, chat) restano sul telefono. Nessun dato viene usato per addestrare modelli. " +
                "L'unico modello che \"impara\" è il tuo Navi, attraverso la sua memoria locale.")
            Toggle("Consenti Internet (solo download modelli)", internet) { on ->
                if (on) askInternet = true else { internet = false; SettingsStore.internetAllowed = false; ModelDownloader.cancel() }
            }
            Label(if (internet) "Acceso: l'app può collegarsi solo a Hugging Face per scaricare un modello. Spegnilo quando hai finito."
                  else "Spento: l'app non apre nessuna connessione. Puoi comunque scaricare il modello dal browser e importarlo.")
        }
        Panel {
            Text("IA locale (offline)", color = NaviCyan)
            Label("Il tuo telefono: ${DeviceTier.describe()}")
            Label(if (installed) "Installato: ${LocalLlm.displayName} (${LocalLlm.modelFile.length() / (1 shl 20)} MB) · stato: ${llm.label}" else "Nessun modello installato")
            if (installed) Label("Memoria libera ora: ${LocalLlm.availMb()} MB · servono circa ${LocalLlm.neededMb()} MB")
            Toggle("Usa IA locale per la chat", useLocal) { useLocal = it; SettingsStore.useLocalModel = it; if (!it) LocalLlm.release() }
            Row {
                TextButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text(if (installed) "Sostituisci modello" else "Importa modello .task") }
                if (installed) TextButton(onClick = { LocalLlm.delete(); testOut = null }) { Text("Elimina") }
            }
            if (installed) {
                Text("Formato conversazione", color = Color.White, fontSize = 14.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PromptFormat.entries.forEach { f ->
                        FilterChip(fmt == f.name, { fmt = f.name; SettingsStore.modelFormat = f.name }, { Text(f.label, fontSize = 12.sp) })
                    }
                }
                Label("Automatico: riconosciuto dal nome del file (ora ${LocalLlm.format.label}).")
                TextButton(onClick = {
                    testOut = "Carico il modello e provo..."
                    scope.launch {
                        val t0 = System.currentTimeMillis()
                        testOut = runCatching {
                            PromptBuilder.clean(LocalLlm.generate(PromptBuilder.build(LocalLlm.format, AiRouter.persona(ProfileStore.profile.value), emptyList(), "Presentati in una frase.")) { testOut = it })
                        }.map { "$it\n(${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)} s, caricamento ${LocalLlm.lastLoadMs / 1000.0} s)" }
                            .getOrElse { "Errore: ${it.message}" }
                    }
                }) { Text("Prova IA locale") }
            }
            progress?.let { Label(it) }
            testOut?.let { Text(it, color = Color.White, fontSize = 13.sp) }
        }
        Panel {
            Text("Modelli consigliati per il tuo telefono", color = NaviCyan)
            Label("Tutti in formato .task (MediaPipe). I Gemma richiedono di accedere a Hugging Face e accettare la licenza; i Qwen no.")
            val rec = remember { DeviceTier.recommended() }
            (rec + ModelCatalog.models.filterNot { it in rec }).forEachIndexed { i, m ->
                if (i == rec.size) Text("Altri modelli", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${m.title} · ${m.sizeMb} MB" + (if (m.gated) " · serve account" else " · libero") + (if (i == 0) " · consigliato" else ""),
                        color = if (i == 0) NaviCyan else Color.White, fontSize = 14.sp)
                    Label(m.note)
                    if (DeviceTier.tooBig(m)) Text(if (Ability.BIG_MODELS.unlocked) "Pensato per ${m.minRamGb} GB di RAM: sul tuo telefono potrebbe non partire o essere molto lento."
                        else "Troppo grande per il tuo telefono (abilità \"${Ability.BIG_MODELS.title}\" bloccata).", color = Color(0xFFFFC857), fontSize = 12.sp)
                    Row {
                        TextButton(onClick = { openUrl(m.page) }) { Text("Apri pagina") }
                        val canDl = internet && Ability.IN_APP_DOWNLOAD.unlocked && (!DeviceTier.tooBig(m) || Ability.BIG_MODELS.unlocked) && dl !is DlState.Running
                        TextButton(enabled = canDl, onClick = { ModelDownloader.start(ctx, m.url, m.file) }) { Text("Scarica nell'app") }
                    }
                }
            }
            if (!internet) Label("Per scaricare dall'app accendi \"Consenti Internet\". Altrimenti: Apri pagina > Files and versions > file .task > scarica, poi \"Importa modello .task\".")
            if (internet) {
                OutlinedTextField(token, { token = it }, label = { Text("Token Hugging Face (solo per Gemma)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { SettingsStore.hfToken = token; msg = "Token salvato sul telefono." }) { Text("Salva token") }
                    TextButton(onClick = { openUrl("https://huggingface.co/settings/tokens") }) { Text("Crea token") }
                }
                Label("Il token viene inviato solo a huggingface.co per scaricare il file, mai altrove.")
                OutlinedTextField(customUrl, { customUrl = it }, label = { Text("Oppure incolla il link di un file .task") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = customUrl.contains("huggingface.co") && dl !is DlState.Running, onClick = {
                    ModelDownloader.start(ctx, customUrl, customUrl.substringAfterLast('/').substringBefore('?'))
                }) { Text("Scarica dal link") }
            }
            when (val d = dl) {
                is DlState.Running -> {
                    val pct = if (d.total > 0) d.done.toFloat() / d.total else 0f
                    Text("Download ${d.name}: ${d.done / (1 shl 20)} / ${if (d.total > 0) "${d.total / (1 shl 20)} MB" else "?"}", color = Color.White, fontSize = 13.sp)
                    if (d.total > 0) LinearProgressIndicator(progress = { pct }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { ModelDownloader.cancel() }) { Text("Annulla download") }
                }
                is DlState.Done -> Text("Modello scaricato e installato: ${d.name}. Ora puoi spegnere Internet.", color = Color(0xFF6BFF9E), fontSize = 13.sp)
                is DlState.Failed -> Text(d.msg, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                DlState.Idle -> Unit
            }
        }
        Panel {
            Text("Trasferisci avatar", color = NaviCyan)
            Text("Crea un file .navi cifrato con profilo, sprite, note, abitudini e memoria del Navi. Dopo l'esportazione l'avatar viene CANCELLATO da questo telefono. " +
                "Se perdi il file o la password, l'avatar è perso.", color = Color(0xFFFFC857), fontSize = 13.sp)
            OutlinedTextField(pw1, { pw1 = it }, label = { Text("Password (min 6)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pw2, { pw2 = it }, label = { Text("Ripeti password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = pw1.length >= 6 && pw1 == pw2, onClick = { askExport = true }) { Text("Trasferisci avatar") }
        }
        msg?.let { Text(it, color = NaviCyan) }
    }

    if (askInternet) AlertDialog(onDismissRequest = { askInternet = false }, title = { Text("Attivare Internet?") },
        text = { Text("Con Internet acceso l'app potrà collegarsi solo a Hugging Face per scaricare un modello. Nessun tuo dato (memoria, note, notifiche, chat) viene inviato.\n\n" +
            "Attenzione però: ogni connessione espone comunque alcune informazioni, cioè il tuo indirizzo IP, quale modello scarichi e, se lo usi, il tuo account Hugging Face tramite il token. " +
            "Consiglio: accendi solo per il download, poi spegni.") },
        confirmButton = { TextButton(onClick = { askInternet = false; internet = true; SettingsStore.internetAllowed = true }) { Text("Ho capito, attiva") } },
        dismissButton = { TextButton(onClick = { askInternet = false }) { Text("Lascia spento") } })

    if (askExport) AlertDialog(onDismissRequest = { askExport = false }, title = { Text("Conferma trasferimento") },
        text = { Text("Il Navi lascerà questo telefono. Continuare?") },
        confirmButton = { TextButton(onClick = { askExport = false; exportPicker.launch("mio-navi.navi") }) { Text("Esporta") } },
        dismissButton = { TextButton(onClick = { askExport = false }) { Text("Annulla") } })
}
