package com.tuo.navi.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tuo.navi.domain.NaviProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * Archivio dell'avatar. create() fallisce se esiste già un profilo o se l'installazione è stata bloccata da un trasferimento.
 * Il nome non cambia mai; l'aspetto si può cambiare quando vuoi con changeAppearance() (la classe viene ricalcolata,
 * livello, EXP, legame e ricordi restano).
 */
object ProfileStore {
    private lateinit var dir: File
    private val _profile = MutableStateFlow<NaviProfile?>(null)
    val profile: StateFlow<NaviProfile?> get() = _profile

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "navi").apply { mkdirs() }
        _profile.value = runCatching { NaviProfile.fromJson(JSONObject(File(dir, "profile.json").readText())) }.getOrNull()
    }

    val baseDir: File get() = dir
    val bustFile: File get() = File(dir, "bust.png")
    val pixelFile: File get() = File(dir, "pixel.png")
    /** Figura intera (ritaglio della tua immagine): sfondo del pannello del Navi. */
    val fullFile: File get() = File(dir, "full.png")
    fun hasFull() = fullFile.exists()
    private val lockFile: File get() = File(dir, "transferred.lock")

    fun exists() = _profile.value != null
    fun isLockedByTransfer() = lockFile.exists()

    @Synchronized
    fun create(p: NaviProfile, bust: Bitmap, pixel: Bitmap, full: Bitmap? = null) {
        check(!exists()) { "Avatar già creato: per cambiare aspetto usa Impostazioni > Cambia aspetto." }
        check(!isLockedByTransfer()) { "Questa installazione ha già trasferito il suo avatar." }
        saveBitmap(bustFile, bust); saveBitmap(pixelFile, pixel); full?.let { saveBitmap(fullFile, it) }
        File(dir, "profile.json").writeText(p.toJson().toString())
        _profile.value = p
    }

    /** Usato solo per EXP, umore e legame. */
    @Synchronized
    fun update(transform: (NaviProfile) -> NaviProfile) {
        val cur = _profile.value ?: return
        val next = transform(cur).copy(
            id = cur.id, name = cur.name, heightCm = cur.heightCm, build = cur.build, stature = cur.stature,
            cls = cur.cls, ratio = cur.ratio, appearanceSeed = cur.appearanceSeed, createdAt = cur.createdAt, provisional = cur.provisional
        )
        File(dir, "profile.json").writeText(next.toJson().toString())
        _profile.value = next
    }

    /** Versioni precedenti: sostituzione del provvisorio (ora si usa changeAppearance). */
    @Synchronized
    fun replaceProvisional(bust: Bitmap, pixel: Bitmap, build: com.tuo.navi.domain.BodyBuild, stature: com.tuo.navi.domain.Stature,
                           cls: com.tuo.navi.domain.NaviClass, ratio: Float, full: Bitmap? = null) {
        val cur = _profile.value ?: error("Nessun avatar")
        check(cur.provisional) { "L'aspetto è già definitivo." }
        saveBitmap(bustFile, bust); saveBitmap(pixelFile, pixel); full?.let { saveBitmap(fullFile, it) }
        val next = cur.copy(build = build, stature = stature, cls = cls, ratio = ratio, provisional = false)
        File(dir, "profile.json").writeText(next.toJson().toString())
        _profile.value = next
    }

    /** Nuovo aspetto in qualsiasi momento: altezza, corporatura e classe ricalcolate. Livello, EXP, legame e ricordi restano. */
    @Synchronized
    fun changeAppearance(bust: Bitmap, pixel: Bitmap, heightCm: Int, build: com.tuo.navi.domain.BodyBuild, stature: com.tuo.navi.domain.Stature,
                         cls: com.tuo.navi.domain.NaviClass, ratio: Float, full: Bitmap? = null) {
        val cur = _profile.value ?: error("Nessun avatar")
        saveBitmap(bustFile, bust); saveBitmap(pixelFile, pixel)
        if (full != null) saveBitmap(fullFile, full)
        val next = cur.copy(heightCm = heightCm, build = build, stature = stature, cls = cls, ratio = ratio, provisional = false)
        File(dir, "profile.json").writeText(next.toJson().toString())
        _profile.value = next
    }

    fun addExp(n: Int) = update { it.addExp(n) }

    /** Importazione da file .navi: consentita solo su installazione pulita. */
    @Synchronized
    fun importRaw(profileJson: String, files: Map<String, ByteArray>) {
        check(!exists()) { "Importazione possibile solo su un'installazione pulita." }
        check(!isLockedByTransfer()) { "Installazione bloccata." }
        files.forEach { (name, bytes) ->
            val f = File(dir, name); f.parentFile?.mkdirs(); f.writeBytes(bytes)
        }
        File(dir, "profile.json").writeText(profileJson)
        _profile.value = NaviProfile.fromJson(JSONObject(profileJson))
    }

    /** Dopo l'esportazione: cancella l'avatar e blocca l'installazione (spostamento, non copia). */
    @Synchronized
    fun wipeAfterTransfer() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        lockFile.writeText(System.currentTimeMillis().toString())
        _profile.value = null
    }

    /** Immagine della scheda del personaggio (statistiche): si può cambiare quando vuoi. */
    @Synchronized
    fun setFull(full: Bitmap) {
        check(exists()) { "Nessun avatar" }
        saveBitmap(fullFile, full)
    }
    /** Torna a usare il busto nella scheda del personaggio. */
    @Synchronized
    fun clearFull() { fullFile.delete() }

    fun loadFull(): Bitmap? = fullFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun loadBust(): Bitmap? = bustFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    fun loadPixel(): Bitmap? = pixelFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    private fun saveBitmap(f: File, b: Bitmap) = f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
