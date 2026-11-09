package com.tuo.navi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuo.navi.brain.LearnIntent
import com.tuo.navi.brain.LearnParser
import com.tuo.navi.brain.MemKind
import com.tuo.navi.brain.MemoryStore
import com.tuo.navi.core.Ability
import com.tuo.navi.core.Leveling
import com.tuo.navi.data.ProfileStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Cosa sa di me": tutto ciò che il Navi ha imparato, visibile, modificabile e cancellabile. Resta sul telefono. */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val items by MemoryStore.items.collectAsState()
    val style by MemoryStore.style.collectAsState()
    var filter by remember { mutableStateOf<MemKind?>(null) }
    var teach by remember { mutableStateOf("") }
    var userName by remember(style.userName) { mutableStateOf(style.userName) }
    var askClear by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val df = remember { SimpleDateFormat("d MMM yyyy", Locale.ITALIAN) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        TopBar("Cosa sa di me", onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Panel {
                    Text("Memoria del Navi", color = NaviCyan)
                    Label("Questi ricordi restano solo su questo telefono, nella cartella privata dell'app. Non vanno in nessun backup " +
                        "e non servono ad addestrare altri modelli. Il modello di base resta lo stesso: il Navi gli passa i ricordi pertinenti quando gli parli.")
                    Label("Ricordi: ${items.size}")
                }
            }
            item {
                Panel {
                    Text("Insegna qualcosa", color = NaviCyan)
                    OutlinedTextField(teach, { teach = it }, Modifier.fillMaxWidth(), placeholder = { Text("es. Marco è mio fratello") }, maxLines = 3)
                    Row {
                        TextButton(onClick = {
                            val t = teach.trim(); if (t.isEmpty()) return@TextButton
                            val intent = LearnParser.parse(t)
                            when (intent) {
                                is LearnIntent.Teach -> MemoryStore.add(MemKind.RULE, intent.reply, key = intent.trigger)
                                is LearnIntent.Remember -> MemoryStore.add(intent.kind, intent.text)
                                else -> MemoryStore.add(LearnParser.classify(t), t)
                            }
                            ProfileStore.addExp(Leveling.Exp.LEARNED_FACT)
                            msg = "Ricordato."; teach = ""
                        }) { Text("Ricorda") }
                    }
                    Label("Puoi anche scrivere: quando dico X rispondi Y")
                }
            }
            if (Ability.STYLE.unlocked) item {
                Panel {
                    Text("Stile", color = NaviCyan)
                    OutlinedTextField(userName, { userName = it }, Modifier.fillMaxWidth(), label = { Text("Come ti chiama") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Risposte", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        FilterChip(style.replyLength == "breve", { MemoryStore.setStyle(style.copy(replyLength = "breve")) }, { Text("Brevi") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(style.replyLength == "lunga", { MemoryStore.setStyle(style.copy(replyLength = "lunga")) }, { Text("Complete") })
                    }
                    TextButton(onClick = { MemoryStore.setStyle(style.copy(userName = userName.trim())); msg = "Salvato." }) { Text("Salva nome") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(filter == null, { filter = null }, { Text("Tutti") })
                    MemKind.entries.forEach { k -> FilterChip(filter == k, { filter = k }, { Text(k.label) }) }
                }
                msg?.let { Text(it, color = NaviCyan) }
            }
            val shown = items.filter { filter == null || it.kind == filter }
            if (shown.isEmpty()) item { Label("Nessun ricordo qui. Prova in chat: \"ricorda che...\".") }
            items(shown, key = { it.id }) { m ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.kind.label + (if (m.source != "tu") " · ${m.source}" else "") + " · " + df.format(Date(m.created)), color = Color(0xFF7F93B5), fontSize = 11.sp)
                            if (m.key.isNotBlank()) Text("\"${m.key}\" →", color = Color(0xFFFFC857), fontSize = 13.sp)
                            Text(m.text, color = Color.White, fontSize = 14.sp)
                            if (m.uses > 0) Label("usato ${m.uses} volte")
                        }
                        TextButton(onClick = { MemoryStore.remove(m.id) }) { Text("Elimina") }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { askClear = true }, Modifier.fillMaxWidth()) { Text("Cancella tutta la memoria", color = Color(0xFFFF6B6B)) }
            }
        }
    }
    if (askClear) AlertDialog(onDismissRequest = { askClear = false }, title = { Text("Cancellare tutta la memoria?") },
        text = { Text("Il Navi dimenticherà fatti, risposte insegnate, correzioni e il tuo nome. Livello e aspetto restano.") },
        confirmButton = { TextButton(onClick = { askClear = false; MemoryStore.clear(); msg = "Memoria cancellata." }) { Text("Cancella") } },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("Annulla") } })
}

/** Tabella delle abilità per livello. Le soglie sono provvisorie. */
@Composable
fun AbilitiesScreen(onBack: () -> Unit) {
    val p by ProfileStore.profile.collectAsState()
    val lv = Leveling.effective(p)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        TopBar("Abilità del Navi", onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Panel {
                    Text("Livello $lv" + if (lv >= Leveling.MAX_LEVEL) " (massimo)" else "", color = NaviCyan, fontSize = 18.sp)
                    if (Leveling.isDevBuild) Label("Build di sviluppo: il Navi è forzato al livello ${Leveling.devLevel} e tutte le abilità sono attive. " +
                        "Il livello reale (${p?.level ?: 1}) continua a crescere e conterà quando decideremo le regole di sblocco.")
                    Label("Soglie provvisorie: le decideremo insieme più avanti.")
                }
            }
            item {
                Panel {
                    Text("Come guadagna EXP", color = NaviCyan)
                    Leveling.expTable.forEach { (what, n) -> Text("• $what: +$n", color = Color.White, fontSize = 13.sp) }
                }
            }
            items(Ability.entries.toList()) { a ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("LV ${a.minLevel}", color = if (a.unlocked) NaviCyan else Color(0xFF7F93B5), fontSize = 13.sp, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.title, color = if (a.unlocked) Color.White else Color(0xFF7F93B5))
                            Label(a.desc)
                        }
                        Text(if (a.unlocked) "attiva" else "bloccata", color = if (a.unlocked) Color(0xFF6BFF9E) else Color(0xFFFF6B6B), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
