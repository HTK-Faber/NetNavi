package com.tuo.navi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuo.navi.sprites.SpriteSpec

fun copyText(ctx: Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Copiato", Toast.LENGTH_SHORT).show()
}

/** Guida condivisa: regole, tabella delle righe, prompt, IA locale. */
@Composable
fun GuideScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TopBar("Guida", onBack)
        Panel {
            Text("Regole degli sprite", color = NaviCyan, fontSize = 17.sp)
            SpriteSpec.rules.forEach { Text("• $it", color = Color.White, fontSize = 13.sp) }
        }
        Panel {
            Text("Tabella delle animazioni", color = NaviCyan, fontSize = 17.sp)
            SpriteSpec.rows.forEach { r ->
                Text("${r.id}. ${r.title} — ${r.frames} frame${if (r.master) " — MASTER" else ""}", color = if (r.master) Color(0xFFFFC857) else Color.White, fontSize = 13.sp)
                Label("   ${r.notes}")
            }
        }
        Panel {
            Text("Prompt condiviso", color = NaviCyan, fontSize = 17.sp)
            Label("Sostituisci i valori della riga oppure usa \"Copia prompt\" nella schermata Sprite, già compilato.")
            SelectionContainer { Text(SpriteSpec.prompt(SpriteSpec.row(1)), color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            TextButton(onClick = { copyText(ctx, "prompt", SpriteSpec.prompt(SpriteSpec.row(1))) }) { Text("Copia (esempio Idle)") }
            Label("Se compaiono riquadri o due righe, scrivi nella stessa chat:")
            SelectionContainer { Text(SpriteSpec.FIX_PROMPT, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            TextButton(onClick = { copyText(ctx, "fix", SpriteSpec.FIX_PROMPT) }) { Text("Copia correzione") }
        }
        Panel {
            Text("IA del Navi", color = NaviCyan, fontSize = 17.sp)
            listOf(
                "Comandi (sveglie, timer, note, calendario): funzionano sempre offline con l'interprete a regole.",
                "IA locale: un modello di base da Hugging Face in formato .task (MediaPipe). Le Impostazioni ti consigliano quello adatto al tuo telefono: su 4 GB Gemma 3 1B int4 o Qwen 2.5 0.5B.",
                "File .gguf, .litertlm o .safetensors non funzionano: l'app ti dice il motivo quando provi a importarli.",
                "Privacy: tutto resta sul telefono. Nessun servizio cloud. Internet è spento di default e si accende solo per scaricare un modello.",
                "Il Navi impara con la sua memoria locale: \"ricorda che...\", \"quando dico X rispondi Y\", i pulsanti Giusta/Sbagliata e le abitudini dei comandi. Tutto è visibile e cancellabile in \"Cosa sa di me\".",
                "Il modello di base non viene riaddestrato (sul telefono non è possibile): il Navi gli passa i ricordi pertinenti a ogni domanda.",
                "Il Navi annuncia solo app e mittente delle notifiche: il testo dei messaggi non viene mai letto.",
                "Le sveglie vengono create nell'app Orologio; il Navi le legge ma non le modifica e resta in silenzio mentre suonano.",
                "Sulla Home i fumetti del Navi durano al massimo 3 secondi.",
                "App poco usate: Disinstalla, Non dirlo più (per sempre) oppure Ricordamelo tra 3 giorni.",
                "Foto e video pesanti: analisi solo sul telefono. Filtri per file grandi (foto oltre 5 MB, video oltre 100 MB, regolabili), duplicati, scatti a pochi secondi di distanza, tipo, periodo e cartella. Invia usa la condivisione di Android verso Drive, Dropbox o simili; Elimina chiede sempre conferma ad Android."
            ).forEach { Text("• $it", color = Color.White, fontSize = 13.sp) }
        }
        Panel {
            Text("Come chiamare il Navi", color = NaviCyan, fontSize = 17.sp)
            listOf("Tocca il personaggio nella home per aprire la chat.",
                "Aggiungi il riquadro \"Chiama Navi\" nelle Impostazioni rapide.",
                "Quando arriva una notifica compare sopra le altre app: toccalo per aprire la chat, trascinalo per spostarlo."
            ).forEach { Text("• $it", color = Color.White, fontSize = 13.sp) }
        }
    }
}
