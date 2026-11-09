package com.tuo.navi.notif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.tuo.navi.core.NaviBus
import com.tuo.navi.core.NaviEvent
import com.tuo.navi.core.NaviNotif
import com.tuo.navi.core.NotifLog
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.habits.HabitTracker
import com.tuo.navi.home.HomeNavi
import com.tuo.navi.phrases.PhraseEngine
import com.tuo.navi.voice.NaviVoice

/**
 * Legge SOLO app e mittente. Il testo (EXTRA_TEXT, EXTRA_BIG_TEXT, messaggi) non viene mai letto.
 * Il mittente si usa solo per categorie di messaggistica/email/chiamate, dove il titolo è il mittente.
 */
class NaviNotificationService : NotificationListenerService() {
    private val lastSeen = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!ProfileStore.exists()) return
        val n = sbn.notification
        if (sbn.packageName == packageName) return
        if (sbn.packageName in SettingsStore.mutedPackages) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        if (n.category in setOf(Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE,
                Notification.CATEGORY_SYSTEM, Notification.CATEGORY_ALARM)) return
        // aggiornamenti della stessa notifica entro 5 s
        val now = System.currentTimeMillis()
        if ((lastSeen[sbn.key] ?: 0L) > now - 5000) return
        lastSeen[sbn.key] = now
        if (lastSeen.size > 200) lastSeen.entries.removeIf { it.value < now - 60_000 }

        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        val ex = n.extras
        val isMessaging = n.category == Notification.CATEGORY_MESSAGE || n.category == Notification.CATEGORY_EMAIL ||
            n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_MISSED_CALL ||
            ex.containsKey(Notification.EXTRA_MESSAGES) || n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MessagingStyle") == true
        val sender: String? = if (isMessaging) {
            val conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            (conv ?: title)?.replace(Regex("\\s*\\(\\d+ messagg[io]+\\)$"), "")?.take(40)?.trim()
        } else null

        val urgent = n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_MISSED_CALL ||
            (isMessaging && (n.priority >= Notification.PRIORITY_HIGH))

        val notif = NaviNotif(sbn.key, sbn.packageName, appName, sender, urgent)
        NotifLog.add(notif)
        NaviBus.emit(NaviEvent.Notification(notif))
        ProfileStore.addExp(1)

        if (!SettingsStore.consumeBudget()) return
        val count = if (sender != null) HabitTracker.countSender(sbn.packageName, sender) else 0
        val line = PhraseEngine.notification(notif, ProfileStore.profile.value, count)
        NaviVoice.say(line)

        // sulla Home il Navi lo dice con il fumetto; nelle altre app resta nascosto e te lo riassume al ritorno
        if (!NaviVoice.alarmRinging()) HomeNavi.notify(line)
    }
}
