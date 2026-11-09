package com.tuo.navi.nlp

import java.util.Calendar

/**
 * Interprete italiano a regole (offline). Copre i comandi più comuni;
 * se non riconosce nulla restituisce Chat e il testo passa all'IA.
 */
object CommandParser {

    private val weekdays = mapOf(
        "lunedi" to Calendar.MONDAY, "martedi" to Calendar.TUESDAY, "mercoledi" to Calendar.WEDNESDAY,
        "giovedi" to Calendar.THURSDAY, "venerdi" to Calendar.FRIDAY, "sabato" to Calendar.SATURDAY, "domenica" to Calendar.SUNDAY
    )
    private val months = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto",
        "settembre", "ottobre", "novembre", "dicembre")
    private val numbers = mapOf("un" to 1, "uno" to 1, "una" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5,
        "sei" to 6, "sette" to 7, "otto" to 8, "nove" to 9, "dieci" to 10, "undici" to 11, "dodici" to 12, "quindici" to 15,
        "venti" to 20, "trenta" to 30, "quaranta" to 40, "quarantacinque" to 45, "mezz" to 30)

    fun normalize(s: String): String = s.lowercase()
        .replace("à", "a").replace("è", "e").replace("é", "e").replace("ì", "i").replace("ò", "o").replace("ù", "u")
        .replace("'", "' ").replace(Regex("\\s+"), " ").trim()

    fun parse(raw: String, now: Calendar = Calendar.getInstance()): NaviCommand {
        val t = normalize(raw)
        if (t.isBlank()) return NaviCommand.Help
        if (t in setOf("aiuto", "help", "comandi", "cosa sai fare", "cosa sai fare?")) return NaviCommand.Help
        if (Regex("^(stato|stats|statistiche|livello|il tuo livello)\\??$").matches(t)) return NaviCommand.Status

        // NOTE
        Regex("^(nota|appunta|annota|scrivi una nota|prendi nota|segna)( che| di)?[:,]?\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.let { m ->
                if (!m.groupValues[3].lowercase().startsWith("in agenda")) return NaviCommand.AddNote(m.groupValues[3].trim())
            }
        if (Regex("(mostra|leggi|vedi|apri) (le )?note").containsMatchIn(t) || t == "note") return NaviCommand.ShowNotes

        // SVEGLIE (lettura)
        if (Regex("(che|quale|prossima) sveglia|a che ora (mi )?(sveglio|suona)|sveglia ho").containsMatchIn(t)) return NaviCommand.NextAlarm
        if (Regex("(mostra|apri|vedi) (le )?sveglie").containsMatchIn(t)) return NaviCommand.ShowAlarms

        // APP
        if (Regex("app (inutilizzate|che non uso|vecchie)|cosa posso (eliminare|disinstallare)").containsMatchIn(t)) return NaviCommand.UnusedApps
        Regex("^(cos' e|cosa e|che cos' e|a cosa serve) (l' app )?(.+?)\\??$").find(t)?.let { return NaviCommand.WhatIsApp(it.groupValues[3].trim()) }
        Regex("^(apri|avvia|lancia) (.+)$").find(t)?.let { return NaviCommand.OpenApp(it.groupValues[2].trim()) }

        // TIMER
        if (t.startsWith("timer") || t.contains("conto alla rovescia") || Regex("^avvisami tra").containsMatchIn(t)) {
            val secs = parseDuration(t)
            if (secs != null && secs > 0) return NaviCommand.Timer(secs, extractLabel(t))
        }

        // SVEGLIA (creazione)
        if (Regex("\\bsveglia(mi)?\\b|svegliami").containsMatchIn(t)) {
            val time = parseTime(t)
            if (time != null) {
                val day = parseDayOffset(t, now) ?: -1
                return NaviCommand.SetAlarm(time.first, time.second, extractLabel(t), day)
            }
            parseDuration(t)?.let { d ->
                val c = (now.clone() as Calendar).apply { add(Calendar.SECOND, d) }
                return NaviCommand.SetAlarm(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), null, -1)
            }
        }

        // APPUNTAMENTI / PROMEMORIA
        if (Regex("appuntamento|ricordami|promemoria|evento|riunione|calendario|segna in agenda|metti in agenda").containsMatchIn(t)) {
            val cal = (now.clone() as Calendar)
            val inDur = if (t.contains(" tra ")) parseDuration(t) else null
            val time = parseTime(t)
            val date = parseDate(t, now)
            val allDay: Boolean
            if (inDur != null && time == null && date == null) {
                cal.add(Calendar.SECOND, inDur); allDay = false
            } else {
                if (date != null) cal.timeInMillis = date.timeInMillis
                if (time != null) {
                    cal.set(Calendar.HOUR_OF_DAY, time.first); cal.set(Calendar.MINUTE, time.second)
                    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                    if (date == null && cal.before(now)) cal.add(Calendar.DAY_OF_MONTH, 1)
                    allDay = false
                } else {
                    if (date == null) return NaviCommand.Chat(raw)
                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); allDay = true
                }
            }
            val title = extractTitle(raw).ifBlank { "Promemoria" }
            val end = cal.timeInMillis + if (allDay) 86_400_000L else 3_600_000L
            return NaviCommand.AddEvent(cal.timeInMillis, end, title, allDay)
        }
        return NaviCommand.Chat(raw)
    }

    /** "alle 7", "alle 7:30", "alle 7 e mezza", "alle 19.15", "alle 8 di sera", "a mezzogiorno". */
    fun parseTime(t: String): Pair<Int, Int>? {
        if (t.contains("mezzogiorno")) return 12 to 0
        if (t.contains("mezzanotte")) return 0 to 0
        val m = Regex("(?:alle|all' |ore|per le|verso le)\\s*(\\d{1,2})(?:[:.](\\d{2}))?(?:\\s+e\\s+(mezza|mezzo|un quarto|tre quarti|\\d{1,2}))?").find(t)
            ?: Regex("\\b(\\d{1,2})[:.](\\d{2})\\b").find(t) ?: return null
        var h = m.groupValues[1].toInt()
        var min = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: 0
        when (val extra = m.groupValues.getOrNull(3).orEmpty()) {
            "mezza", "mezzo" -> min = 30
            "un quarto" -> min = 15
            "tre quarti" -> min = 45
            "" -> {}
            else -> min = extra.toIntOrNull() ?: min
        }
        if (Regex("di sera|stasera|del pomeriggio|di pomeriggio|questa sera").containsMatchIn(t) && h in 1..11) h += 12
        if (t.contains("di notte") && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }

    /** "tra 10 minuti", "timer 1 ora e 30", "5 min", "mezz'ora". */
    fun parseDuration(t: String): Int? {
        var total = 0; var found = false
        if (t.contains("mezz' ora") || t.contains("mezzora")) { total += 1800; found = true }
        Regex("(\\d+|${numbers.keys.joinToString("|")})\\s*(ore|ora|h|minuti|minuto|min|m|secondi|secondo|sec|s)\\b").findAll(t).forEach {
            val n = it.groupValues[1].toIntOrNull() ?: numbers[it.groupValues[1]] ?: return@forEach
            total += when (it.groupValues[2]) { "ore", "ora", "h" -> n * 3600; "minuti", "minuto", "min", "m" -> n * 60; else -> n }
            found = true
        }
        if (found && Regex("ora e (\\d+)$").containsMatchIn(t)) total += (Regex("ora e (\\d+)$").find(t)!!.groupValues[1].toInt()) * 60
        return if (found) total else null
    }

    fun parseDayOffset(t: String, now: Calendar): Int? {
        val d = parseDate(t, now) ?: return null
        val a = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }
        val b = (d.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }
        return ((b.timeInMillis - a.timeInMillis) / 86_400_000L).toInt()
    }

    fun parseDate(t: String, now: Calendar): Calendar? {
        val c = (now.clone() as Calendar)
        when {
            t.contains("dopodomani") -> { c.add(Calendar.DAY_OF_MONTH, 2); return c }
            t.contains("domani") -> { c.add(Calendar.DAY_OF_MONTH, 1); return c }
            Regex("\\boggi\\b|stasera|stamattina|questa sera|oggi pomeriggio").containsMatchIn(t) -> return c
        }
        Regex("tra (\\d+|${numbers.keys.joinToString("|")}) giorni").find(t)?.let {
            c.add(Calendar.DAY_OF_MONTH, it.groupValues[1].toIntOrNull() ?: numbers[it.groupValues[1]] ?: 0); return c
        }
        for ((name, dow) in weekdays) if (Regex("\\b$name\\b").containsMatchIn(t)) {
            var diff = (dow - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (diff == 0 || t.contains("$name prossimo") || t.contains("prossimo $name") || t.contains("prossima $name")) diff = if (diff == 0) 7 else diff
            c.add(Calendar.DAY_OF_MONTH, diff); return c
        }
        Regex("\\b(\\d{1,2})\\s+(${months.joinToString("|")})").find(t)?.let {
            val day = it.groupValues[1].toInt(); val mon = months.indexOf(it.groupValues[2])
            c.set(Calendar.MONTH, mon); c.set(Calendar.DAY_OF_MONTH, day)
            if (c.before(now)) c.add(Calendar.YEAR, 1); return c
        }
        Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b").find(t)?.let {
            val day = it.groupValues[1].toInt(); val mon = it.groupValues[2].toInt() - 1
            c.set(Calendar.MONTH, mon); c.set(Calendar.DAY_OF_MONTH, day)
            it.groupValues[3].takeIf { y -> y.isNotEmpty() }?.toInt()?.let { y -> c.set(Calendar.YEAR, if (y < 100) 2000 + y else y) }
            if (c.before(now) && it.groupValues[3].isEmpty()) c.add(Calendar.YEAR, 1); return c
        }
        Regex("\\bil (\\d{1,2})\\b").find(t)?.let {
            val day = it.groupValues[1].toInt(); if (day in 1..31) {
                c.set(Calendar.DAY_OF_MONTH, day); if (c.before(now)) c.add(Calendar.MONTH, 1); return c
            }
        }
        return null
    }

    private fun extractLabel(t: String): String? =
        Regex("\\b(?:per|con scritto|etichetta|chiamata)\\s+(?!le\\b)([a-z][^0-9]{2,40})$").find(t)?.groupValues?.get(1)?.trim()
            ?.takeUnless { it.startsWith("domani") || it.startsWith("oggi") }

    /** Toglie dal testo originale parole chiave, date e orari per ottenere il titolo. */
    fun extractTitle(raw: String): String {
        var s = normalize(raw)
        val drop = listOf(
            "ricordami di", "ricordami che", "ricordami", "metti in agenda", "segna in agenda", "crea un appuntamento", "crea appuntamento",
            "nuovo appuntamento", "appuntamento con", "appuntamento", "promemoria per", "promemoria", "crea evento", "evento", "aggiungi al calendario",
            "nel calendario", "in calendario", "dopodomani", "domani", "oggi", "stasera", "stamattina", "questa sera", "di sera", "di mattina", "del pomeriggio",
            "mezzogiorno", "mezzanotte"
        )
        s = s.replace(Regex("(?:alle|all' |ore|per le|verso le)\\s*\\d{1,2}(?:[:.]\\d{2})?(?:\\s+e\\s+(?:mezza|mezzo|un quarto|tre quarti|\\d{1,2}))?"), " ")
        s = s.replace(Regex("tra \\S+ (?:minuti|minuto|ore|ora|giorni)"), " ")
        s = s.replace(Regex("\\b(il |l' )?\\d{1,2}\\s+(${months.joinToString("|")})"), " ").replace(Regex("\\b\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?"), " ")
        s = s.replace(Regex("\\bil \\d{1,2}\\b"), " ")
        weekdays.keys.forEach { s = s.replace(Regex("\\b(prossimo |prossima )?$it( prossimo)?\\b"), " ") }
        drop.forEach { s = s.replace(it, " ") }
        s = s.replace(Regex("\\b(di|il|lo|la|per|a|al|del)\\s*$"), "").replace(Regex("^\\s*(di|per|che|:)\\s+"), "")
        val origWords = raw.split(Regex("\\s+"))
        val out = s.replace(Regex("\\s+"), " ").trim().split(" ").filter { it.isNotEmpty() }.joinToString(" ") { w ->
            origWords.firstOrNull { normalize(it).trim(',', '.', '!', '?') == w }?.trim(',', '.', '!', '?') ?: w
        }
        return out.replaceFirstChar { it.uppercase() }
    }
}
