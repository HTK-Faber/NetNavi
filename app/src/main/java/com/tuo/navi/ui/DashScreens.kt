package com.tuo.navi.ui

import androidx.core.graphics.drawable.toBitmap
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuo.navi.actions.NotesStore
import com.tuo.navi.brain.MemoryStore
import com.tuo.navi.core.Leveling
import com.tuo.navi.core.NotifLog
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.domain.RpgStat
import com.tuo.navi.domain.RpgStatus
import com.tuo.navi.sprites.SpriteSpec
import com.tuo.navi.sprites.SpriteStore

private val Cream = Color(0xFFF3E6C8)
private val Muted = Color(0xFFBFC8DA)

@Composable
private fun FantasyScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Gold, fontFamily = SerifFont, fontSize = 21.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Indietro", color = Cream) }
        }
        content()
    }

/** Tutte le notifiche ricevute da quando il Navi è attivo (solo app e mittente). */
@Composable
fun NotificationsScreen(onBack: () -> Unit) = FantasyScreen("Notifiche recenti", onBack) {
    val list = remember { NotifLog.recent() }
    var muted by remember { mutableStateOf(SettingsStore.mutedPackages) }
    if (list.isEmpty()) Text("Nessuna notifica da quando il Navi è attivo.", color = Muted)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { it.key + it.time }) { n ->
            Row(Modifier.fillMaxWidth().goldFrame().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { NotifRow(n) }
                val isMuted = n.pkg in muted
                TextButton(onClick = {
                    SettingsStore.mutedPackages = if (isMuted) SettingsStore.mutedPackages - n.pkg else SettingsStore.mutedPackages + n.pkg
                    muted = SettingsStore.mutedPackages
                }) { Text(if (isMuted) "Riattiva" else "Silenzia", color = if (isMuted) NaviCyan else Muted, fontSize = 12.sp) }
            }
        }
    }
}

/** App poco usate: Disinstalla, Non dirlo più, Ricordamelo tra 3 giorni. */
@Composable
fun UnusedAppsScreen(vm: NaviViewModel, onBack: () -> Unit) = FantasyScreen("App che non usi da molto", onBack) {
    val ctx = LocalContext.current
    val unused by vm.unused.collectAsState()
    LaunchedEffect(Unit) { vm.checkUnused(verbose = false) }
    Text("App installate da almeno 60 giorni e mai aperte negli ultimi 60. Controlla bene: alcune servono di rado ma sono importanti (banca, SPID, assicurazione).",
        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
    if (!com.tuo.navi.habits.HabitTracker.hasUsageAccess(ctx))
        Text("Serve l'accesso ai dati di utilizzo: attivalo dalla dashboard (riga gialla dei permessi).", color = Color(0xFFFFC857))
    else if (unused.isEmpty()) Text("Nessuna app inutilizzata. Ottimo lavoro.", color = Cream)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(unused, key = { it.pkg }) { u ->
            val icon = remember(u.pkg) { runCatching { ctx.packageManager.getApplicationIcon(u.pkg).toBitmap(72, 72).asImageBitmap() }.getOrNull() }
            Column(Modifier.fillMaxWidth().goldFrame().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    icon?.let { Image(it, null, Modifier.size(36.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(u.label, color = Cream, fontFamily = SerifFont, fontSize = 16.sp)
                        Text("Ferma da ${u.daysUnused} giorni", color = Muted, fontSize = 12.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { vm.uninstall(u.pkg) }) { Text("Disinstalla", color = Color(0xFFFF8A80), fontSize = 13.sp) }
                    TextButton(onClick = { vm.keepApp(u.pkg) }) { Text("Non dirlo più", color = Cream, fontSize = 13.sp) }
                    TextButton(onClick = { vm.remindLater(u.pkg) }) { Text("Tra 3 giorni", color = NaviCyan, fontSize = 13.sp) }
                }
            }
        }
    }
}

/** "Le mie caratteristiche": status GDR, note, ricordi, sprite e abilità. */
@Composable
fun TraitsScreen(onBack: () -> Unit, nav: (Screen) -> Unit) = FantasyScreen("Le mie caratteristiche", onBack) {
    val p by ProfileStore.profile.collectAsState()
    val prof = p ?: return@FantasyScreen
    val notes by NotesStore.notes.collectAsState()
    val mem by MemoryStore.items.collectAsState()
    val sv by SpriteStore.version.collectAsState()
    val bv = backdropVersion.intValue
    val figure: Bitmap? = remember(prof.id, bv) { ProfileStore.loadFull() ?: ProfileStore.loadBust() ?: ProfileStore.loadPixel() }
    val lv = Leveling.effective(prof)
    val combat = remember(prof, lv) { RpgStatus.combat(prof, lv) }
    val attrs = remember(prof, lv) { RpgStatus.attributes(prof, lv) }
    val loaded = remember(sv) { SpriteSpec.rows.count { SpriteStore.has(it.id) } }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().goldFrame().padding(12.dp)) {
            FantasyTitle("Status GDR")
            Row(Modifier.padding(top = 8.dp)) {
                Box(Modifier.width(118.dp).height(200.dp).background(Color(0x66000000), FantasyShape), contentAlignment = Alignment.BottomCenter) {
                    figure?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(prof.name, color = Cream, fontFamily = SerifFont, fontSize = 20.sp)
                    Text("${prof.cls.label}  ·  LV $lv${if (lv >= 100) " MAX" else ""}", color = Gold, fontSize = 14.sp)
                    Text(prof.cls.role, color = Muted, fontSize = 12.sp)
                    Text("Altezza ${prof.heightCm} cm  ·  Legame ${prof.bond / 10}%", color = Muted, fontSize = 12.sp)
                    val aff = remember(mem.size) { com.tuo.navi.brain.Interview.affinity() to com.tuo.navi.brain.Interview.answeredCount() }
                    Text("Affinità con te ${aff.first}%  ·  ${aff.second}/${com.tuo.navi.brain.Interview.topics.size} argomenti", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    combat.forEach { StatBar(it) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Attributi", color = Gold, fontFamily = SerifFont, fontSize = 15.sp)
            attrs.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { pair.forEach { Box(Modifier.weight(1f)) { StatBar(it) } } }
            }
            Text("Parametri provvisori, coerenti con la classe: verranno definiti con il sistema di battaglia.", color = Muted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashTile(com.tuo.navi.R.drawable.ic_q_note, "Note (${notes.size})", Modifier.weight(1f)) { nav(Screen.NOTES) }
            DashTile(com.tuo.navi.R.drawable.ic_q_book, "Ricordi (${mem.size})", Modifier.weight(1f)) { nav(Screen.MEMORY) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashTile(com.tuo.navi.R.drawable.ic_q_photos, "Sprite ($loaded/${SpriteSpec.rows.size})", Modifier.weight(1f)) { nav(Screen.SPRITES) }
            DashTile(com.tuo.navi.R.drawable.ic_q_star, "Abilità", Modifier.weight(1f)) { nav(Screen.ABILITIES) }
        }
    }
}

@Composable
private fun StatBar(s: RpgStat) = Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    Row {
        Text(s.label, color = Cream, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${s.value}", color = Gold, fontSize = 12.sp)
    }
    LinearProgressIndicator(progress = { (s.value / s.max.toFloat()).coerceIn(0.03f, 1f) }, Modifier.fillMaxWidth().height(4.dp),
        color = NaviCyan, trackColor = Color(0xFF1A2440))
}
