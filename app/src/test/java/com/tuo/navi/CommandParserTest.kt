package com.tuo.navi

import com.tuo.navi.nlp.CommandParser
import com.tuo.navi.nlp.NaviCommand
import org.junit.Test
import java.util.Calendar

class CommandParserTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 5, 10, 0, 0) } // giovedì
    private fun p(s: String) = CommandParser.parse(s, now).also { println("$s -> $it ${(it as? NaviCommand)?.let { c -> runCatching { c.describe() }.getOrNull() }}") }

    @Test fun samples() {
        listOf(
            "sveglia alle 7", "Svegliami domani alle 6:30", "sveglia alle 7 e mezza per palestra", "sveglia alle 8 di sera",
            "timer 10 minuti", "timer 1 ora e 30", "che sveglia ho?", "nota: comprare il latte", "Appunta di chiamare il medico",
            "appuntamento domani alle 15 dentista", "ricordami venerdì alle 9 di chiamare Marco", "ricordami tra 20 minuti di spegnere il forno",
            "promemoria il 12 dicembre compleanno Luca", "Riunione lunedì prossimo alle 14:30 con il capo", "app inutilizzate",
            "cos'è Telegram", "apri WhatsApp", "come stai?", "raccontami una barzelletta", "sveglia dopodomani alle 5"
        ).forEach { p(it) }
    }
}
