package com.tuo.navi

import com.tuo.navi.sprites.SpriteSpec
import org.junit.Assert.*
import org.junit.Test

class SpriteSpecTest {
    @Test fun parlareSoloParlato() {
        val t = SpriteSpec.byKey("talk")!!
        assertEquals(15, t.id); assertTrue(t.loop); assertEquals(4, t.frames); assertTrue(t.master)
        assertFalse(t.prompt.contains("surprised")); assertFalse(t.prompt.contains("waving"))
    }
    @Test fun espressioniSeparate() {
        assertEquals(23, SpriteSpec.byKey("think")!!.id)
        assertEquals(25, SpriteSpec.byKey("surprised")!!.id)
        assertEquals(26, SpriteSpec.byKey("wave")!!.id)
        assertEquals(SpriteSpec.rows.size, SpriteSpec.rows.map { it.id }.toSet().size)
        assertEquals(SpriteSpec.rows.size, SpriteSpec.rows.map { it.key }.toSet().size)
    }
}
