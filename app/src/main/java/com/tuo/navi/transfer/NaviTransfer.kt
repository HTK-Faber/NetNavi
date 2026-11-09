package com.tuo.navi.transfer

import android.content.Context
import android.net.Uri
import com.tuo.navi.data.ProfileStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * File .navi = "NAVI" + versione + salt(16) + iv(12) + AES-256-GCM(zip della cartella avatar).
 * L'esportazione è uno SPOSTAMENTO: dopo la scrittura riuscita il telefono di origine cancella l'avatar.
 * Contiene profilo, immagini, sprite, note e abitudini. Le impostazioni (chiave API) non vengono esportate.
 */
object NaviTransfer {
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'I'.code.toByte())
    private const val VERSION: Byte = 3
    private const val ITER = 120_000

    fun export(ctx: Context, target: Uri, password: String) {
        require(password.length >= 6) { "Password di almeno 6 caratteri" }
        check(ProfileStore.exists()) { "Nessun avatar da trasferire" }
        val zip = zipDir(ProfileStore.baseDir)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val enc = c.doFinal(zip)
        ctx.contentResolver.openOutputStream(target, "w")!!.use { o ->
            o.write(MAGIC); o.write(byteArrayOf(VERSION)); o.write(salt); o.write(iv); o.write(enc); o.flush()
        }
        // verifica che il file sia leggibile prima di cancellare
        val back = ctx.contentResolver.openInputStream(target)!!.use { it.readBytes() }
        decrypt(back, password)
        ProfileStore.wipeAfterTransfer()
    }

    fun import(ctx: Context, source: Uri, password: String) {
        val bytes = ctx.contentResolver.openInputStream(source)!!.use { it.readBytes() }
        val zip = decrypt(bytes, password)
        var profileJson: String? = null
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zi ->
            while (true) {
                val e = zi.nextEntry ?: break
                if (e.isDirectory || e.name.contains("..")) continue
                val data = zi.readBytes()
                when (e.name) {
                    "profile.json" -> profileJson = String(data)
                    "transferred.lock" -> Unit
                    else -> files[e.name] = data
                }
            }
        }
        ProfileStore.importRaw(profileJson ?: error("File .navi senza profilo"), files)
    }

    private fun decrypt(bytes: ByteArray, password: String): ByteArray {
        require(bytes.size > 33 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)) { "Non è un file .navi valido" }
        val salt = bytes.copyOfRange(5, 21); val iv = bytes.copyOfRange(21, 33)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return try { c.doFinal(bytes, 33, bytes.size - 33) } catch (e: Exception) { throw IllegalArgumentException("Password errata o file danneggiato") }
    }

    private fun key(pw: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(pw.toCharArray(), salt, ITER, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    private fun zipDir(dir: File): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zo ->
            dir.walkTopDown().filter { it.isFile && it.name != "transferred.lock" }.forEach { f ->
                zo.putNextEntry(ZipEntry(f.relativeTo(dir).path)); f.inputStream().use { it.copyTo(zo) }; zo.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
