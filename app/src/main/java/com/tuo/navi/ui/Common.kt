package com.tuo.navi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow

@Composable
fun NaviTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = NaviCyan, background = NaviBg, surface = NaviPanel, onPrimary = Color.Black),
    content = content
)

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().background(NaviPanel.copy(alpha = 0.84f), RoundedCornerShape(12.dp)).padding(12.dp), content = content)

/** Cambia quando si imposta la figura intera (usata in "Le mie caratteristiche"). */
val backdropVersion = androidx.compose.runtime.mutableIntStateOf(0)

// Stile dark fantasy della dashboard
val Gold = Color(0xFFD9B46A)
val GoldDeep = Color(0xFF7E5F2E)
val Ink = Color(0xE00A0E1C)
val FantasyShape = androidx.compose.foundation.shape.CutCornerShape(8.dp)
val SerifFont = androidx.compose.ui.text.font.FontFamily.Serif

fun Modifier.goldFrame(glow: Boolean = false, shape: androidx.compose.ui.graphics.Shape = FantasyShape): Modifier {
    val base = if (glow) this.shadow(14.dp, shape, ambientColor = NaviCyan, spotColor = NaviCyan) else this
    return base.background(Ink, shape)
        .border(if (glow) 1.6.dp else 1.dp, if (glow) Brush.verticalGradient(listOf(NaviCyan, Color(0xFF2F7FD1), NaviCyan))
            else Brush.verticalGradient(listOf(Gold, GoldDeep, Gold)), shape)
}

/**
 * Sfondo del pannello del Navi (si vede solo dopo averlo toccato dalla Home):
 * lo scenario predefinito o la foto scelta in Impostazioni. Velo scuro più forte fuori dalla dashboard, per leggere bene.
 */
@Composable
fun NaviBackdrop(strong: Boolean = false, content: @Composable () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val v by com.tuo.navi.data.BackdropStore.version.collectAsState()
    val bg = remember(v) { com.tuo.navi.data.BackdropStore.load(ctx)?.asImageBitmap() }
    Box(Modifier.fillMaxSize().background(NaviBg)) {
        if (bg != null) Image(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Image(painterResource(com.tuo.navi.R.drawable.ic_navi), null,
            Modifier.align(Alignment.Center).fillMaxWidth(0.85f).aspectRatio(1f).alpha(0.42f))
        val veil = if (strong) listOf(NaviBg.copy(alpha = 0.72f), NaviBg.copy(alpha = 0.6f), NaviBg.copy(alpha = 0.8f))
            else listOf(NaviBg.copy(alpha = 0.45f), Color.Transparent, NaviBg.copy(alpha = 0.55f))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(veil)))
        content()
    }
}

/** Intestazione di sezione in stile fantasy, con link opzionale a destra. */
@Composable
fun FantasyTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(com.tuo.navi.R.drawable.ic_q_star), null, Modifier.size(16.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(NaviCyan))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Gold, fontFamily = SerifFont, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Text("$action  >", color = Color(0xFFE8DCC0), fontSize = 13.sp,
            modifier = Modifier.clickable { onAction() }.padding(4.dp))
    }

@Composable
fun TopBar(title: String, onBack: () -> Unit) = Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween) {
    Text(title, color = NaviCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    TextButton(onClick = onBack) { Text("Indietro") }
}

@Composable
fun Label(t: String) = Text(t, color = Color(0xFF9FB3D1), fontSize = 13.sp)
