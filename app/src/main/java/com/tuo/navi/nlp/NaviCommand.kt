package com.tuo.navi.nlp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class NaviCommand {
    /** dayOffset: 0 oggi, 1 domani, ... (-1 = prossima occorrenza). */
    data class SetAlarm(val hour: Int, val minute: Int, val label: String?, val dayOffset: Int) : NaviCommand()
    data class Timer(val seconds: Int, val label: String?) : NaviCommand()
    data class AddEvent(val startMs: Long, val endMs: Long, val title: String, val allDay: Boolean) : NaviCommand()
    data class AddNote(val text: String) : NaviCommand()
    object ShowNotes : NaviCommand()
    object NextAlarm : NaviCommand()
    object ShowAlarms : NaviCommand()
    object UnusedApps : NaviCommand()
    object Help : NaviCommand()
    object Status : NaviCommand()
    data class WhatIsApp(val name: String) : NaviCommand()
    data class OpenApp(val name: String) : NaviCommand()
    data class Chat(val text: String) : NaviCommand()
    /** Internet spento nel Navi: apre la ricerca nel browser del telefono (dopo conferma). */
    data class OpenWebSearch(val query: String) : NaviCommand()

    /** Le azioni che creano qualcosa richiedono conferma. */
    val needsConfirm: Boolean get() = this is SetAlarm || this is Timer || this is AddEvent || this is AddNote

    fun describe(): String {
        val df = SimpleDateFormat("EEEE d MMMM 'alle' HH:mm", Locale.ITALIAN)
        return when (this) {
            is SetAlarm -> {
                val d = when (dayOffset) { 0 -> "oggi "; 1 -> "domani "; else -> "" }
                "Sveglia ${d}alle %02d:%02d".format(hour, minute) + (label?.let { " ($it)" } ?: "")
            }
            is Timer -> "Timer di " + (if (seconds >= 3600) "${seconds / 3600} h ${(seconds % 3600) / 60} min" else if (seconds >= 60) "${seconds / 60} min" else "$seconds s") + (label?.let { " ($it)" } ?: "")
            is AddEvent -> "Appuntamento \"$title\" " + if (allDay) SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN).format(Date(startMs)) + " (tutto il giorno)" else df.format(Date(startMs))
            is AddNote -> "Nota: \"$text\""
            is OpenWebSearch -> "Ricerca nel browser: \"$query\""
            else -> toString()
        }
    }
}
