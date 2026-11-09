package com.tuo.navi.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuo.navi.avatar.BodyClassifier
import com.tuo.navi.avatar.BodyProfile
import com.tuo.navi.avatar.Pixelizer
import com.tuo.navi.avatar.Segmenter
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.domain.BodyBuild
import com.tuo.navi.domain.NaviClass
import com.tuo.navi.domain.NaviProfile
import com.tuo.navi.domain.Stature
import com.tuo.navi.habits.HabitTracker
import com.tuo.navi.sprites.PlaceholderSprites
import com.tuo.navi.sprites.SpriteStore
import com.tuo.navi.transfer.NaviTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
private fun StepHeader(step: Int, total: Int, title: String) {
    Label("Passo $step di $total")
    LinearProgressIndicator(progress = { step / total.toFloat() }, Modifier.fillMaxWidth().padding(vertical = 6.dp), color = NaviCyan)
    Text(title, color = NaviCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Body(t: String) = Text(t, color = Color.White, fontSize = 15.sp)

/** Elabora un'immagine: ritaglio, classe, mezzo busto e pixel art. */
/** Figura intera dell'ultima immagine elaborata (sfondo del pannello). */
var lastFullCutout: Bitmap? = null

suspend fun processImage(src: Bitmap, heightCm: Int): Triple<BodyProfile, Bitmap, Bitmap> {
    val cut = Segmenter.trim(Segmenter.cutout(src))
    val prof = withContext(Dispatchers.Default) { BodyClassifier.classify(cut, heightCm) }
    val pix = withContext(Dispatchers.Default) { Pixelizer.pixelate(cut) }
    lastFullCutout = fullForBackdrop(cut)
    return Triple(prof, Segmenter.bust(cut), pix)
}

/** Riduce la figura a max 1280 px di altezza: basta per lo sfondo e pesa poco. */
fun fullForBackdrop(cut: Bitmap): Bitmap {
    val maxH = 1280
    return if (cut.height <= maxH) cut else Bitmap.createScaledBitmap(cut, (cut.width * maxH.toFloat() / cut.height).toInt().coerceAtLeast(1), maxH, true)
}

fun decodeImage(ctx: android.content.Context, uri: Uri): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        if (maxOf(info.size.width, info.size.height) > 2000) d.setTargetSampleSize(2)
    }.copy(Bitmap.Config.ARGB_8888, false)
}.getOrNull()

/**
 * Procedura guidata del primo avvio:
 * 1 Benvenuto · 2 Nome e altezza · 3 Aspetto (immagine tua o provvisorio) · 4 Conferma definitiva.
 */
