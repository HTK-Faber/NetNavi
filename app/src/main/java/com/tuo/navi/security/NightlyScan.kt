package com.tuo.navi.security

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.tuo.navi.MainActivity
import com.tuo.navi.R
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.home.HomeNavi
import java.util.Calendar

/**
 * Controllo del sistema pianificato di notte, tra le 3 e le 5 (finestra flessibile: Android lo fa partire
 * quando conviene alla batteria). Se l'interruttore Internet è acceso e l'elenco ha più di 7 giorni, lo aggiorna prima.
 * Al mattino il Navi ti dice com'è andata con un fumetto; una notifica arriva solo se c'è un pericolo.
 */
object NightlyScan {
    const val ACTION = "com.tuo.navi.NIGHTLY_SCAN"
    private const val CHANNEL = "sicurezza"
    private const val WEEK = 7 * 86_400_000L

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, 4242,
        Intent(ctx, NightlyScanReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Prossime ore 3:00 dopo l'istante dato. */
    fun nextAt(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now + 60_000) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(ctx))
        if (!SettingsStore.nightlyScan) return
        am.setWindow(AlarmManager.RTC_WAKEUP, nextAt(), 2 * 3_600_000L, pi(ctx))
    }

    fun runNow(ctx: Context) {
        if (SettingsStore.internetAllowed && System.currentTimeMillis() - IocStore.updatedAt() > WEEK) runCatching { IocStore.update() }
        val r = SecurityCenter.runBlocking(ctx, nightly = true)
        HomeNavi.sayOnReturn(r.summary())
        if (r.worst == Sev.DANGER) notifyDanger(ctx, r)
    }

    private fun notifyDanger(ctx: Context, r: ScanReport) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sicurezza", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Solo quando il controllo notturno trova un pericolo"
        })
        val open = PendingIntent.getActivity(ctx, 4243, Intent(ctx, MainActivity::class.java).putExtra("openScreen", "SECURITY")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_q_shield)
            .setContentTitle("Il Navi ha trovato un pericolo").setContentText(r.findings.first { it.sev == Sev.DANGER }.title)
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(4244, n)
    }
}

class NightlyScanReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> NightlyScan.schedule(ctx)
            NightlyScan.ACTION -> {
                val res = goAsync()
                Thread {
                    try { if (SettingsStore.nightlyScan) NightlyScan.runNow(ctx) } catch (_: Throwable) {} finally {
                        NightlyScan.schedule(ctx); res.finish()
                    }
                }.start()
            }
        }
    }
}
