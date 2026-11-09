package com.tuo.navi.security

import com.tuo.navi.ai.NetGate
import com.tuo.navi.data.ProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Una famiglia di app spia nota: nomi dei pacchetti e impronte (SHA-1) dei certificati di firma. */
data class IocEntry(val name: String, val type: String, val packages: List<String>, val certs: List<String>)

/**
 * Elenco delle app spia (stalkerware/spyware) note, scaricato da Internet dal progetto pubblico
 * "stalkerware-indicators" dell'associazione Echap (GitHub). Si scarica solo con l'interruttore Internet acceso,
 * resta salvato sul telefono e il confronto con le tue app avviene tutto in locale: nessun elenco delle tue app esce dal telefono.
 */
object IocStore {
    const val SOURCE_URL = "https://raw.githubusercontent.com/AssoEchap/stalkerware-indicators/master/ioc.yaml"
    const val SOURCE_PAGE = "https://github.com/AssoEchap/stalkerware-indicators"
    private val file: File get() = File(ProfileStore.baseDir, "security/ioc.json")

    @Volatile private var cached: List<IocEntry>? = null

    fun updatedAt(): Long = runCatching { JSONObject(file.readText()).optLong("updatedAt") }.getOrDefault(0L)

    fun load(): List<IocEntry> {
        cached?.let { return it }
        val list = runCatching {
            val a = JSONObject(file.readText()).getJSONArray("entries")
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                IocEntry(o.optString("name"), o.optString("type"), o.getJSONArray("p").let { p -> (0 until p.length()).map { p.getString(it) } },
                    o.getJSONArray("c").let { c -> (0 until c.length()).map { c.getString(it) } })
            }
        }.getOrDefault(emptyList())
        cached = list; return list
    }

    /** Scarica l'elenco aggiornato (serve Internet acceso). Restituisce quante famiglie contiene. */
    fun update(): Int {
        val txt = NetGate.fetchText(SOURCE_URL, timeoutMs = 20_000, maxBytes = 8 shl 20)
        val entries = parseYaml(txt)
        check(entries.size > 10) { "Elenco non valido" }
        file.parentFile?.mkdirs()
        val a = JSONArray()
        entries.forEach { e -> a.put(JSONObject().put("name", e.name).put("type", e.type).put("p", JSONArray(e.packages)).put("c", JSONArray(e.certs))) }
        File(file.path + ".tmp").apply { writeText(JSONObject().put("updatedAt", System.currentTimeMillis()).put("entries", a).toString()); renameTo(file) }
        cached = entries
        return entries.size
    }

    /** Lettura del file ioc.yaml (struttura semplice: "- name:", poi elenchi "packages:" e "certificates:"). */
    fun parseYaml(text: String): List<IocEntry> {
        val out = mutableListOf<IocEntry>()
        var name = ""; var type = ""; var pk = mutableListOf<String>(); var ce = mutableListOf<String>(); var section = ""
        fun flush() { if (name.isNotBlank() && (pk.isNotEmpty() || ce.isNotEmpty())) out += IocEntry(name, type, pk.toList(), ce.toList()) }
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            when {
                line.startsWith("- name:") -> { flush(); name = line.substringAfter(":").trim().trim('"', '\''); type = ""; pk = mutableListOf(); ce = mutableListOf(); section = "" }
                Regex("^ {2}[a-z_]+:.*").matches(line) -> {
                    val key = line.trim().substringBefore(":"); val v = line.substringAfter(":").trim()
                    section = key
                    if (key == "type") type = v
                }
                line.trimStart().startsWith("- ") -> {
                    val v = line.trimStart().removePrefix("- ").trim().trim('"', '\'')
                    when (section) { "packages" -> pk += v; "certificates" -> ce += v.uppercase().replace(":", "") }
                }
            }
        }
        flush()
        return out
    }

    /** Cerca un'app nell'elenco: per nome del pacchetto o per certificato di firma. */
    fun match(entries: List<IocEntry>, pkg: String, certsSha1: List<String>): IocEntry? =
        entries.firstOrNull { e -> pkg in e.packages || certsSha1.any { it in e.certs } }
}