@Composable
fun SetupWizard(onGuide: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(1) }
    var name by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("170") }
    var useImage by remember { mutableStateOf<Boolean?>(null) }
    var stocky by remember { mutableStateOf(false) }
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Triple<BodyProfile, Bitmap, Bitmap>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var importPw by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        src = decodeImage(ctx, uri); result = null; msg = null
        val s = src ?: run { msg = "Immagine non leggibile"; return@rememberLauncherForActivityResult }
        busy = true
        scope.launch {
            runCatching { processImage(s, height.toIntOrNull() ?: 170) }
                .onSuccess { result = it }.onFailure { msg = "Non riesco a ritagliare il personaggio: ${it.message}. Prova un'immagine con sfondo semplice, oppure usa l'avatar provvisorio." }
            busy = false
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importUri = it }

    if (ProfileStore.isLockedByTransfer()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Questo avatar è stato trasferito su un altro telefono.", color = NaviCyan, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Body("Per crearne uno nuovo qui disinstalla e reinstalla l'app.")
        }
        return
    }

    val h = height.toIntOrNull() ?: 0
    val placeholderClass = when {
        stocky && h >= BodyClassifier.TALL_CM -> NaviClass.TANK
        stocky -> NaviClass.DEFENDER
        h >= BodyClassifier.TALL_CM -> NaviClass.WARRIOR
        else -> NaviClass.MAGE
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (step) {
            1 -> {
                StepHeader(1, 4, "Benvenuto in NetNavi")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { NaviSprite("wave", true, 160.dp, null) }
                Body("Stai per creare il tuo Navi: un compagno digitale che vive nel telefono. " +
                    "Ti avvisa delle notifiche (solo app e mittente), imposta sveglie, timer, note e appuntamenti, e impara le tue abitudini.")
                Panel { Text("Il nome resta per sempre. L'aspetto potrai cambiarlo quando vuoi dalle Impostazioni (la classe viene ricalcolata, livello e ricordi restano).", color = Color(0xFFFFC857), fontSize = 13.sp) }
                Button(onClick = { step = 2 }, Modifier.fillMaxWidth()) { Text("Inizia") }
                OutlinedButton(onClick = { importPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Ho già un Navi (importa file .navi)") }
                TextButton(onClick = onGuide) { Text("Leggi la guida") }
            }
            2 -> {
                StepHeader(2, 4, "Come si chiama?")
                OutlinedTextField(name, { name = it.take(16) }, label = { Text("Nome del Navi") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(height, { height = it.filter(Char::isDigit).take(3) }, label = { Text("Altezza del personaggio in cm") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                Label("L'altezza decide la statura: da ${BodyClassifier.TALL_CM} cm in su è alto (Tank o Guerriero), sotto è basso (Difensore o Mago).")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { step = 1 }) { Text("Indietro") }
                    Button(enabled = name.isNotBlank() && h in 50..300, onClick = { step = 3 }) { Text("Avanti") }
                }
            }
            3 -> {
                StepHeader(3, 4, "Aspetto di ${name.trim()}")
                Body("Puoi usare un'immagine del tuo personaggio a figura intera, oppure partire con un Navi provvisorio già animato.")
                Panel {
                    Text("Avatar provvisorio", color = NaviCyan, fontSize = 17.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NaviSprite("walk", true, 96.dp, null, Modifier.background(Color(0xFF13264A), RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Label("Potrai sostituirlo con la tua immagine quando vuoi, dalle Impostazioni > Cambia aspetto.")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Label("Corporatura robusta"); Switch(stocky, { stocky = it })
                            }
                            Label("Classe: ${placeholderClass.label}")
                        }
                    }
                    Button(onClick = { useImage = false; step = 4 }) { Text("Usa il provvisorio") }
                }
                Panel {
                    Text("La mia immagine", color = NaviCyan, fontSize = 17.sp)
                    Label("Figura intera, un solo personaggio, sfondo semplice. Il foglio con più pose va ritagliato prima sulla vista frontale.")
                    Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Scegli immagine") }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    result?.let { (prof, bust, pix) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Image(bust.asImageBitmap(), null, Modifier.size(100.dp))
                            Image(pix.asImageBitmap(), null, Modifier.size(100.dp), filterQuality = FilterQuality.None)
                        }
                        Label("Classe: ${prof.cls.label} (corporatura ${if (prof.build == BodyBuild.STOCKY) "robusta" else "snella"})")
                        Button(onClick = { useImage = true; step = 4 }) { Text("Usa questa immagine") }
                    }
                }
                msg?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp) }
                OutlinedButton(onClick = { step = 2 }) { Text("Indietro") }
            }
            4 -> {
                StepHeader(4, 4, "Conferma")
                val cls = if (useImage == true) result!!.first.cls else placeholderClass
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (useImage == true) Image(result!!.third.asImageBitmap(), null, Modifier.size(110.dp), filterQuality = FilterQuality.None)
                    else NaviSprite("idle", true, 110.dp, null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(name.trim(), color = NaviCyan, fontSize = 22.sp)
                        Body("Classe ${cls.label}"); Label(cls.role)
                        Label("Altezza $h cm · ${if (useImage == true) "immagine tua" else "avatar provvisorio"}")
                    }
                }
                Panel { Text("Dopo la conferma il nome diventa definitivo. Aspetto e classe potrai cambiarli dalle Impostazioni.", color = Color(0xFFFFC857), fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { step = 3 }) { Text("Indietro") }
                    Button(onClick = {
                        runCatching {
                            val id = UUID.randomUUID().toString()
                            if (useImage == true) {
                                val (prof, bust, pix) = result!!
                                ProfileStore.create(NaviProfile(id, name.trim(), h, prof.build, prof.stature, prof.cls, prof.ratio,
                                    System.nanoTime(), System.currentTimeMillis()), bust, pix, lastFullCutout)
                            } else {
                                val portrait = PlaceholderSprites.portrait()
                                ProfileStore.create(NaviProfile(id, name.trim(), h, if (stocky) BodyBuild.STOCKY else BodyBuild.SLIM,
                                    if (h >= BodyClassifier.TALL_CM) Stature.TALL else Stature.SHORT, placeholderClass, 0f,
                                    System.nanoTime(), System.currentTimeMillis(), provisional = true), portrait, portrait)
                            }
                        }.onFailure { msg = it.message }
                    }) { Text("Crea ${name.trim()}") }
                }
                msg?.let { Text(it, color = Color(0xFFFF6B6B)) }
            }
        }
    }

    importUri?.let { uri ->
        AlertDialog(onDismissRequest = { importUri = null }, title = { Text("Importa avatar") },
            text = { OutlinedTextField(importPw, { importPw = it }, label = { Text("Password del file") }, visualTransformation = PasswordVisualTransformation(), singleLine = true) },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { NaviTransfer.import(ctx, uri, importPw) } }.onFailure { msg = it.message }
                    importUri = null; importPw = ""
                }
            }) { Text("Importa") } },
            dismissButton = { TextButton(onClick = { importUri = null }) { Text("Annulla") } })
    }
}

