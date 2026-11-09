package com.tuo.navi

import com.tuo.navi.domain.*
import com.tuo.navi.media.*
import org.junit.Assert.*
import org.junit.Test

class MediaRpgTest {
    private val mb = MediaLogic.MB
    private fun m(id: Long, size: Long, t: Long, video: Boolean = false, b: String = "Camera") = MediaInfo(id, "f$id", size, t, b, video)

    @Test fun large() {
        val l = listOf(m(1, 6 * mb, 0), m(2, 4 * mb, 0), m(3, 150 * mb, 0, true), m(4, 90 * mb, 0, true))
        assertEquals(listOf(3L, 1L), MediaLogic.large(l, MediaFilter()).map { it.items[0].id })
    }
    @Test fun bursts() {
        val l = listOf(m(1, 3 * mb, 1000), m(2, 4 * mb, 3000), m(3, 2 * mb, 5500), m(4, 2 * mb, 60_000), m(5, 1 * mb, 2000, b = "WhatsApp"))
        val g = MediaLogic.bursts(l, 5)
        assertEquals(1, g.size)
        assertEquals(listOf(1L, 2L, 3L), g[0].items.map { it.id })
        assertEquals(2L, g[0].suggestedKeep)
    }
    @Test fun duplicates() {
        val l = listOf(m(1, 5 * mb, 200), m(2, 5 * mb, 100), m(3, 5 * mb, 300), m(4, 7 * mb, 0))
        val g = MediaLogic.duplicates(l) { if (it.id == 3L) "b" else "a" }
        assertEquals(1, g.size); assertEquals(2L, g[0].suggestedKeep); assertEquals(5 * mb, g[0].reclaim)
    }
    @Test fun period() {
        val now = 400L * 86_400_000L
        val l = listOf(m(1, 1, now - 10 * 86_400_000L), m(2, 1, now - 380 * 86_400_000L))
        assertEquals(listOf(2L), MediaLogic.base(l, MediaFilter(period = MediaPeriod.OLD), now).map { it.id })
        assertEquals(listOf(1L), MediaLogic.base(l, MediaFilter(period = MediaPeriod.MONTH), now).map { it.id })
    }
    @Test fun rpg() {
        fun prof(c: NaviClass) = NaviProfile("x", "N", 170, BodyBuild.SLIM, Stature.TALL, c, 1f, 42L, 0L)
        val mage = RpgStatus.attributes(prof(NaviClass.MAGE), 100); val tank = RpgStatus.attributes(prof(NaviClass.TANK), 100)
        assertTrue(mage.first { it.key == "INT" }.value > tank.first { it.key == "INT" }.value)
        assertTrue(tank.first { it.key == "COS" }.value > mage.first { it.key == "COS" }.value)
        assertEquals(mage, RpgStatus.attributes(prof(NaviClass.MAGE), 100))
        assertTrue(mage.all { it.value in 1..99 })
    }
}
