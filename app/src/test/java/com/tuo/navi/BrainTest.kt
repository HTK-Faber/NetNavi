package com.tuo.navi

import com.tuo.navi.ai.ModelCheck
import com.tuo.navi.brain.LearnIntent
import com.tuo.navi.brain.LearnParser
import com.tuo.navi.brain.TextIndex
import org.junit.Assert.*
import org.junit.Test

class BrainTest {
    @Test fun teach() {
        val r = LearnParser.parse("quando dico buongiorno rispondi ciao capo")
        assertTrue(r is LearnIntent.Teach)
        assertEquals("buongiorno", (r as LearnIntent.Teach).trigger)
    }
    @Test fun remember() { assertTrue(LearnParser.parse("ricorda che il gatto si chiama Mimì") is LearnIntent.Remember) }
    @Test fun name() { assertEquals(LearnIntent.Name("Fabrizio"), LearnParser.parse("mi chiamo fabrizio")) }
    @Test fun know() { assertEquals(LearnIntent.WhatYouKnow, LearnParser.parse("cosa sai di me")) }
    @Test fun forgetAll() { assertEquals(LearnIntent.ForgetAll, LearnParser.parse("dimentica tutto")) }
    @Test fun implicit() {
        val r = LearnParser.parse("mi piace la pizza")
        assertTrue(r is LearnIntent.Remember && r.implicit)
    }
    @Test fun noFalsePositive() { assertNull(LearnParser.parse("che ore sono?")) }
    @Test fun rank() {
        val docs = listOf("il gatto si chiama Mimì", "domani riunione in ufficio", "mi piace la pizza")
        assertEquals(0, TextIndex.rank("come si chiama il gatto", docs).first().first)
    }
    @Test fun modelCheck() {
        val task = byteArrayOf(0, 0, 0, 0, 0x50, 0x4b, 3, 4) + ByteArray(8)
        assertNull(ModelCheck.check(task, "m.task", 300_000_000))
        assertNotNull(ModelCheck.check("GGUF".toByteArray() + ByteArray(12), "m.gguf", 300_000_000))
    }
}
