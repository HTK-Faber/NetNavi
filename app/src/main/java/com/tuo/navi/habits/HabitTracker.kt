package com.tuo.navi.habits

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.data.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

data class HabitSuggestion(val pkg: String, val count: Int, val share: Float)
data class UnusedApp(val pkg: String, val label: String, val daysUnused: Int)

/**
 * Memoria delle abitudini (solo in locale): quali app apri, in che fascia oraria e giorno.
 * Il Navi suggerisce solo quando un'abitudine si è ripetuta almeno 5 volte con almeno il 50% di probabilità.
 */
object HabitTracker {
    private val file: File get() = File(ProfileStore.baseDir, "habits.json")
    private val senders: File get() = File(ProfileStore.baseDir, "senders.json")
    private const val KEEP_DAYS = 45L

    private data class Launch(val pkg: String, val t: Long)
    private var cache: MutableList<Launch>? = null

    private fun data(): MutableList<Launch> {
        cache?.let { return it }
        val l = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Launch(o.getString("p"), o.getLong("t")) } }.toMutableList()
        }.getOrDefault(mutableListOf())
        cache = l; return l
    }

    fun recordLaunch(pkg: String) {
        val d = data(); val now = System.currentTimeMillis()
        d += Launch(pkg, now); d.removeAll { now - it.t > KEEP_DAYS * 86_400_000L }
        val a = JSONArray(); d.forEach { a.put(JSONObject().put("p", it.pkg).put("t", it.t)) }
        file.writeText(a.toString())
    }

    private fun slot(t: Long): Pair<Int, Boolean> {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val dow = c.get(Calendar.DAY_OF_WEEK)
        return c.get(Calendar.HOUR_OF_DAY) to (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)
    }

    /** App che l'utente apre di solito in questa ora (stesso tipo di giorno: feriale o weekend). */
    fun suggestion(now: Long = System.currentTimeMillis(), exclude: Set<String> = emptySet()): HabitSuggestion? {
        val (h, we) = slot(now)
        val inSlot = data().filter { val (hh, w) = slot(it.t); hh == h && w == we && it.pkg !in exclude }
        // conta una sola apertura per app al giorno
        val perDay = inSlot.groupBy { it.pkg }.mapValues { (_, v) -> v.map { it.t / 86_400_000L }.toSet().size }
        val days = inSlot.map { it.t / 86_400_000L }.toSet().size.coerceAtLeast(1)
        val best = perDay.maxByOrNull { it.value } ?: return null
        val share = best.value / days.toFloat()
        return if (best.value >= 5 && share >= 0.5f) HabitSuggestion(best.key, best.value, share) else null
    }

    fun topApps(n: Int): List<String> = data().groupingBy { it.pkg }.eachCount().entries.sortedByDescending { it.value }.take(n).map { it.key }

    /** Quante volte è arrivata una notifica da questo mittente (solo conteggio, chiave anonimizzata). */
    fun countSender(pkg: String, sender: String): Int {
        val key = (pkg + "|" + sender).hashCode().toString(16)
        val o = runCatching { JSONObject(senders.readText()) }.getOrElse { JSONObject() }
        val n = o.optInt(key, 0) + 1
        o.put(key, n); senders.writeText(o.toString())
        return n
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** App installate da almeno 60 giorni e non usate da almeno 60 (esclude sistema e app "da tenere"). */
    fun unusedApps(ctx: Context, minDays: Int = 60): List<UnusedApp> {
        if (!hasUsageAccess(ctx)) return emptyList()
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(now - 365L * 86_400_000L, now)
        val pm = ctx.packageManager
        val keep = SettingsStore.neverAskUninstall + SettingsStore.snoozedNow()
        return pm.getInstalledApplications(0).asSequence()
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName && it.packageName !in keep }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .mapNotNull { ai ->
                val installed = runCatching { pm.getPackageInfo(ai.packageName, 0).firstInstallTime }.getOrDefault(now)
                val last = stats[ai.packageName]?.lastTimeUsed ?: 0L
                val ref = maxOf(last, installed)
                val days = ((now - ref) / 86_400_000L).toInt()
                if (days >= minDays) UnusedApp(ai.packageName, pm.getApplicationLabel(ai).toString(), days) else null
            }
            .sortedByDescending { it.daysUnused }.toList()
    }
}
