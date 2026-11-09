package com.tuo.navi.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.tuo.navi.nlp.NaviCommand
import java.util.Calendar

enum class Quick { ALARM, TIMER, NOTE, EVENT }

/** Barra comandi sotto il Navi: pulsante grande per parlare + azioni rapide con moduli guidati. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandDock(vm: NaviViewModel, name: String, onTalk: () -> Unit, onNotes: () -> Unit) {
    var open by remember { mutableStateOf<Quick?>(null) }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Button(onClick = onTalk, Modifier.fillMaxWidth().height(48.dp)) { Text("Parla con $name") }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { open = Quick.ALARM }, label = { Text("Sveglia") })
            AssistChip(onClick = { open = Quick.TIMER }, label = { Text("Timer") })
            AssistChip(onClick = { open = Quick.NOTE }, label = { Text("Nota") })
            AssistChip(onClick = { open = Quick.EVENT }, label = { Text("Appuntamento") })
            AssistChip(onClick = { vm.send("che sveglia ho?") }, label = { Text("Prossima sveglia") })
            AssistChip(onClick = onNotes, label = { Text("Le mie note") })
            AssistChip(onClick = { vm.send("app inutilizzate") }, label = { Text("App inutilizzate") })
            AssistChip(onClick = { vm.send("stato") }, label = { Text("Stato del Navi") })
        }
    }

    open?.let { QuickDialog(vm, it) { open = null } }
}

/** Moduli guidati per i comandi rapidi (usati dalla dashboard e dalla barra comandi). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickDialog(vm: NaviViewModel, q: Quick, onDone: () -> Unit) {
    when (q) {
        Quick.ALARM -> {
            val now = Calendar.getInstance()
            val tp = rememberTimePickerState(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), is24Hour = true)
            var label by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = onDone, title = { Text("Nuova sveglia") },
                text = { Column { TimeInput(tp); OutlinedTextField(label, { label = it }, label = { Text("Etichetta (facoltativa)") }, singleLine = true) } },
                confirmButton = { TextButton(onClick = { vm.runDirect(NaviCommand.SetAlarm(tp.hour, tp.minute, label.ifBlank { null }, -1)); onDone() }) { Text("Imposta") } },
                dismissButton = { TextButton(onClick = { onDone() }) { Text("Annulla") } })
        }
        Quick.TIMER -> {
            var min by remember { mutableStateOf("10") }
            AlertDialog(onDismissRequest = onDone, title = { Text("Timer") },
                text = {
                    Column {
                        OutlinedTextField(min, { min = it.filter(Char::isDigit).take(3) }, label = { Text("Minuti") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("1", "5", "10", "25", "60").forEach { v -> AssistChip(onClick = { min = v }, label = { Text(v) }) } }
                    }
                },
                confirmButton = { TextButton(enabled = (min.toIntOrNull() ?: 0) > 0, onClick = { vm.runDirect(NaviCommand.Timer(min.toInt() * 60, null)); onDone() }) { Text("Avvia") } },
                dismissButton = { TextButton(onClick = { onDone() }) { Text("Annulla") } })
        }
        Quick.NOTE -> {
            var text by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = onDone, title = { Text("Nuova nota") },
                text = { OutlinedTextField(text, { text = it }, label = { Text("Testo") }, minLines = 3) },
                confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { vm.runDirect(NaviCommand.AddNote(text.trim())); onDone() }) { Text("Salva") } },
                dismissButton = { TextButton(onClick = { onDone() }) { Text("Annulla") } })
        }
        Quick.EVENT -> {
            var title by remember { mutableStateOf("") }
            var day by remember { mutableIntStateOf(0) }
            val tp = rememberTimePickerState(9, 0, is24Hour = true)
            AlertDialog(onDismissRequest = onDone, title = { Text("Appuntamento") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(title, { title = it }, label = { Text("Cosa") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Oggi", "Domani", "Dopodomani").forEachIndexed { i, s -> FilterChip(day == i, { day = i }, label = { Text(s) }) }
                        }
                        TimeInput(tp)
                    }
                },
                confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
                    val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, day); set(Calendar.HOUR_OF_DAY, tp.hour); set(Calendar.MINUTE, tp.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                    vm.runDirect(NaviCommand.AddEvent(c.timeInMillis, c.timeInMillis + 3_600_000L, title.trim(), false)); onDone()
                }) { Text("Crea") } },
                dismissButton = { TextButton(onClick = { onDone() }) { Text("Annulla") } })
        }
    }
}

/** Suggerimenti cliccabili nella chat. */
@Composable
fun ChatSuggestions(vm: NaviViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("aiuto", "che sveglia ho?", "sveglia domani alle 7", "timer 5 minuti", "mostra note", "come stai?", "chi sei?")
            .forEach { s -> SuggestionChip(onClick = { vm.send(s) }, label = { Text(s) }) }
    }
}
