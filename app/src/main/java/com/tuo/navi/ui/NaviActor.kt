package com.tuo.navi.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuo.navi.sprites.SpriteSpec
import com.tuo.navi.sprites.SpriteStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

val NaviCyan = Color(0xFF00E5FF)
val NaviBg = Color(0xFF050B1A)
val NaviPanel = Color(0xFF0C1A33)

/**
 * Disegna un'animazione sprite (pixel perfetti). Se la riga non è stata caricata l'animazione
 * viene generata dalla tua immagine (vedi SpriteFrames).
 */
@Composable
fun NaviSprite(anim: String, facingRight: Boolean, size: Dp, fallback: Bitmap?, modifier: Modifier = Modifier, onEnd: (() -> Unit)? = null) {
    val ver by SpriteStore.version.collectAsState()
    val row = SpriteSpec.byKey(anim)
    // riga caricata, oppure animazione generata dalla tua immagine (mai un'immagine ferma)
    val frames: List<ImageBitmap>? = remember(anim, ver, fallback) {
        runCatching { com.tuo.navi.sprites.SpriteFrames.frames(anim, fallback).map { it.asImageBitmap() } }.getOrNull()
    }
    val fb = remember(fallback) { fallback?.asImageBitmap() }
    var idx by remember(anim) { mutableIntStateOf(0) }
    LaunchedEffect(anim, frames) {
        val n = frames?.size ?: 4
        val fps = row?.fps ?: 6
        while (isActive) {
            delay(1000L / fps)
            if (idx + 1 >= n) { if (row?.loop != false) idx = 0 else { onEnd?.invoke(); if (onEnd == null) idx = 0 } } else idx++
        }
    }
    val t = rememberInfiniteTransition(label = "bob")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    Canvas(modifier.size(size)) {
        val img = frames?.getOrNull(idx.coerceIn(0, (frames.size - 1).coerceAtLeast(0)))
        scale(if (facingRight) 1f else -1f, 1f) {
            if (img != null) {
                drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.None)
            } else if (fb != null) {
                val k = minOf(this.size.width * 0.75f / fb.width, this.size.height * 0.75f / fb.height)
                val w = (fb.width * k).roundToInt(); val h = (fb.height * k).roundToInt()
                val lift = when (anim) { "walk", "run" -> (bob * 6f); "jump", "fly" -> bob * 14f; "talk" -> bob * 3f; else -> bob * 2f }
                drawImage(fb, srcOffset = IntOffset.Zero, srcSize = IntSize(fb.width, fb.height),
                    dstOffset = IntOffset(((this.size.width - w) / 2).roundToInt(), (this.size.height * 0.875f - h - lift).roundToInt()),
                    dstSize = IntSize(w, h), filterQuality = FilterQuality.None)
            }
        }
    }
}


/** Fumetto con testo che compare lettera per lettera. */
@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier, tailLeft: Boolean = true, charMs: Long = 18) {
    var shown by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(text) { while (shown < text.length) { delay(charMs); shown++ } }
    Box(modifier) {
        Box(
            Modifier.widthIn(max = 260.dp).background(Color(0xF2FFFFFF), RoundedCornerShape(14.dp))
                .border(2.dp, NaviCyan, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
        ) { Text(text.take(shown), color = Color(0xFF0A1428), fontSize = 14.sp) }
    }
}

/**
 * Palco della home: il Navi passeggia, corre, salta o fluttua lungo il fondo dello schermo
 * e parla con il fumetto quando c'è una nuova frase.
 */
@Composable
fun NaviStage(line: String?, lineId: Int, fallback: Bitmap?, sleepy: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().height(190.dp)) {
        val density = LocalDensity.current
        val spriteDp = 112.dp
        val maxX = with(density) { (maxWidth - spriteDp).toPx() }.coerceAtLeast(0f)
        val x = remember { Animatable(maxX * 0.5f) }
        val y = remember { Animatable(0f) }
        var anim by remember { mutableStateOf("idle") }
        var right by remember { mutableStateOf(true) }
        var talking by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(lineId) {
            if (line != null && lineId > 0) { talking = line; delay(com.tuo.navi.home.HomeNavi.BUBBLE_MS); talking = null }
        }
        LaunchedEffect(maxX, sleepy) {
            while (isActive) {
                if (talking != null) { anim = "talk"; delay(300); continue }
                val r = Random.nextFloat()
                when {
                    sleepy || r < 0.40f -> { anim = "idle"; delay(Random.nextLong(2500, 6000)) }
                    r < 0.72f -> {
                        val target = Random.nextFloat() * maxX; right = target > x.value
                        anim = "walk"; val px = with(density) { 45.dp.toPx() }
                        x.animateTo(target, tween(((abs(target - x.value) / px) * 1000).toInt().coerceAtLeast(300), easing = LinearEasing))
                    }
                    r < 0.84f -> {
                        val target = if (x.value < maxX / 2) maxX * (0.75f + Random.nextFloat() * 0.25f) else maxX * Random.nextFloat() * 0.25f
                        right = target > x.value; anim = "run"; val px = with(density) { 140.dp.toPx() }
                        x.animateTo(target, tween(((abs(target - x.value) / px) * 1000).toInt().coerceAtLeast(300), easing = LinearEasing))
                        anim = "slide"; delay(320)
                    }
                    r < 0.93f -> {
                        anim = "jump"; val h = with(density) { 46.dp.toPx() }
                        y.animateTo(-h, tween(280)); y.animateTo(0f, tween(320)); delay(150)
                    }
                    else -> {
                        anim = "fly"; val h = with(density) { 60.dp.toPx() }
                        y.animateTo(-h, tween(700)); delay(1800); y.animateTo(0f, tween(700))
                    }
                }
            }
        }
        val dx = x.value.roundToInt(); val dy = y.value.roundToInt()
        talking?.let {
            SpeechBubble(it, Modifier.offset { IntOffset((dx - 40).coerceIn(0, (maxX + 40).roundToInt()).coerceAtLeast(0), 0) })
        }
        NaviSprite(anim, right, spriteDp, fallback,
            Modifier.offset { IntOffset(dx, with(density) { 78.dp.roundToPx() } + dy - with(density) { 0.dp.roundToPx() }) }
                .clickable(remember { MutableInteractionSource() }, null) { onTap() })
    }
}
