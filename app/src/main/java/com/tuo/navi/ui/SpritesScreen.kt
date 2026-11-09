package com.tuo.navi.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.sprites.ImportResult
import com.tuo.navi.sprites.SpriteImporter
import com.tuo.navi.sprites.SpriteRow
import com.tuo.navi.sprites.SpriteSpec
import com.tuo.navi.sprites.SpriteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SpritesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver by SpriteStore.version.collectAsState()
    var target by remember { mutableStateOf<SpriteRow?>(null) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var band by remember { mutableIntStateOf(-1) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var askSeal by remember { mutableStateOf(false) }
    val pixel = remember { ProfileStore.loadPixel() }

    fun run() {
        val s = source ?: return; val r = target ?: return
        busy = true
        scope.launch {
            result = withContext(Dispatchers.Default) { runCatching { SpriteImporter.process(s, r.frames, band, r.heightRatio, if (r.id == 15) 8 else -1) }.getOrElse { ImportResult(null, emptyList(), emptyList(), it.message, 0, "-") } }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        source = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
                .copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
        band = -1; run()
    }

    val sealed = remember(ver) { SpriteStore.isSealed() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        TopBar("Sprite del Navi", onBack)
        Panel {
            Text(if (sealed) "Master sigillata. Puoi aggiungere le righe 8-14 e 16-24. Quelle che non carichi vengono generate dalla tua immagine." else
                "Carica le righe master (1-7 e 15), poi sigilla. Dopo il sigillo non saranno più modificabili.", color = Color.White, fontSize = 13.sp)
            if (!sealed) {
                val miss = remember(ver) { SpriteStore.missingMaster() }
                Label(if (miss.isEmpty()) "Tutte le righe master sono presenti." else "Mancano: ${miss.joinToString()}")
                Button(enabled = miss.isEmpty(), onClick = { askSeal = true }) { Text("Sigilla master") }
            }
        }
        msg?.let { Text(it, color = Color(0xFFFF6B6B)) }
        LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SpriteSpec.rows) { r ->
                val has = remember(ver) { SpriteStore.has(r.id) }
                val lock = remember(ver) { SpriteStore.lockReason(r.id) }
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.id}. ${r.title} (${r.frames} frame)${if (r.master) "  MASTER" else ""}", color = if (r.master) Color(0xFFFFC857) else Color.White)
                            Label(if (has) "Caricata" else "Non caricata"); lock?.let { Label(it) }
                        }
                        if (has) NaviSprite(r.key, true, 64.dp, pixel, Modifier.background(Color(0xFF13264A)))
                    }
                    Row {
                        TextButton(onClick = { copyText(ctx, "prompt", SpriteSpec.prompt(r)) }) { Text("Copia prompt") }
                        TextButton(enabled = lock == null, onClick = {
                            target = r; result = null; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text(if (has) "Sostituisci" else "Importa") }
                        if (has && lock == null) TextButton(onClick = { runCatching { SpriteStore.remove(r.id) }.onFailure { msg = it.message } }) { Text("Rimuovi") }
                    }
                }
            }
        }
    }

    if (target != null && (busy || result != null)) {
        val r = target!!
        AlertDialog(onDismissRequest = { target = null; result = null; source = null },
            title = { Text("Anteprima: ${r.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    result?.let { res ->
                        res.error?.let { Text(it, color = Color(0xFFFF6B6B)) }
                        if (res.frames.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()).background(Color(0xFF13264A))) {
                                res.frames.forEach { f -> Image(f.asImageBitmap(), null, Modifier.size(64.dp), filterQuality = FilterQuality.None) }
                            }
                            Label("Frame: ${res.frames.size}/${r.frames} · divisione: ${res.method}")
                        }
                        res.warnings.forEach { Text("• $it", color = Color(0xFFFFC857), fontSize = 12.sp) }
                        if (res.bandCount > 1) Row {
                            (0 until res.bandCount).forEach { i -> TextButton(onClick = { band = i; run() }) { Text("Riga ${i + 1}") } }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = result?.strip != null && !busy, onClick = {
                    runCatching { SpriteStore.save(r.id, result!!.strip!!) }.onFailure { msg = it.message }
                    target = null; result = null; source = null
                }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { target = null; result = null; source = null }) { Text("Annulla") } })
    }

    if (askSeal) AlertDialog(onDismissRequest = { askSeal = false }, title = { Text("Sigillare la master?") },
        text = { Text("Le righe 1-7 e 15 non potranno più essere sostituite né cancellate.") },
        confirmButton = { TextButton(onClick = { askSeal = false; runCatching { SpriteStore.seal() }.onFailure { msg = it.message } }) { Text("Sigilla") } },
        dismissButton = { TextButton(onClick = { askSeal = false }) { Text("Annulla") } })
}
