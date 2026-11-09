package com.tuo.navi.home

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tuo.navi.MainActivity
import com.tuo.navi.core.NotifLog
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.habits.HabitTracker
import com.tuo.navi.habits.UnusedApp
import com.tuo.navi.phrases.PhraseEngine
import com.tuo.navi.ui.NaviSprite
import com.tuo.navi.ui.SpeechBubble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * Il Navi che vive sopra la Home del sistema.
 * Due finestre trasparenti di accessibilità: il personaggio (piccola, toccabile, si sposta)
 * e il fumetto (non toccabile, i tocchi passano alla Home). Il resto dello schermo resta della Home.
 * Tocco = apre la schermata del Navi. Trascinamento = lo sposti, poi cade sulla superficie sotto.
 */
@SuppressLint("StaticFieldLeak")
object HomeNavi {
    /** I fumetti sulla Home durano al massimo 3 secondi, poi scompaiono. */
    const val BUBBLE_MS = 3000L
    private var svc: AccessibilityService? = null
    private val scope = MainScope()
    private var brain: Job? = null
    private var watchers: Job? = null
    private var spriteView: ComposeView? = null
    private var bubbleView: ComposeView? = null
    private var owner: OverlayOwner? = null

    private var anim by mutableStateOf("idle")
    private var facingRight by mutableStateOf(true)
    private var bubble by mutableStateOf<String?>(null)
    private var sizeDp by mutableIntStateOf(64)

    private var fx = 0f; private var fy = 0f
    private var support: HomeIcon? = null
    private var shown = false
    private var dragging = false
    private var lastHidden = System.currentTimeMillis()
    private var lastComment = 0L
    private var lastPageLine = 0L
    private var sayId = 0
    private var bubbleSince = 0L
    private var lastBubbleEnd = 0L
    private var guard: Job? = null
    /** Dopo una notifica resta sveglio almeno fino a quest'ora. */
    private var awakeUntil = 0L
    private var asleep = false
    private var lastBatteryLine = 0L
    private var lastBatteryCheck = 0L
    private var wasCharging: Boolean? = null
    private var unused: Map<String, UnusedApp> = emptyMap()
    private var unusedAt = 0L
    private val promptedUnused = mutableSetOf<String>()

    private var screenW = 1080; private var screenH = 2400
    private var groundY = 2300f; private var topLimit = 200f

    private val density get() = svc?.resources?.displayMetrics?.density ?: 2.6f
    private fun dp(v: Float) = v * density
    private val S get() = dp(sizeDp.toFloat())

    private val spriteLp = WindowManager.LayoutParams(
        1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }

