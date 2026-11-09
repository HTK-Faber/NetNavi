package com.tuo.navi.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tuo.navi.actions.NotesStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val notes by NotesStore.notes.collectAsState()
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        TopBar("Note", onBack)
        Row {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Nuova nota") })
            TextButton(onClick = { if (input.isNotBlank()) { NotesStore.add(input); input = "" } }) { Text("Aggiungi") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(notes, key = { it.id }) { n ->
                Panel {
                    Text(n.text, color = Color.White)
                    Label(SimpleDateFormat("d MMM yyyy HH:mm", Locale.ITALIAN).format(Date(n.created)))
                    Row {
                        TextButton(onClick = {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, n.text), "Condividi nota"))
                        }) { Text("Condividi") }
                        TextButton(onClick = { NotesStore.delete(n.id) }) { Text("Elimina") }
                    }
                }
            }
        }
    }
}
