package com.tuo.navi.domain

import kotlin.random.Random

data class RpgStat(val key: String, val label: String, val value: Int, val max: Int)

/**
 * Status GDR del personaggio. Per ora i parametri sono PROVVISORI: valori casuali ma stabili
 * (dipendono dall'aspetto del Navi) e coerenti con la classe. Verranno sostituiti dal sistema di battaglia.
 */
object RpgStatus {
    /** Peso degli attributi per classe: Forza, Agilità, Intelletto, Spirito, Costituzione, Fortuna. */
    private fun bias(c: NaviClass): IntArray = when (c) {
        NaviClass.TANK -> intArrayOf(8, 3, 2, 5, 10, 4)
        NaviClass.WARRIOR -> intArrayOf(10, 7, 3, 4, 6, 5)
        NaviClass.MAGE -> intArrayOf(2, 6, 10, 9, 3, 5)
        NaviClass.DEFENDER -> intArrayOf(6, 4, 3, 7, 9, 5)
    }
    private val names = listOf("FOR" to "Forza", "AGI" to "Agilità", "INT" to "Intelletto", "SPI" to "Spirito", "COS" to "Costituzione", "FOR2" to "Fortuna")

    fun attributes(p: NaviProfile, level: Int): List<RpgStat> {
        val rnd = Random(p.appearanceSeed xor p.cls.ordinal.toLong())
        val b = bias(p.cls)
        return names.mapIndexed { i, (k, l) ->
            val base = 4 + b[i] * 2 + rnd.nextInt(0, 7)
            val v = (base + (level - 1) * (b[i] + 2) / 8).coerceIn(1, 99)
            RpgStat(k, l, v, 99)
        }
    }

    fun combat(p: NaviProfile, level: Int): List<RpgStat> {
        val s = p.stats(level)
        val rnd = Random(p.appearanceSeed)
        val mpBase = when (p.cls) { NaviClass.MAGE -> 80; NaviClass.DEFENDER -> 45; NaviClass.WARRIOR -> 30; NaviClass.TANK -> 25 }
        val mpGrow = when (p.cls) { NaviClass.MAGE -> 6; NaviClass.DEFENDER -> 3; else -> 2 }
        val mp = mpBase + (level - 1) * mpGrow + rnd.nextInt(0, 10)
        val crit = (3 + rnd.nextInt(0, 4) + if (p.cls == NaviClass.WARRIOR) 4 else 0).coerceAtMost(50)
        return listOf(
            RpgStat("PV", "Punti vita", s.hp, NaviClass.values().maxOf { it.baseHp } + 99 * 12),
            RpgStat("PM", "Punti magia", mp, 80 + 99 * 6 + 10),
            RpgStat("ATT", "Attacco", s.atk, 22 + 99 * 3),
            RpgStat("DIF", "Difesa", s.def, 20 + 99 * 3),
            RpgStat("VEL", "Velocità", s.spd, 14 + 99),
            RpgStat("CRI", "Critico %", crit, 50)
        )
    }
}
