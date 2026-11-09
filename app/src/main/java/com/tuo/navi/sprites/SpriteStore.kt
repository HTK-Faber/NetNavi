package com.tuo.navi.sprites

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tuo.navi.data.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** Sprite salvati in navi/sprites (inclusi nel file .navi). */
object SpriteStore {
    private val dir: File get() = File(ProfileStore.baseDir, "sprites").apply { mkdirs() }
    private val metaFile: File get() = File(dir, "meta.json")
    private val cache = HashMap<Int, List<Bitmap>>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> get() = _version

    private fun meta(): JSONObject = runCatching { JSONObject(metaFile.readText()) }.getOrElse { JSONObject() }
    fun isSealed(): Boolean = meta().optBoolean("sealed", false)
    fun has(id: Int) = File(dir, "row_$id.png").exists()
    fun missingMaster() = SpriteSpec.masterIds.filterNot { has(it) }.sorted()

    fun canImport(id: Int): Boolean {
        val r = SpriteSpec.row(id)
        return if (r.master) !isSealed() else isSealed()
    }

    fun lockReason(id: Int): String? {
        val r = SpriteSpec.row(id)
        return when {
            r.master && isSealed() -> "Master sigillata: non modificabile"
            !r.master && !isSealed() -> "Disponibile dopo il sigillo della master"
            r.id in 8..14 && !com.tuo.navi.core.Ability.BATTLE_ART.unlocked -> "Si sblocca al livello ${com.tuo.navi.core.Ability.BATTLE_ART.minLevel} (pixel art da combattimento)"
            r.id >= 16 && !com.tuo.navi.core.Ability.EXTRA_SPRITES.unlocked -> "Si sblocca al livello ${com.tuo.navi.core.Ability.EXTRA_SPRITES.minLevel}"
            else -> null
        }
    }

    @Synchronized
    fun save(id: Int, strip: Bitmap) {
        check(canImport(id)) { lockReason(id) ?: "Riga bloccata" }
        if (id == TALK && strip.width / SpriteSpec.CELL == 8) { splitOldTalk(strip); return }
        write(id, strip)
    }

    private fun write(id: Int, strip: Bitmap) {
        File(dir, "row_$id.png").outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache.remove(id); _version.value++
    }

    private const val TALK = 15
    private var migrated = false

    /**
     * La vecchia riga 15 da 8 frame mescolava parla (1-3), sorpreso (4-5), pensa (6-7) e saluta (8): riprodotta a ciclo
     * mentre il Navi parlava, faceva vedere anche le altre espressioni. Ora ogni azione ha la sua riga: qui la si divide.
     */
    private fun splitOldTalk(strip: Bitmap) {
        val c = SpriteSpec.CELL
        fun cell(i: Int) = Bitmap.createBitmap(strip, i * c, 0, c, c)
        fun join(vararg idx: Int): Bitmap {
            val out = Bitmap.createBitmap(c * idx.size, c, Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(out); idx.forEachIndexed { k, i -> cv.drawBitmap(cell(i), (k * c).toFloat(), 0f, null) }
            return out
        }
        write(TALK, join(0, 1, 2, 1))
        if (!has(25)) write(25, join(0, 3, 4, 0))
        if (!has(23)) write(23, join(5, 6))
    }

    @Synchronized
    private fun migrateOnce() {
        if (migrated) return
        migrated = true
        val f = File(dir, "row_$TALK.png"); if (!f.exists()) return
        val strip = BitmapFactory.decodeFile(f.path) ?: return
        if (strip.width / SpriteSpec.CELL == 8) splitOldTalk(strip)
    }

    @Synchronized
    fun remove(id: Int) {
        check(canImport(id)) { lockReason(id) ?: "Riga bloccata" }
        File(dir, "row_$id.png").delete(); cache.remove(id); _version.value++
    }

    @Synchronized
    fun seal() {
        check(!isSealed()) { "Già sigillata" }
        check(missingMaster().isEmpty()) { "Mancano le righe master: ${missingMaster().joinToString()}" }
        metaFile.writeText(meta().put("sealed", true).put("sealedAt", System.currentTimeMillis()).toString())
        _version.value++
    }

    /** Frame (128×128) della riga, oppure null se non caricata. */
    fun frames(id: Int): List<Bitmap>? {
        if (!migrated) migrateOnce()
        cache[id]?.let { return it }
        val f = File(dir, "row_$id.png"); if (!f.exists()) return null
        val strip = BitmapFactory.decodeFile(f.path) ?: return null
        val c = SpriteSpec.CELL
        val list = (0 until strip.width / c).map { Bitmap.createBitmap(strip, it * c, 0, c, c) }
        cache[id] = list; return list
    }

    fun frames(key: String): List<Bitmap>? = SpriteSpec.byKey(key)?.let { frames(it.id) }
    fun importedCount() = dir.listFiles()?.count { it.name.startsWith("row_") } ?: 0

    /** Dopo un cambio di aspetto: toglie le animazioni importate e riapre l'importazione. */
    @Synchronized
    fun resetAll() {
        dir.listFiles()?.forEach { if (it.name.startsWith("row_")) it.delete() }
        metaFile.delete(); cache.clear(); migrated = false; _version.value++
    }

    fun clearCache() { cache.clear(); _version.value++ }
}
