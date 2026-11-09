package com.tuo.navi.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.media.*
import com.tuo.navi.media.MediaLogic.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Cream = Color(0xFFF3E6C8)
private val Muted = Color(0xFFBFC8DA)

/**
 * Foto e video pesanti: analisi locale con filtri (grandi, duplicati, scatti in serie, tipo, periodo, cartella).
 * Invio a Drive/Dropbox con la condivisione di Android; eliminazione con la conferma di sistema.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(vm: NaviViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(MediaScanner.hasPermission(ctx)) }
    var all by remember { mutableStateOf<List<MediaInfo>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var f by remember { mutableStateOf(MediaFilter(photoMinMb = SettingsStore.photoMinMb, videoMinMb = SettingsStore.videoMinMb, burstSeconds = SettingsStore.burstSeconds)) }
    var groups by remember { mutableStateOf<List<MediaGroup>>(emptyList()) }
    var working by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var showFilters by remember { mutableStateOf(false) }
    var askDeleteAfterSend by remember { mutableStateOf<List<MediaInfo>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val thumbs = remember { mutableStateMapOf<Long, ImageBitmap?>() }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = MediaScanner.hasPermission(ctx) }
    fun rescan() { scope.launch { scanning = true; all = withContext(Dispatchers.IO) { MediaScanner.scan(ctx) }; scanning = false } }
    LaunchedEffect(granted) { if (granted && all == null) rescan() }

    // ricalcolo dei gruppi quando cambiano i filtri
    LaunchedEffect(all, f) {
        val list = all ?: return@LaunchedEffect
        val base = MediaLogic.base(list, f)
        groups = when (f.mode) {
            MediaMode.LARGE -> MediaLogic.large(base, f)
            MediaMode.ALL -> base.sortedByDescending { it.size }.map { MediaGroup(listOf(it), null) }
            MediaMode.BURSTS -> MediaLogic.bursts(base, f.burstSeconds)
            MediaMode.DUPLICATES -> {
                working = "Confronto i file con la stessa dimensione..."
                val cache = HashMap<Long, String?>()
                val r = withContext(Dispatchers.IO) { MediaLogic.duplicates(base) { m -> cache.getOrPut(m.id) { MediaScanner.fingerprint(ctx, m) } } }
                working = null; r
            }
        }
        // preselezione: nei gruppi si selezionano le copie, non il file suggerito
        selected = if (f.mode == MediaMode.DUPLICATES || f.mode == MediaMode.BURSTS)
            groups.flatMap { g -> g.items.filter { it.id != g.suggestedKeep }.map { it.id } }.toSet() else emptySet()
    }

    val itemsById = remember(groups) { groups.flatMap { it.items }.associateBy { it.id } }
    val sel = selected.mapNotNull { itemsById[it] }

    val deleter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val gone = selected
            all = all?.filterNot { it.id in gone }
            msg = "Eliminati ${gone.size} file."
            vm.gainExpPublic(com.tuo.navi.core.Leveling.Exp.COMMAND)
            selected = emptySet()
        } else msg = "Eliminazione annullata."
    }
    fun delete(list: List<MediaInfo>) {
        if (list.isEmpty()) return
        selected = list.map { it.id }.toSet()
        runCatching { deleter.launch(IntentSenderRequest.Builder(MediaScanner.deleteRequest(ctx, list)).build()) }.onFailure { msg = "Impossibile eliminare: ${it.message}" }
    }
    val sharer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        askDeleteAfterSend = sel.takeIf { it.isNotEmpty() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Foto e video pesanti", color = Gold, fontFamily = SerifFont, fontSize = 21.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Indietro", color = Cream) }
        }
        if (!granted) {
            Column(Modifier.fillMaxWidth().goldFrame().padding(12.dp)) {
                Text("Per trovare i file pesanti il Navi deve poter leggere foto e video. L'analisi avviene solo sul telefono: niente esce dall'app.", color = Cream, fontSize = 14.sp)
                Button(onClick = { perm.launch(MediaScanner.permissions()) }, Modifier.padding(top = 8.dp)) { Text("Consenti accesso a foto e video") }
            }
            return@Column
        }

        // modalità
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MediaMode.values().forEach { m -> FilterChip(f.mode == m, { f = f.copy(mode = m) }, label = { Text(m.label) }) }
            AssistChip(onClick = { showFilters = !showFilters }, label = { Text(if (showFilters) "Chiudi filtri" else "Filtri") })
        }
        if (showFilters) Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).goldFrame().padding(10.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MediaType.values().forEach { t -> FilterChip(f.type == t, { f = f.copy(type = t) }, label = { Text(t.label) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MediaPeriod.values().forEach { pe -> FilterChip(f.period == pe, { f = f.copy(period = pe) }, label = { Text(pe.label) }) }
            }
            val buckets = remember(all) { all.orEmpty().groupingBy { it.bucket }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(f.bucket == null, { f = f.copy(bucket = null) }, label = { Text("Tutte le cartelle") })
                buckets.forEach { b -> FilterChip(f.bucket == b, { f = f.copy(bucket = b) }, label = { Text(b) }) }
            }
            var pm by remember { mutableFloatStateOf(f.photoMinMb.toFloat()) }
            var vmb by remember { mutableFloatStateOf(f.videoMinMb.toFloat()) }
            var bs by remember { mutableFloatStateOf(f.burstSeconds.toFloat()) }
            Text("Foto grandi oltre ${pm.toInt()} MB", color = Cream, fontSize = 13.sp)
            Slider(pm, { pm = it }, valueRange = 1f..50f, onValueChangeFinished = { SettingsStore.photoMinMb = pm.toInt(); f = f.copy(photoMinMb = pm.toInt()) })
            Text("Video grandi oltre ${vmb.toInt()} MB", color = Cream, fontSize = 13.sp)
            Slider(vmb, { vmb = it }, valueRange = 10f..2000f, onValueChangeFinished = { SettingsStore.videoMinMb = vmb.toInt(); f = f.copy(videoMinMb = vmb.toInt()) })
            Text("Scatti in serie: foto a meno di ${bs.toInt()} secondi l'una dall'altra", color = Cream, fontSize = 13.sp)
            Slider(bs, { bs = it }, valueRange = 1f..60f, onValueChangeFinished = { SettingsStore.burstSeconds = bs.toInt(); f = f.copy(burstSeconds = bs.toInt()) })
        }

        // riepilogo
        val total = groups.sumOf { it.total }; val reclaim = groups.sumOf { it.reclaim }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when {
                    scanning -> "Analisi in corso..."
                    working != null -> working!!
                    groups.isEmpty() -> "Nessun file con questi filtri."
                    f.mode == MediaMode.DUPLICATES || f.mode == MediaMode.BURSTS -> "${groups.size} gruppi  ·  recuperabili ${formatSize(reclaim)}"
                    else -> "${groups.size} file  ·  ${formatSize(total)}"
                }, color = Cream, fontSize = 13.sp)
                if (f.mode == MediaMode.DUPLICATES) Text("Ogni gruppo tiene il file più vecchio (l'originale).", color = Muted, fontSize = 11.sp)
                if (f.mode == MediaMode.BURSTS) Text("Ogni gruppo tiene la foto più grande, di solito la più nitida.", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = { rescan() }) { Text("Aggiorna", color = NaviCyan) }
        }
        if (scanning || working != null) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(groups, key = { g -> g.items.first().id }) { g ->
                Column(Modifier.fillMaxWidth().goldFrame().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    g.items.forEach { m ->
                        MediaRow(m, m.id in selected, keep = m.id == g.suggestedKeep, thumb = thumbs[m.id],
                            loadThumb = { if (!thumbs.containsKey(m.id)) { thumbs[m.id] = null; scope.launch { thumbs[m.id] = withContext(Dispatchers.IO) { MediaScanner.thumbnail(ctx, m)?.asImageBitmap() } } } },
                            onToggle = { selected = if (m.id in selected) selected - m.id else selected + m.id })
                    }
                }
            }
        }

        // azioni
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selected = if (selected.size == itemsById.size) emptySet() else itemsById.keys }) {
                Text(if (selected.size == itemsById.size && selected.isNotEmpty()) "Nessuno" else "Tutti", color = Cream)
            }
            Text("${sel.size} sel. · ${formatSize(sel.sumOf { it.size })}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            OutlinedButton(enabled = sel.isNotEmpty(), onClick = { runCatching { sharer.launch(MediaScanner.shareIntent(sel)) }.onFailure { msg = it.message } }) { Text("Invia") }
            Button(enabled = sel.isNotEmpty(), onClick = { delete(sel) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C), contentColor = Color.White)) { Text("Elimina") }
        }
        Text("Invia apre la condivisione di Android: scegli Drive, Dropbox, OneDrive o un'altra app. NetNavi non usa Internet.", color = Muted, fontSize = 11.sp)
    }

    askDeleteAfterSend?.let { list ->
        AlertDialog(onDismissRequest = { askDeleteAfterSend = null },
            title = { Text("Eliminare gli originali?") },
            text = { Text("Se hai inviato ${list.size} file a Drive o Dropbox, aspetta che il caricamento sia finito (controlla nell'app scelta), poi puoi eliminarli dal telefono per liberare ${formatSize(list.sumOf { it.size })}.") },
            confirmButton = { TextButton(onClick = { askDeleteAfterSend = null; delete(list) }) { Text("Elimina ora") } },
            dismissButton = { TextButton(onClick = { askDeleteAfterSend = null }) { Text("Tienili") } })
    }
    msg?.let { m -> AlertDialog(onDismissRequest = { msg = null }, confirmButton = { TextButton(onClick = { msg = null }) { Text("OK") } }, text = { Text(m) }) }
}

@Composable
private fun MediaRow(m: MediaInfo, checked: Boolean, keep: Boolean, thumb: ImageBitmap?, loadThumb: () -> Unit, onToggle: () -> Unit) {
    LaunchedEffect(m.id) { loadThumb() }
    val df = remember { SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.ITALIAN) }
    Row(Modifier.fillMaxWidth().clickable { onToggle() }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onToggle() })
        Box(Modifier.size(52.dp).background(Color(0x55000000), FantasyShape), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (m.isVideo) Text("VIDEO", color = Color.White, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomCenter).background(Color(0x99000000)).padding(horizontal = 3.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, color = Cream, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatSize(m.size)}  ·  ${m.bucket}", color = Gold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(df.format(Date(m.taken)) + if (m.isVideo && m.durationMs > 0) "  ·  ${m.durationMs / 60000}:${"%02d".format((m.durationMs / 1000) % 60)}" else "",
                color = Muted, fontSize = 11.sp)
        }
        if (keep) Text("DA TENERE", color = NaviCyan, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
    }
}
