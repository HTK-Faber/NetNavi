package com.tuo.navi.home

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Gli "occhi" del Navi sulla Home del sistema (es. launcher Motorola).
 * Capisce se sei sulla Home, dove sono le icone e quando cambi pagina.
 * Legge solo nome e posizione delle icone della Home; quando sei in un'altra app ascolta
 * soltanto i cambi di finestra e l'ora dei tocchi (per capire se il telefono è in uso), senza leggerne il contenuto.
 */
class NaviAccessibilityService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var launcher: String? = null
    private var lastLabels: Set<String> = emptySet()
    private var screenOn = true
    private var fullEvents = true
    private var resolvedAt = 0L

    private val evalR = Runnable { evalState() }
    private val scanR = Runnable { scan() }
    private val scrollEndR = Runnable { HomeScene.scrolling.value = false; scan() }

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action != Intent.ACTION_SCREEN_OFF
            if (i.action == Intent.ACTION_USER_PRESENT || i.action == Intent.ACTION_SCREEN_ON) HomeScene.markActivity()
            scheduleEval()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        launcher = resolveLauncher(); HomeScene.launcherPkg = launcher
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(screenRx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        })
        HomeNavi.attach(this)
        com.tuo.navi.security.NightlyScan.schedule(this)
        evalState()
    }

    private fun resolveLauncher(): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val def = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (def != null && def != packageName && def != "android") return def
        return packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.firstOrNull { it != packageName }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString()
        // uso del telefono: conta solo che è successo qualcosa (tocco, scorrimento, cambio app), mai il contenuto
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (pkg != packageName) HomeScene.markActivity()
        }
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // i movimenti del Navi cambiano solo posizione/dimensione delle sue finestre: si ignorano
                val ch = e.windowChanges
                val relevant = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
                    AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or AccessibilityEvent.WINDOWS_CHANGE_LAYER
                if (ch and relevant != 0) scheduleEval()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleEval()
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (pkg == launcher && HomeScene.onHome.value) {
                HomeScene.scrolling.value = true
                h.removeCallbacks(scrollEndR); h.postDelayed(scrollEndR, 450)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (pkg == launcher && HomeScene.onHome.value) {
                h.removeCallbacks(scanR); h.postDelayed(scanR, 600)
            }
        }
    }

    private var evalPending = false
    private fun scheduleEval() {
        if (evalPending) return
        evalPending = true
        h.postDelayed({ evalPending = false; evalState() }, 150)
    }

    private fun topAppWindow(): AccessibilityWindowInfo? =
        runCatching { windows }.getOrNull()?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.maxByOrNull { it.layer }

    private fun evalState() {
        val ws = runCatching { windows }.getOrNull() ?: emptyList()
        val top = ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }
        val root = top?.root
        val topPkg = root?.packageName?.toString()
        if (top != null && topPkg == null) { h.postDelayed(evalR, 300); return }
        val sh = screenSize().second
        // tendina notifiche, schermata di blocco o altre finestre di sistema grandi sopra la Home
        val shade = ws.any {
            it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.layer > (top?.layer ?: 0) &&
                Rect().also { r -> it.getBoundsInScreen(r) }.height() > sh / 2
        }
        if (launcher == null || System.currentTimeMillis() - resolvedAt > 60_000L) {
            launcher = resolveLauncher(); HomeScene.launcherPkg = launcher; resolvedAt = System.currentTimeMillis()
        }
        val home = screenOn && topPkg != null && topPkg == launcher && !shade
        setFullEvents(home)
        if (home) { if (!HomeScene.onHome.value) scan(root) } else HomeScene.onHome.value = false
    }

    /** Quando non sei sulla Home ascolta solo i cambi di finestra: meno lavoro per il telefono. */
    private fun setFullEvents(full: Boolean) {
        if (full == fullEvents) return
        val info = serviceInfo ?: return
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
            (if (full) AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0)
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info; fullEvents = full
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val m = getSystemService(WindowManager::class.java).currentWindowMetrics
        val top = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        return Triple(m.bounds.width(), m.bounds.height(), top)
    }

    private fun scan(rootIn: AccessibilityNodeInfo? = null) {
        val root = rootIn ?: topAppWindow()?.root ?: return
        if (root.packageName?.toString() != launcher) return
        val (sw, sh, statusTop) = screenSize()
        val d = resources.displayMetrics.density
        val minI = (30 * d).toInt(); val maxI = (130 * d).toInt()
        val out = ArrayList<HomeIcon>()
        var recents = false
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || !n.isVisibleToUser) return
            val id = n.viewIdResourceName ?: ""
            if (id.contains("overview_panel") || id.contains("recents_view")) recents = true
            val r = Rect(); n.getBoundsInScreen(r)
            val raw = (n.contentDescription ?: n.text)?.toString()
            if ((n.isClickable || n.isLongClickable) && !raw.isNullOrBlank() &&
                r.left >= 0 && r.right <= sw && r.top >= statusTop && r.bottom <= sh) {
                val w = r.width(); val hh = r.height()
                val label = HomeScene.cleanLabel(raw)
                if (w in minI..maxI && hh in minI..(maxI * 16 / 10)) out += HomeIcon(label, Rect(r), false)
                else if (w > maxI && hh in minI..(sh * 45 / 100)) out += HomeIcon(label, Rect(r), true)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        if (recents) { HomeScene.onHome.value = false; return }
        // niente duplicati e niente bottoni dentro i widget (resta il widget)
        val widgets = out.filter { it.widget }
        val clean = out.distinctBy { it.rect }.filter { ic -> ic.widget || widgets.none { it.rect.contains(ic.rect) } }
        val labels = clean.filter { !it.widget }.map { it.label }.toSet()
        if (lastLabels.isNotEmpty() && labels.isNotEmpty()) {
            val inter = labels.intersect(lastLabels).size.toFloat()
            val union = labels.union(lastLabels).size.toFloat()
            if (inter / union < 0.5f) HomeScene.pageTick.value++
        }
        if (labels.isNotEmpty()) lastLabels = labels
        HomeScene.icons.value = clean
        HomeScene.onHome.value = true
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenRx) }
        HomeScene.onHome.value = false
        HomeNavi.detach()
        super.onDestroy()
    }
}
