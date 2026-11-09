package com.tuo.navi.actions

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Esegue le azioni delegando alle app di sistema (Orologio, Calendario). */
object ActionExecutor {

    fun setAlarm(ctx: Context, hour: Int, minute: Int, label: String?): Result<Unit> = runCatching {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        start(ctx, i, "Nessuna app Orologio compatibile")
    }

    fun setTimer(ctx: Context, seconds: Int, label: String?): Result<Unit> = runCatching {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        start(ctx, i, "Nessuna app Orologio compatibile per i timer")
    }

    fun showAlarms(ctx: Context): Result<Unit> = runCatching {
        start(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "App Orologio non trovata")
    }

    /** Apre il Calendario con l'evento precompilato: l'utente conferma il salvataggio. */
    fun addEvent(ctx: Context, startMs: Long, endMs: Long, title: String, allDay: Boolean): Result<Unit> = runCatching {
        val i = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.HAS_ALARM, 1)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        start(ctx, i, "Nessuna app Calendario trovata")
    }

    /** Prossima sveglia di qualunque app (solo lettura: il Navi non la tocca). */
    fun nextAlarm(ctx: Context): Long? = ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime

    fun describeNextAlarm(ctx: Context): String {
        val t = nextAlarm(ctx) ?: return "Non hai sveglie impostate."
        val now = System.currentTimeMillis()
        val day = when (((t / 86_400_000L) - (now / 86_400_000L)).toInt()) {
            0 -> "oggi"; 1 -> "domani"
            else -> SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN).format(Date(t))
        }
        return "La prossima sveglia suona $day alle ${SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date(t))}."
    }

    fun openApp(ctx: Context, pkg: String): Result<Unit> = runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: error("App non avviabile")
        start(ctx, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "App non trovata")
    }

    /** Chiede al sistema di disinstallare: è sempre l'utente a confermare. */
    fun requestUninstall(ctx: Context, pkg: String): Result<Unit> = runCatching {
        start(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Impossibile disinstallare")
    }

    private fun start(ctx: Context, i: Intent, err: String) {
        try { ctx.startActivity(i) } catch (e: ActivityNotFoundException) { error(err) }
    }
}
