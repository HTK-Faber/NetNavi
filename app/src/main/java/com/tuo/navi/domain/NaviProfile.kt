package com.tuo.navi.domain

import org.json.JSONObject

enum class BodyBuild { SLIM, STOCKY }
enum class Stature { TALL, SHORT }

enum class NaviClass(val label: String, val baseHp: Int, val baseAtk: Int, val baseDef: Int, val baseSpd: Int, val role: String) {
    TANK("Tank", 160, 12, 18, 6, "Assorbe i colpi, molta vita"),
    WARRIOR("Guerriero", 120, 18, 10, 12, "Attacco ravvicinato bilanciato"),
    MAGE("Mago", 90, 22, 6, 14, "Attacchi a distanza, fragile"),
    DEFENDER("Difensore", 140, 10, 20, 8, "Parate e contrattacchi");
}

enum class Mood(val label: String) { CALM("calmo"), ALERT("all'erta"), HAPPY("allegro"), SLEEPY("assonnato"), BORED("annoiato") }

data class NaviStats(val hp: Int, val atk: Int, val def: Int, val spd: Int)

data class NaviProfile(
    val id: String,
    val name: String,
    val heightCm: Int,
    val build: BodyBuild,
    val stature: Stature,
    val cls: NaviClass,
    val ratio: Float,
    val appearanceSeed: Long,
    val createdAt: Long,
    val level: Int = 1,
    val exp: Int = 0,
    val bond: Int = 0,          // legame/amicizia 0..1000
    val mood: Mood = Mood.CALM,
    val provisional: Boolean = false   // avatar provvisorio: si può sostituire UNA volta con la propria immagine
) {
    val expToNext: Int get() = 100 + (level - 1) * 50

    fun stats(atLevel: Int = level): NaviStats {
        val g = atLevel - 1
        return when (cls) {
            NaviClass.TANK -> NaviStats(cls.baseHp + g * 12, cls.baseAtk + g, cls.baseDef + g * 2, cls.baseSpd + g / 3)
            NaviClass.WARRIOR -> NaviStats(cls.baseHp + g * 8, cls.baseAtk + g * 2, cls.baseDef + g, cls.baseSpd + g / 2)
            NaviClass.MAGE -> NaviStats(cls.baseHp + g * 5, cls.baseAtk + g * 3, cls.baseDef + g / 2, cls.baseSpd + g)
            NaviClass.DEFENDER -> NaviStats(cls.baseHp + g * 10, cls.baseAtk + g, cls.baseDef + g * 3, cls.baseSpd + g / 3)
        }
    }

    /** Solo questo metodo cambia livello/EXP: l'aspetto non si modifica mai. */
    fun addExp(amount: Int): NaviProfile {
        var e = exp + amount; var l = level
        while (l < 100 && e >= 100 + (l - 1) * 50) { e -= 100 + (l - 1) * 50; l++ }
        if (l >= 100) e = e.coerceAtMost(100 + 99 * 50)
        return copy(exp = e, level = l, bond = (bond + amount).coerceAtMost(1000))
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("heightCm", heightCm)
        put("build", build.name); put("stature", stature.name); put("cls", cls.name)
        put("ratio", ratio.toDouble()); put("seed", appearanceSeed); put("createdAt", createdAt)
        put("level", level); put("exp", exp); put("bond", bond); put("mood", mood.name); put("provisional", provisional)
    }

    companion object {
        fun fromJson(o: JSONObject) = NaviProfile(
            id = o.getString("id"), name = o.getString("name"), heightCm = o.getInt("heightCm"),
            build = BodyBuild.valueOf(o.getString("build")), stature = Stature.valueOf(o.getString("stature")),
            cls = NaviClass.valueOf(o.getString("cls")), ratio = o.getDouble("ratio").toFloat(),
            appearanceSeed = o.getLong("seed"), createdAt = o.getLong("createdAt"),
            level = o.optInt("level", 1), exp = o.optInt("exp", 0), bond = o.optInt("bond", 0),
            mood = runCatching { Mood.valueOf(o.optString("mood", "CALM")) }.getOrDefault(Mood.CALM),
            provisional = o.optBoolean("provisional", false)
        )
    }
}