/** Dopo la creazione: permessi uno per uno e mini tutorial. */
@Composable
fun PermissionsSetup(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(1) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val name = ProfileStore.profile.collectAsState().value?.name ?: "Navi"

    @Composable
    fun PermRow(title: String, why: String, ok: Boolean, action: () -> Unit) = Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (ok) "[OK] " else "") + title, color = if (ok) Color(0xFF7CFF6B) else Color.White, fontSize = 15.sp)
                Label(why)
            }
            if (!ok) Button(onClick = action) { Text("Attiva") }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (step == 1) {
            Label("Configurazione")
            Text("$name ha bisogno di alcuni permessi", color = NaviCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Body("Tocca \"Attiva\", abilita NetNavi nella schermata che si apre e torna indietro. Lo stato si aggiorna da solo.")
            val listener = remember(tick) { Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true }
            val acc = remember(tick) { com.tuo.navi.home.HomeScene.accessibilityOn(ctx) }
            val usage = remember(tick) { HabitTracker.hasUsageAccess(ctx) }
            val battery = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
            PermRow("Accesso alle notifiche", "Per dirti \"messaggio su WhatsApp da Marco\". Il testo non viene letto.", listener) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            PermRow("Navi sulla Home (accessibilità)", "Per camminare sopra le icone della tua Home e accorgersi quando cambi pagina. Legge solo nome e posizione delle icone della Home.", acc) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            if (!acc || !listener) Panel {
                Text("Interruttore grigio o \"Impostazione con restrizioni\"?", color = Color(0xFFFFC857), fontSize = 14.sp)
                Label("Android blocca questi due permessi per le app installate da file APK. Sbloccali una volta: Info app NetNavi > menu con i tre puntini in alto a destra > \"Consenti impostazioni con restrizioni\". Poi torna qui e tocca di nuovo \"Attiva\".")
                TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }) { Text("Apri Info app NetNavi") }
                Label("Nell'elenco Accessibilità cerca \"NetNavi - Navi sulla Home\" (a volte è dentro \"App installate\" o \"Servizi scaricati\").")
            }
            PermRow("Dati di utilizzo", "Per imparare le abitudini e trovare le app che non usi.", usage) {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            PermRow("Nessuna ottimizzazione batteria", "Così Android non lo spegne in background.", battery) {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
            Label("Puoi saltare e farlo dopo dalle Impostazioni del Navi.")
            Button(onClick = { step = 2 }, Modifier.fillMaxWidth()) { Text("Avanti") }
        } else {
            Label("Ultimo passo")
            Text("Come parlare con $name", color = NaviCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { NaviSprite("talk", true, 120.dp, ProfileStore.loadPixel()) }
            listOf(
                "Premi il tasto Home: $name compare sopra la tua Home, cammina, salta sulle icone e le osserva.",
                "Quando cambi pagina se ne accorge ed esplora quella nuova. Quando apri un'app sparisce e torna con la Home.",
                "Ti avvisa con un fumetto delle notifiche (solo app e mittente) e delle app che non usi da molto.",
                "Toccalo per aprire la sua schermata: Parla con $name, Sveglia, Timer, Nota, Appuntamento, Impostazioni.",
                "Trascinalo con il dito per spostarlo: poi cade sull'icona o sul fondo sotto di lui.",
                "Oppure scrivi o detta frasi come: \"sveglia domani alle 7\", \"timer 10 minuti\", \"nota: comprare il latte\", \"ricordami venerdì alle 9 di chiamare Marco\".",
                "Scrivi \"aiuto\" per vedere tutti i comandi.",
                "Chiedi \"cos'è WhatsApp\" per farti spiegare un'app.",
                "Aggiungi il riquadro \"Chiama Navi\" nelle Impostazioni rapide (tendina in alto, matita)."
            ).forEach { Text("• $it", color = Color.White, fontSize = 14.sp) }
            Button(onClick = { SettingsStore.setupDone = true; onDone() }, Modifier.fillMaxWidth()) { Text("Inizia a usare $name") }
        }
    }
}

/**
 * Cambia l'aspetto del Navi quando vuoi: nuova immagine (e altezza), la classe viene ricalcolata.
 * Nome, livello, EXP, legame, note e ricordi restano.
 */
