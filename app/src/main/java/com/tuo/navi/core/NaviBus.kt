package com.tuo.navi.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Solo metadati: app e mittente. Il testo dei messaggi non viene mai letto né salvato. */
data class NaviNotif(
    val key: String,
    val pkg: String,
    val appName: String,
    val sender: String?,
    val urgent: Boolean,
    val time: Long = System.currentTimeMillis()
)

sealed class NaviEvent {
    data class Notification(val n: NaviNotif) : NaviEvent()
    data class Say(val text: String, val speak: Boolean = true) : NaviEvent()
    object Summon : NaviEvent()
}

object NaviBus {
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()
    fun emit(e: NaviEvent) { _events.tryEmit(e) }
}

/** Registro in memoria delle ultime notifiche (solo metadati, si azzera alla chiusura del processo). */
object NotifLog {
    private val list = ArrayDeque<NaviNotif>()
    @Volatile var homeVisible = false
    @Synchronized fun add(n: NaviNotif) { list.removeAll { it.key == n.key }; list.addFirst(n); while (list.size > 50) list.removeLast() }
    @Synchronized fun since(t: Long): List<NaviNotif> = list.filter { it.time >= t }
    @Synchronized fun recent(): List<NaviNotif> = list.toList()
}
