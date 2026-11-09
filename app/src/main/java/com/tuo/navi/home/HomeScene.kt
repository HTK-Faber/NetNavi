package com.tuo.navi.home

import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow

/** Un'icona (o un widget) della Home del sistema: nome e posizione sullo schermo. */
data class HomeIcon(val label: String, val rect: Rect, val widget: Boolean)

/** Cosa vede il Navi della Home dell'utente. Aggiornato dal servizio di accessibilità. */
object HomeScene {
    val onHome = MutableStateFlow(false)
    val icons = MutableStateFlow<List<HomeIcon>>(emptyList())
    val pageTick = MutableStateFlow(0)
    val scrolling = MutableStateFlow(false)
    @Volatile var launcherPkg: String? = null

    /** Dopo quanto tempo senza usare il telefono il Navi si addormenta. */
    const val SLEEP_MS = 15 * 60_000L
    /** Ultimo uso del telefono (tocchi, schermo acceso, cambio di app). Solo l'ora: nessun contenuto. */
    @Volatile var lastActivity = System.currentTimeMillis()
    /** true se l'ultimo risveglio arriva dopo almeno [SLEEP_MS] di inutilizzo. */
    @Volatile var wokeFromSleep = false
    fun markActivity() {
        val now = System.currentTimeMillis()
        if (now - lastActivity > SLEEP_MS) wokeFromSleep = true
        lastActivity = now
    }
    fun idleMs() = System.currentTimeMillis() - lastActivity

    fun accessibilityOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { it.startsWith(ctx.packageName + "/") }
    }

    fun cleanLabel(raw: String): String = raw.substringBefore(',').substringBefore('\n').trim()
}