    private val bubbleLp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }

    // ---------------------------------------------------------------- ciclo di vita

    fun attach(s: AccessibilityService) {
        svc = s
        watchers?.cancel()
        watchers = scope.launch {
            launch { HomeScene.onHome.collect { if (it && SettingsStore.overlayEnabled && ProfileStore.exists()) show() else hide() } }
            launch { HomeScene.pageTick.drop(1).collect { if (shown && !dragging) react { onNewPage() } } }
            launch { HomeScene.scrolling.collect { if (it && shown && !dragging) react { onScrollStart() } } }
            launch { HomeScene.icons.collect { if (shown && !dragging) checkSupport() } }
        }
    }

    fun detach() { watchers?.cancel(); hide(); svc = null }

    /** Riapplica impostazioni (dimensione, attivo/spento) dalla schermata del Navi. */
    fun refreshSettings() = scope.launch {
        hide()
        if (HomeScene.onHome.value && SettingsStore.overlayEnabled && ProfileStore.exists()) show()
    }

    /** Una notifica: il Navi la annuncia subito se sei sulla Home (svegliandosi se dorme), altrimenti te la riassume al ritorno. */
    fun notify(line: String) = scope.launch {
        if (shown && !dragging) {
            awakeUntil = System.currentTimeMillis() + 60_000L
            react { if (asleep) wakeUp(quiet = true); anim = "surprised"; delay(520); say(line) }
        }
    }

    val isShown get() = shown

    private var pendingLine: String? = null
    /** Frase da dire la prossima volta che torni sulla Home. */
    fun sayOnReturn(text: String) { pendingLine = text }

    private fun measure(s: AccessibilityService) {
        val m = s.getSystemService(WindowManager::class.java).currentWindowMetrics
        screenW = m.bounds.width(); screenH = m.bounds.height()
        val ins = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        groundY = screenH - ins.bottom - dp(2f)
        topLimit = ins.top + S * 0.95f
    }

    private fun show() {
        val s = svc ?: return
        if (shown) return
        sizeDp = SettingsStore.naviSizeDp
        measure(s)
        val wm = s.getSystemService(WindowManager::class.java)
        val o = OverlayOwner().also { it.start() }
        val pix = ProfileStore.loadPixel()
        val sv = ComposeView(s).apply {
            setViewTreeLifecycleOwner(o); setViewTreeSavedStateRegistryOwner(o); setViewTreeViewModelStoreOwner(o)
            setContent { NaviSprite(anim, facingRight, sizeDp.dp, pix) }
            setOnTouchListener(Touch)
        }
        val bv = ComposeView(s).apply {
            setViewTreeLifecycleOwner(o); setViewTreeSavedStateRegistryOwner(o); setViewTreeViewModelStoreOwner(o)
            setContent { bubble?.let { SpeechBubble(it, Modifier.padding(2.dp), charMs = (1200L / it.length.coerceAtLeast(1)).coerceIn(4L, 18L)) } }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeBubble() }
            visibility = View.GONE
        }
        spriteLp.width = S.toInt(); spriteLp.height = S.toInt()
        if (fx == 0f) { fx = screenW * 0.5f; fy = groundY }
        fx = clampX(fx); fy = fy.coerceIn(topLimit, groundY)
        computeLp()
        runCatching { wm.addView(bv, bubbleLp); wm.addView(sv, spriteLp) }.onFailure { o.stop(); return }
        spriteView = sv; bubbleView = bv; owner = o; shown = true
        NotifLog.homeVisible = true
        // sicurezza: nessun fumetto resta sullo schermo più di 3 secondi, qualunque cosa succeda
        guard?.cancel()
        guard = scope.launch {
            while (true) {
                delay(250)
                if (bubble != null && SystemClock.uptimeMillis() - bubbleSince > BUBBLE_MS + 300) showBubble(null)
            }
        }
        react { entrance() }
    }

    private fun hide() {
        if (!shown) return
        brain?.cancel(); brain = null
        guard?.cancel(); guard = null
        val wm = svc?.getSystemService(WindowManager::class.java)
        spriteView?.let { v -> runCatching { wm?.removeView(v) } }
        bubbleView?.let { v -> runCatching { wm?.removeView(v) } }
        owner?.stop(); spriteView = null; bubbleView = null; owner = null
        shown = false; bubble = null; dragging = false; asleep = false
        lastHidden = System.currentTimeMillis()
        NotifLog.homeVisible = false
    }

    // ---------------------------------------------------------------- posizione e finestre

    private fun clampX(x: Float) = x.coerceIn(S / 2, screenW - S / 2)

    private fun computeLp() {
        spriteLp.x = (fx - S / 2).toInt()
        spriteLp.y = (fy - 0.875f * S).toInt()
    }

    private fun place() {
        computeLp()
        val v = spriteView ?: return
        runCatching { svc?.getSystemService(WindowManager::class.java)?.updateViewLayout(v, spriteLp) }
        if (bubble != null) placeBubble()
    }

    private fun placeBubble() {
        val bv = bubbleView ?: return
        val w = bv.width; val h = bv.height
        if (w == 0 || h == 0) return
        val head = fy - 0.80f * S
        var y = head - h - dp(2f)
        if (y < topLimit - S * 0.6f) y = fy + dp(4f)
        val x = (fx - w / 2f).coerceIn(dp(4f), max(dp(4f), screenW - w - dp(4f)))
        if (bubbleLp.x == x.toInt() && bubbleLp.y == y.toInt()) return
        bubbleLp.x = x.toInt(); bubbleLp.y = y.toInt()
        runCatching { svc?.getSystemService(WindowManager::class.java)?.updateViewLayout(bv, bubbleLp) }
    }

    // ---------------------------------------------------------------- cervello

    /** Interrompe ciò che sta facendo, esegue [block] e poi torna a girovagare. */
    private fun react(block: suspend () -> Unit) {
        brain?.cancel()
        brain = scope.launch {
            try { settle(); block(); loop() } catch (_: CancellationException) {}
        }
    }

    private fun surfaces(): List<HomeIcon> = HomeScene.icons.value.filter { topOf(it) > topLimit && topOf(it) < groundY - S * 0.5f }

    private fun topOf(i: HomeIcon) = i.rect.top + if (i.widget) 0f else dp(4f)

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    /**
     * Comportamento sulla Home. Stati: attivo, calmo (di notte), assonnato (10 minuti senza uso),
     * dorme (15 minuti senza uso). Si sveglia al tocco, quando usi il telefono o all'arrivo di una notifica.
     */
    private suspend fun loop() {
        while (true) {
            if (bubble != null) { anim = "talk"; delay(250); continue }
            val idleFor = HomeScene.idleMs()
            val now = System.currentTimeMillis()
            if (idleFor > HomeScene.SLEEP_MS && now > awakeUntil) { sleep(); continue }
            if (asleep) { wakeUp(quiet = false); continue }
            checkBattery()
            checkCalls()
            val night = hour() >= 23 || hour() < 7
            val drowsy = idleFor > 10 * 60_000L
            val list = surfaces()
            val r = Random.nextFloat()
            when {
                drowsy -> if (r < 0.7f) idle(long = true) else { anim = "think"; delay(Random.nextLong(2500, 4500)) }
                (night && r < 0.6f) || r < 0.30f -> idle()
                r < 0.50f -> stroll(list)
                r < 0.62f -> pickNear(list)?.let { hopOnto(it) } ?: stroll(list)
                r < 0.78f -> list.randomOrNull()?.let { inspect(it) } ?: idle()
                r < 0.86f -> { anim = "read"; delay(Random.nextLong(2500, 4500)); anim = "idle" }
                r < 0.92f -> { anim = "think"; delay(Random.nextLong(2000, 3500)); anim = "idle" }
                r < 0.96f -> stretch()
                else -> if (support != null) jumpDown() else walkTo(Random.nextFloat() * screenW, run = true)
            }
        }
    }

    /** Si addormenta: scende a terra, si accovaccia e resta fermo finché non usi di nuovo il telefono. */
    private suspend fun sleep() {
        if (!asleep) {
            asleep = true
            if (support != null && Random.nextFloat() < 0.5f) jumpDown()
            anim = "think"; delay(900)
        }
        anim = "sleep"
        while (HomeScene.idleMs() > HomeScene.SLEEP_MS && System.currentTimeMillis() > awakeUntil) delay(1000)
    }

    private suspend fun wakeUp(quiet: Boolean) {
        asleep = false
        anim = "sleep"; delay(500); anim = "idle"; delay(300)
        facingRight = !facingRight; delay(250); facingRight = !facingRight
        if (!quiet && Random.nextFloat() < 0.5f) say(PhraseEngine.wakeUp())
    }

    private suspend fun stretch() {
        anim = "jump"; delay(250); anim = "cheer"; delay(900); anim = "idle"
    }

    private var lastCallCheck = 0L
    /** Numeri molesti: ogni 30 minuti guarda il registro chiamate (solo in locale) e propone un numero da bloccare, una volta per numero. */
    private suspend fun checkCalls() {
        val s = svc ?: return
        val now = System.currentTimeMillis()
        if (now - lastCallCheck < 30 * 60_000L) return
        lastCallCheck = now
        val line = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.tuo.navi.calls.CallGuard.newSuggestionLine(s) }.getOrNull() } ?: return
        anim = "think"; delay(600); say(line)
    }

    /** Batteria scarica o messa in carica: un commento breve, al massimo ogni 2 ore. */
    private suspend fun checkBattery() {
        val s = svc ?: return
        val now = System.currentTimeMillis()
        if (now - lastBatteryCheck < 60_000L) return
        lastBatteryCheck = now
        val i = runCatching { s.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else return
        val prev = wasCharging; wasCharging = plugged
        if (now - lastBatteryLine < 2 * 3600_000L) return
        when {
            prev == false && plugged -> { lastBatteryLine = now; anim = "cheer"; delay(700); say(PhraseEngine.charging(pct)) }
            !plugged && pct in 1..15 -> { lastBatteryLine = now; say(PhraseEngine.lowBattery(pct)) }
        }
    }

    private suspend fun idle(long: Boolean = false) {
        anim = "idle"
        delay(if (long) Random.nextLong(5000, 9000) else Random.nextLong(1800, 4800))
        if (Random.nextFloat() < 0.3f) facingRight = !facingRight
    }

    private suspend fun stroll(list: List<HomeIcon>) {
        val s = support
        if (s == null) { walkTo(fx + (Random.nextFloat() - 0.5f) * screenW * 0.8f); return }
        // in cima a un'icona: salta sulla vicina della stessa riga, se c'è
        val row = list.filter { it != s && abs(topOf(it) - topOf(s)) < dp(14f) && abs(it.rect.centerX() - fx) in dp(30f)..dp(220f) }
        row.randomOrNull()?.let { hopOnto(it) } ?: jumpDown()
    }

    private fun pickNear(list: List<HomeIcon>): HomeIcon? {
        val c = list.filter { it != support }
        if (c.isEmpty()) return null
        val w = c.map { 1f / (1f + hypot(it.rect.centerX() - fx, topOf(it) - fy) / dp(120f)) }
        var x = Random.nextFloat() * w.sum()
        c.forEachIndexed { i, ic -> x -= w[i]; if (x <= 0f) return ic }
        return c.last()
    }

    private suspend fun hopOnto(i: HomeIcon) {
        val cx = if (i.widget) (i.rect.left + S / 2 + Random.nextFloat() * (i.rect.width() - S).coerceAtLeast(0f)) else i.rect.centerX().toFloat()
        jumpTo(cx, topOf(i), i)
    }

    /** Guarda un'icona: ci sale sopra o le si mette accanto, la osserva, a volte la commenta. */
    private suspend fun inspect(i: HomeIcon) {
        if (Random.nextBoolean()) {
            hopOnto(i); anim = if (Random.nextBoolean()) "read" else "idle"; delay(900)
            anim = "idle"; facingRight = !facingRight; delay(500); facingRight = !facingRight
        } else {
            // resta a terra sotto l'icona e la guarda
            if (support != null) jumpDown()
            val side = if (fx < i.rect.centerX()) -1 else 1
            walkTo(i.rect.centerX() + side * S * 0.6f)
            facingRight = i.rect.centerX() > fx; anim = "idle"; delay(1600)
        }
        comment(i)
    }

    private suspend fun comment(i: HomeIcon) {
        val now = System.currentTimeMillis()
        if (i.widget || now - lastComment < 3 * 60_000L || Random.nextFloat() > 0.35f) return
        lastComment = now
        val u = unused[i.label.lowercase()]
        val text = if (u != null && u.pkg !in promptedUnused) { promptedUnused += u.pkg; PhraseEngine.unusedOnHome(u.label, u.daysUnused) }
            else PhraseEngine.lookAt(i.label)
        say(text)
    }

    private suspend fun jumpDown() {
        support = null
        val tx = clampX(fx + (if (facingRight) 1 else -1) * dp(40f))
        jumpTo(tx, groundY, null)
    }

    /** Arrivo sulla Home: una sola frase (la più importante), così i fumetti non si accodano. */
    private suspend fun entrance() {
        val woke = HomeScene.wokeFromSleep; HomeScene.wokeFromSleep = false
        if (woke) { anim = "sleep"; delay(900); anim = "idle"; delay(250) } else { anim = "teleport"; delay(650); anim = "idle"; if (Random.nextFloat() < 0.5f) { anim = "wave"; delay(760); anim = "idle" } }
        val missed = NotifLog.since(lastHidden)
        val line = pendingLine?.also { pendingLine = null }
            ?: if (missed.isNotEmpty()) PhraseEngine.homeSummary(missed) else if (woke && Random.nextFloat() < 0.5f) PhraseEngine.wakeUp() else null
        if (line != null) say(line) else maybeUnused()
    }

    private suspend fun onScrollStart() {
        if (support != null) { support = null; anim = "jump"; fall(groundY) }
        anim = "idle"
        var waited = 0
        while (HomeScene.scrolling.value && waited < 2500) { delay(100); waited += 100 }
    }

    private suspend fun onNewPage() {
        anim = "idle"
        facingRight = !facingRight; delay(450); facingRight = !facingRight; delay(450)
        val now = System.currentTimeMillis()
        if (now - lastPageLine > 90_000L && Random.nextFloat() < 0.4f) { lastPageLine = now; say(PhraseEngine.newPage(surfaces().size)) }
        if (Random.nextFloat() < 0.7f) pickNear(surfaces())?.let { hopOnto(it) }
        maybeUnused()
    }

    /** Al massimo una volta al giorno: propone di eliminare un'app inutilizzata (meglio se l'icona è su questa pagina). */
    private suspend fun maybeUnused() {
        val s = svc ?: return
        val now = System.currentTimeMillis()
        val day = now / 86_400_000L
        if (SettingsStore.lastUnusedPromptDay == day) return
        if (now - unusedAt > 6 * 3600_000L) {
            unused = withContext(Dispatchers.IO) { runCatching { HabitTracker.unusedApps(s) }.getOrDefault(emptyList()) }
                .associateBy { it.label.lowercase() }
            unusedAt = now
        }
        if (unused.isEmpty()) return
        val here = surfaces().firstOrNull { !it.widget && unused[it.label.lowercase()]?.pkg?.let { p -> p !in promptedUnused } == true }
        val u = here?.let { unused[it.label.lowercase()] } ?: unused.values.firstOrNull { it.pkg !in promptedUnused } ?: return
        SettingsStore.lastUnusedPromptDay = day; promptedUnused += u.pkg
        if (here != null) { hopOnto(here); delay(300) }
        say(PhraseEngine.unusedOnHome(u.label, u.daysUnused))
    }

    /** Mostra o nasconde la finestra del fumetto (nascosta del tutto, non solo svuotata). */
    private fun showBubble(text: String?) {
        if (text == null && bubble != null) lastBubbleEnd = SystemClock.uptimeMillis()
        bubble = text
        if (text != null) bubbleSince = SystemClock.uptimeMillis()
        bubbleView?.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) placeBubble()
    }

    private suspend fun say(text: String) {
        // breve pausa tra un fumetto e l'altro: si capisce che è una frase nuova
        val gap = 700L - (SystemClock.uptimeMillis() - lastBubbleEnd)
        if (bubble == null && gap > 0) { anim = "idle"; delay(gap) }
        val id = ++sayId
        try {
            showBubble(text); anim = "talk"
            delay(BUBBLE_MS)
        } finally { if (id == sayId) { showBubble(null); anim = "idle" } }
    }

    // ---------------------------------------------------------------- movimento

    private suspend fun frames(durationMs: Long, step: (Float) -> Unit) {
        val t0 = SystemClock.uptimeMillis()
        val d = durationMs.coerceAtLeast(1)
        while (true) {
            val t = ((SystemClock.uptimeMillis() - t0) / d.toFloat()).coerceAtMost(1f)
            step(t); place()
            if (t >= 1f) break
            delay(33)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private suspend fun walkTo(x: Float, run: Boolean = false) {
        val tx = clampX(x); val d = abs(tx - fx)
        if (d < dp(6f)) return
        facingRight = tx > fx; anim = if (run) "run" else "walk"
        val speed = dp(if (run) 150f else 48f)
        val x0 = fx
        frames((d / speed * 1000).toLong()) { fx = lerp(x0, tx, it) }
        if (run) { anim = "slide"; delay(300) }
        anim = "idle"
    }

    private suspend fun jumpTo(x: Float, y: Float, target: HomeIcon?) {
        val tx = clampX(x); val ty = y.coerceIn(topLimit, groundY)
        val x0 = fx; val y0 = fy
        if (abs(tx - x0) > dp(2f)) facingRight = tx > x0
        val dist = hypot(tx - x0, ty - y0)
        anim = if (dist > dp(340f)) "fly" else "jump"
        val arc = dp(34f) + max(0f, y0 - ty) * 0.3f
        val dur = (380 + dist / density * 1.4f).toLong().coerceIn(380, 1150)
        support = null
        frames(dur) { t -> fx = lerp(x0, tx, t); fy = lerp(y0, ty, t) - arc * 4f * t * (1f - t) }
        support = target
        anim = "idle"; delay(140)
    }

    private suspend fun fall(ty: Float) {
        val y0 = fy
        if (abs(ty - y0) < dp(2f)) { fy = ty; place(); return }
        val dur = (180 + abs(ty - y0) / density * 0.9f).toLong().coerceIn(180, 900)
        frames(dur) { t -> fy = lerp(y0, ty, t * t) }
    }

    /** Se è a mezz'aria (o la sua icona è sparita) cade sulla superficie sotto di lui. */
    private suspend fun settle() {
        val under = surfaces().filter { fx >= it.rect.left + dp(6f) && fx <= it.rect.right - dp(6f) && topOf(it) >= fy - dp(4f) }
            .minByOrNull { topOf(it) }
        val ty = under?.let { topOf(it) } ?: groundY
        if (abs(ty - fy) > dp(2f)) { anim = "jump"; fall(ty); anim = "idle" }
        support = under
    }

    private fun checkSupport() {
        val s = support ?: return
        val m = HomeScene.icons.value.firstOrNull {
            it.label == s.label && abs(it.rect.centerX() - s.rect.centerX()) < dp(10f) && abs(it.rect.top - s.rect.top) < dp(10f)
        }
        if (m == null) react { } else if (m != s) { support = m; fy = topOf(m); place() }
    }

    // ---------------------------------------------------------------- tocco

    private var petted = false
    /** Tenere premuto il Navi = carezza. */
    private val pressR = Runnable {
        if (!dragging && shown) {
            petted = true
            react { if (asleep) wakeUp(quiet = true); anim = "pet"; delay(1200); say(PhraseEngine.petted()) }
        }
    }
    private fun cancelPress() { spriteView?.removeCallbacks(pressR) }

    private object Touch : View.OnTouchListener {
        private var sx = 0f; private var sy = 0f
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; dragging = false; petted = false
                    HomeScene.markActivity()
                    v.postDelayed(pressR, 550)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - sx, e.rawY - sy) > dp(12f)) {
                        dragging = true; cancelPress(); brain?.cancel(); sayId++; showBubble(null); support = null; asleep = false; anim = "hurt"
                    }
                    if (dragging) { fx = clampX(e.rawX); fy = (e.rawY + S * 0.4f).coerceIn(topLimit, groundY); place() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelPress()
                    if (dragging) { dragging = false; react { anim = "idle" } }
                    else if (petted) petted = false
                    else if (e.actionMasked == MotionEvent.ACTION_UP) openPanel()
                }
            }
            return true
        }
    }

    private fun openPanel() {
        val s = svc ?: return
        anim = "talk"
        s.startActivity(Intent(s, MainActivity::class.java).putExtra("panel", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val reg = LifecycleRegistry(this)
        private val ssc = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        override val lifecycle: Lifecycle get() = reg
        override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
        fun start() { ssc.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
        fun stop() { reg.currentState = Lifecycle.State.DESTROYED; store.clear() }
    }
}