@Composable
fun ChangeAppearancePanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p by ProfileStore.profile.collectAsState()
    val cur = p ?: return
    var open by remember { mutableStateOf(cur.provisional) }
    var hText by remember(cur.heightCm) { mutableStateOf(cur.heightCm.toString()) }
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Triple<BodyProfile, Bitmap, Bitmap>?>(null) }
    var buildOverride by remember { mutableStateOf<BodyBuild?>(null) }
    var resetSprites by remember { mutableStateOf(SpriteStore.importedCount() > 0) }
    var replaceFull by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val h = hText.toIntOrNull()?.takeIf { it in 80..250 }
    fun analyze() {
        val s = src ?: return; val hh = h ?: return
        busy = true
        scope.launch { runCatching { processImage(s, hh) }.onSuccess { result = it }.onFailure { msg = it.message }; busy = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        src = uri?.let { decodeImage(ctx, it) } ?: return@rememberLauncherForActivityResult
        msg = null; analyze()
    }
    val build = buildOverride ?: result?.first?.build
    val stature = h?.let { if (it >= BodyClassifier.TALL_CM) Stature.TALL else Stature.SHORT }
    val cls = if (build != null && stature != null) when {
        build == BodyBuild.STOCKY && stature == Stature.TALL -> NaviClass.TANK
        build == BodyBuild.STOCKY -> NaviClass.DEFENDER
        stature == Stature.TALL -> NaviClass.WARRIOR
        else -> NaviClass.MAGE
    } else null

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cambia aspetto del Navi", color = NaviCyan)
                Label("Ora: classe ${cur.cls.label}, ${cur.heightCm} cm" + if (cur.provisional) ", avatar provvisorio" else "")
            }
            TextButton(onClick = { open = !open }) { Text(if (open) "Chiudi" else "Cambia") }
        }
        if (!open) return@Panel
        Label("Scegli una nuova immagine (meglio a figura intera, sfondo semplice). La classe viene ricalcolata da corporatura e altezza: " +
            "alto e robusto Tank, alto e snello Guerriero, basso e snello Mago, basso e robusto Difensore. Nome, livello, EXP, legame e ricordi restano.")
        OutlinedTextField(hText, { hText = it.filter(Char::isDigit).take(3); if (src != null && it.toIntOrNull() in 80..250) analyze() },
            label = { Text("Altezza in cm") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        if (h == null) Label("Scrivi un'altezza tra 80 e 250 cm.")
        Button(enabled = h != null && !busy, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text(if (src == null) "Scegli immagine" else "Scegli un'altra immagine") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { (prof, bust, pix) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(bust.asImageBitmap(), null, Modifier.size(90.dp))
                Image(pix.asImageBitmap(), null, Modifier.size(90.dp), filterQuality = FilterQuality.None)
            }
            Text("Corporatura", color = Color.White, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(buildOverride == null, { buildOverride = null }, { Text("Automatica (${if (prof.build == BodyBuild.STOCKY) "robusta" else "snella"})", fontSize = 12.sp) })
                FilterChip(buildOverride == BodyBuild.SLIM, { buildOverride = BodyBuild.SLIM }, { Text("Snella", fontSize = 12.sp) })
                FilterChip(buildOverride == BodyBuild.STOCKY, { buildOverride = BodyBuild.STOCKY }, { Text("Robusta", fontSize = 12.sp) })
            }
            cls?.let { Text("Nuova classe: ${it.label}" + if (it != cur.cls) " (prima ${cur.cls.label})" else " (uguale a prima)", color = NaviCyan, fontSize = 15.sp); Label(it.role) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(replaceFull, { replaceFull = it })
                Text("Usa questa immagine anche nella scheda del personaggio", color = Color.White, fontSize = 13.sp)
            }
            if (SpriteStore.importedCount() > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(resetSprites, { resetSprites = it })
                Text("Togli le animazioni importate del vecchio aspetto (consigliato: verranno rigenerate dalla nuova immagine)", color = Color.White, fontSize = 13.sp)
            }
            Button(enabled = cls != null, onClick = { ask = true }) { Text("Applica il nuovo aspetto") }
        }
        msg?.let { Label(it) }
    }

    if (ask) AlertDialog(onDismissRequest = { ask = false }, title = { Text("Cambiare aspetto?") },
        text = { Text("Il Navi avrà la nuova immagine e la classe ${cls?.label}. Le statistiche si ricalcolano con la nuova classe, il livello resta lo stesso. Puoi cambiarlo di nuovo quando vuoi.") },
        confirmButton = { TextButton(onClick = {
            ask = false
            val (prof, bust, pix) = result ?: return@TextButton
            runCatching {
                ProfileStore.changeAppearance(bust, pix, h!!, build!!, stature!!, cls!!, prof.ratio, if (replaceFull) lastFullCutout else null)
                if (resetSprites) SpriteStore.resetAll() else SpriteStore.clearCache()
                backdropVersion.intValue++
            }.onSuccess { msg = "Fatto: nuovo aspetto applicato, classe ${cls?.label}."; result = null; src = null; open = false }
                .onFailure { msg = it.message }
        }) { Text("Applica") } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Annulla") } })
}
