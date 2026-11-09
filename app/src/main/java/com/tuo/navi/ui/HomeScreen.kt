package com.tuo.navi.ui

import androidx.core.graphics.drawable.toBitmap
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuo.navi.ai.AiRouter
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.domain.Mood
import com.tuo.navi.core.NotifLog
import com.tuo.navi.home.HomeScene
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import com.tuo.navi.habits.HabitTracker
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { HOME, NOTES, SPRITES, GUIDE, SETTINGS, MEMORY, ABILITIES, NOTIFS, UNUSED, TRAITS, FILES, SECURITY, CALLS }

/**
 * Dashboard del Navi: si apre toccando il Navi sulla Home (o dall'icona NetNavi).
 * Disposizione: intestazione, fumetto di benvenuto, quattro sezioni, sfondo libero al centro,
 * notifiche recenti, comandi rapidi e tasto Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: NaviViewModel, openChat: Boolean, onChatOpened: () -> Unit, nav: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val p by vm.profile.collectAsState()
    val apps by vm.apps.collectAsState()
    val line by vm.line.collectAsState()
    val sugg by vm.suggestion.collectAsState()
    val unused by vm.unused.collectAsState()
    val navTo by vm.navTo.collectAsState()
    val mem by com.tuo.navi.brain.MemoryStore.items.collectAsState()
    var chatOpen by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf<Quick?>(null) }
    var showPerms by remember { mutableStateOf(false) }
    val pixel = remember(p?.id, p?.cls, p?.heightCm, p?.provisional, backdropVersion.intValue) { ProfileStore.loadPixel() }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    LaunchedEffect(openChat) { if (openChat) { chatOpen = true; onChatOpened() } }
    LaunchedEffect(navTo) { navTo?.let { chatOpen = false; vm.navHandled(); nav(it) } }
    val missingPerms = rememberMissingPermissions()

    val prof = p ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullH = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().heightIn(min = fullH).statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                // Intestazione
                Row(Modifier.fillMaxWidth().goldFrame().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(78.dp).goldFrame(shape = CircleShape).clickable { chatOpen = true }, contentAlignment = Alignment.Center) {
                        NaviSprite(if (line != null) "talk" else "idle", true, 70.dp, pixel)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(prof.name, color = Color(0xFFF3E6C8), fontFamily = SerifFont, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val lv = com.tuo.navi.core.Leveling.effective(prof)
                        Text("LV $lv${if (lv >= 100) " MAX" else ""}", color = Gold, fontSize = 13.sp)
                        LinearProgressIndicator(progress = { if (lv >= 100) 1f else prof.exp / prof.expToNext.toFloat() },
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).height(5.dp), color = NaviCyan, trackColor = Color(0xFF1A2440))
                        Text("EXP reale: LV ${prof.level}, ${prof.exp}/${prof.expToNext}", color = Color(0xFFBFC8DA), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.widthIn(max = 128.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoLine(com.tuo.navi.R.drawable.ic_q_moon, "Umore: ${prof.mood.label}")
                        InfoLine(com.tuo.navi.R.drawable.ic_q_chip, "IA: ${AiRouter.mode()}")
                        InfoLine(com.tuo.navi.R.drawable.ic_q_book, "Ricordi: ${mem.size}")
                    }
                }

                // Fumetto di benvenuto (o l'ultima frase del Navi)
                Row(Modifier.fillMaxWidth().goldFrame().clickable { chatOpen = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(com.tuo.navi.R.drawable.ic_q_star), null, Modifier.size(34.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(NaviCyan))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val s = sugg?.let { su -> apps.firstOrNull { it.pkg == su.pkg } }
                        Text(line?.second ?: s?.let { "Di solito a quest'ora apri ${it.label}. La apro?" }
                            ?: "Ciao, sono ${prof.name}. Toccami per parlare, o usa i pulsanti qui sotto.",
                            color = Color(0xFFF3E6C8), fontFamily = SerifFont, fontStyle = FontStyle.Italic, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (line == null && s != null) Row {
                            TextButton(onClick = { vm.acceptSuggestion() }) { Text("Apri", color = NaviCyan) }
                            TextButton(onClick = { vm.dismissSuggestion() }) { Text("No", color = Color(0xFFBFC8DA)) }
                        }
                    }
                }

                // Sezioni principali
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashTile(com.tuo.navi.R.drawable.ic_q_chat, "Parla con ${prof.name}", Modifier.weight(1f), glow = true) { chatOpen = true }
                    DashTile(com.tuo.navi.R.drawable.ic_q_apps, "App che non usi da molto", Modifier.weight(1f), badge = unused.size) { nav(Screen.UNUSED) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashTile(com.tuo.navi.R.drawable.ic_q_bell, "Notifiche recenti", Modifier.weight(1f)) { nav(Screen.NOTIFS) }
                    DashTile(com.tuo.navi.R.drawable.ic_q_book, "Le mie caratteristiche", Modifier.weight(1f)) { nav(Screen.TRAITS) }
                }
                val secLast by com.tuo.navi.security.SecurityCenter.last.collectAsState()
                LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.tuo.navi.security.SecurityCenter.loadLast() } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashTile(com.tuo.navi.R.drawable.ic_q_photos, "Foto e video pesanti", Modifier.weight(1f), height = 52.dp) { nav(Screen.FILES) }
                    DashTile(com.tuo.navi.R.drawable.ic_q_shield, "Controllo sistema", Modifier.weight(1f), height = 52.dp,
                        badge = secLast?.problems ?: 0) { nav(Screen.SECURITY) }
                }
                val dashCtx = LocalContext.current
                var callSugg by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) { callSugg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (com.tuo.navi.data.SettingsStore.callGuard) runCatching { com.tuo.navi.calls.CallGuard.currentSuggestions(dashCtx).size }.getOrDefault(0) else 0 } }
                DashTile(com.tuo.navi.R.drawable.ic_q_phone, "Chiamate moleste", Modifier.fillMaxWidth(), height = 48.dp, badge = callSugg) { nav(Screen.CALLS) }
                if (missingPerms.isNotEmpty()) Text("Mancano ${missingPerms.size} permessi: tocca qui per attivarli.", color = Color(0xFFFFC857), fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().goldFrame().clickable { nav(Screen.SETTINGS) }.padding(10.dp))

                // Spazio libero: si vede lo sfondo
                Spacer(Modifier.weight(1f).heightIn(min = 40.dp))

                // Notifiche recenti
                val recent = remember(now, line) { NotifLog.recent().take(5) }
                Column(Modifier.fillMaxWidth().goldFrame().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FantasyTitle("Notifiche recenti", "Mostra tutte") { nav(Screen.NOTIFS) }
                    if (recent.isEmpty()) Text("Nessuna notifica da quando il Navi è attivo.", color = Color(0xFFBFC8DA), fontSize = 13.sp)
                    recent.forEach { n -> NotifRow(n) }
                }

                // Comandi rapidi
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickTile(com.tuo.navi.R.drawable.ic_q_alarm, "Sveglia", Modifier.weight(0.9f)) { quick = Quick.ALARM }
                    QuickTile(com.tuo.navi.R.drawable.ic_q_timer, "Timer", Modifier.weight(0.9f)) { quick = Quick.TIMER }
                    QuickTile(com.tuo.navi.R.drawable.ic_q_note, "Nota", Modifier.weight(0.9f)) { quick = Quick.NOTE }
                    QuickTile(com.tuo.navi.R.drawable.ic_q_event, "Appuntamento", Modifier.weight(1.4f)) { quick = Quick.EVENT }
                    QuickTile(com.tuo.navi.R.drawable.ic_q_settings, "Impostazioni", Modifier.weight(1.4f)) { nav(Screen.SETTINGS) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Row(Modifier.widthIn(min = 200.dp).goldFrame().clickable { (ctx as? Activity)?.finish() }.padding(horizontal = 28.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Image(painterResource(com.tuo.navi.R.drawable.ic_q_home), null, Modifier.size(22.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Gold))
                        Spacer(Modifier.width(8.dp))
                        Text("Home", color = Color(0xFFF3E6C8), fontFamily = SerifFont, fontSize = 18.sp)
                    }
                }
            }
        }
    }

    quick?.let { QuickDialog(vm, it) { quick = null } }
    if (showPerms) AlertDialog(onDismissRequest = { showPerms = false }, confirmButton = { TextButton(onClick = { showPerms = false }) { Text("Chiudi") } },
        title = { Text("Permessi del Navi") }, text = { PermissionCards() })
    if (chatOpen) ModalBottomSheet(onDismissRequest = { chatOpen = false }, containerColor = NaviPanel) { ChatPanel(vm) }
}

@Composable
private fun InfoLine(icon: Int, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Image(painterResource(icon), null, Modifier.size(14.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Gold))
    Spacer(Modifier.width(5.dp))
    Text(text, color = Color(0xFFE8DCC0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun DashTile(icon: Int, text: String, modifier: Modifier = Modifier, glow: Boolean = false, badge: Int = 0, height: androidx.compose.ui.unit.Dp = 64.dp, onClick: () -> Unit) {
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(height).goldFrame(glow).clickable { onClick() }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (glow) NaviCyan else Gold))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color(0xFFF3E6C8), fontFamily = SerifFont, fontSize = 13.sp, lineHeight = 15.sp,
                modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (badge > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-6).dp).size(22.dp)
            .background(Color(0xFFC62828), CircleShape), contentAlignment = Alignment.Center) {
            Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun QuickTile(icon: Int, text: String, modifier: Modifier, onClick: () -> Unit) =
    Column(modifier.height(62.dp).goldFrame().clickable { onClick() }.padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(icon), null, Modifier.size(24.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Gold))
        Spacer(Modifier.height(3.dp))
        Text(text, color = Color(0xFFF3E6C8), fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }

/** Riga di notifica: ora, icona dell'app, app e mittente (mai il testo del messaggio). */
@Composable
fun NotifRow(n: com.tuo.navi.core.NaviNotif) {
    val ctx = LocalContext.current
    val icon = remember(n.pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(n.pkg).toBitmap(48, 48).asImageBitmap() }.getOrNull()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date(n.time)), color = Color(0xFFE8DCC0), fontSize = 13.sp, modifier = Modifier.width(46.dp))
        if (icon != null) Image(icon, null, Modifier.size(20.dp)) else Spacer(Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(n.appName + (n.sender?.let { "  ·  $it" } ?: ""), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Elenco dei permessi mancanti, ricontrollato ogni 3 secondi. */
@Composable
fun rememberMissingPermissions(): List<String> {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    return remember(tick) {
        buildList {
            if (!HomeScene.accessibilityOn(ctx)) add("accessibilità")
            if (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) != true) add("notifiche")
            if (!HabitTracker.hasUsageAccess(ctx)) add("utilizzo")
        }
    }
}

@Composable
fun ChatPanel(vm: NaviViewModel) {
    val chat by vm.chat.collectAsState()
    val pending by vm.pending.collectAsState()
    val busy by vm.busy.collectAsState()
    val p by vm.profile.collectAsState()
    val partial by vm.partial.collectAsState()
    val forgetAll by vm.askForgetAll.collectAsState()
    val llm by com.tuo.navi.ai.LocalLlm.state.collectAsState()
    LaunchedEffect(Unit) { com.tuo.navi.ai.LocalLlm.warmUp(); delay(1200); vm.maybeAsk() }
    val chatCtx = LocalContext.current
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(chat.size, partial != null) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.size - 1 + if (partial != null) 1 else 0) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.send(it) }
        }
    }
    val bust = remember(p?.id) { ProfileStore.loadBust() }
    Column(Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 560.dp).padding(12.dp).imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            bust?.let { Image(it.asImageBitmap(), null, Modifier.size(56.dp)) }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(p?.name ?: "Navi", color = NaviCyan, fontSize = 18.sp)
                Label("IA locale: ${llm.label}")
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false).padding(vertical = 8.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chat, key = { it.id }) { t ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (t.user) Alignment.End else Alignment.Start) {
                    Text(t.text, color = if (t.user) Color.Black else Color.White, fontSize = 14.sp,
                        modifier = Modifier.widthIn(max = 280.dp).background(if (t.user) NaviCyan else Color(0xFF1B2E52), RoundedCornerShape(10.dp)).padding(8.dp))
                    t.links.forEachIndexed { i, h ->
                        Column(Modifier.padding(top = 4.dp).widthIn(max = 280.dp).background(Color(0xFF13223F), RoundedCornerShape(8.dp))
                            .clickable { runCatching { chatCtx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(h.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                            .padding(8.dp)) {
                            Text("${i + 1}. ${h.title}", color = NaviCyan, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${h.domain} · ${h.trust.label}", color = when (h.trust) {
                                com.tuo.navi.net.Trust.HIGH -> Color(0xFF66BB6A); com.tuo.navi.net.Trust.MEDIUM -> Color(0xFFBFC8DA); com.tuo.navi.net.Trust.LOW -> Color(0xFFFFC857) }, fontSize = 11.sp)
                            if (h.snippet.isNotBlank()) Text(h.snippet, color = Color(0xFFDDE3EE), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!t.user && t.engine != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.engine, color = Color(0xFF7F93B5), fontSize = 11.sp)
                        if (t.question != null && !t.rated) {
                            TextButton(onClick = { vm.rate(t.id, true) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Giusta", fontSize = 12.sp) }
                            TextButton(onClick = { vm.rate(t.id, false) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Sbagliata", fontSize = 12.sp) }
                        }
                    }
                }
            }
            partial?.let { pt -> item {
                Text(pt.ifBlank { "..." }, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.widthIn(max = 280.dp).background(Color(0xFF1B2E52), RoundedCornerShape(10.dp)).padding(8.dp))
            } }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ChatSuggestions(vm)
        if (forgetAll) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.forgetAll(true) }) { Text("Dimentica tutto") }
            OutlinedButton(onClick = { vm.forgetAll(false) }) { Text("Annulla") }
        }
        pending?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.confirm(true) }) { Text("Conferma") }
                OutlinedButton(onClick = { vm.confirm(false) }) { Text("Annulla") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Parla con il Navi") }, maxLines = 3)
            TextButton(onClick = {
                speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
            }) { Text("Voce") }
            TextButton(onClick = { vm.send(input); input = "" }) { Text("Invia") }
        }
    }
}

@Composable
fun PermissionCards() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    val listener = remember(tick) { Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true }
    val acc = remember(tick) { HomeScene.accessibilityOn(ctx) }
    val usage = remember(tick) { HabitTracker.hasUsageAccess(ctx) }
    if (listener && acc && usage) return
    Panel {
        Text("Il Navi ha bisogno di qualche permesso:", color = Color(0xFFFFC857))
        if (!acc) TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Navi sulla Home (accessibilità)") }
        if (!listener) TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Accesso alle notifiche (app e mittente)") }
        if (!usage) TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Dati di utilizzo (app inutilizzate)") }
        if (!acc || !listener) {
            Label("Se l'interruttore è grigio o compare \"Impostazione con restrizioni\": Info app NetNavi > menu con i tre puntini in alto > \"Consenti impostazioni con restrizioni\", poi riprova.")
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }) { Text("Apri Info app NetNavi") }
        }
    }
}
