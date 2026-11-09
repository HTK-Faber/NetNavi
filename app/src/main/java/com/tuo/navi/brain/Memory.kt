package com.tuo.navi.brain

import com.tuo.navi.data.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class MemKind(val label: String) {
    FACT("Fatto"), PERSON("Persona"), PREFERENCE("Preferenza"), DATE("Data importante"), HABIT("Abitudine"),
    RULE("Risposta insegnata"), EXAMPLE("Risposta approvata"), CORRECTION("Correzione")
}

/**
 * Un ricordo del Navi.
 *  - FACT/PERSON/PREFERENCE/DATE/HABIT: text = ciò che hai detto o che ha osservato;
 *  - RULE: key = frase che dici, text = risposta da dare;
 *  - EXAMPLE/CORRECTION: key = domanda, text = risposta giusta.
 */
data class MemoryItem(
    val id: Long, val kind: MemKind, val text: String, val key: String = "",
    val created: Long = System.currentTimeMillis(), val lastUsed: Long = 0, val uses: Int = 0, val source: String = "tu"
) {
    /** Testo usato per la ricerca. */
    val searchText: String get() = if (key.isBlank()) text else "$key $text"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.name).put("text", text).put("key", key)
        .put("created", created).put("lastUsed", lastUsed).put("uses", uses).put("source", source)

    companion object {
        fun fromJson(o: JSONObject) = MemoryItem(
            o.getLong("id"), runCatching { MemKind.valueOf(o.getString("kind")) }.getOrDefault(MemKind.FACT),
            o.getString("text"), o.optString("key"), o.optLong("created"), o.optLong("lastUsed"), o.optInt("uses"), o.optString("source", "tu")
        )
    }
}

/** Stile personale appreso (abilità STYLE). */
data class NaviStyle(val userName: String = "", val replyLength: String = "breve") {
    fun toJson(): JSONObject = JSONObject().put("userName", userName).put("replyLength", replyLength)
    companion object { fun fromJson(o: JSONObject?) = NaviStyle(o?.optString("userName").orEmpty(), o?.optString("replyLength")?.ifBlank { null } ?: "breve") }
}

/**
 * Memoria del Navi: un file JSON dentro la cartella privata dell'app (navi/brain/memory.json).
 * Non lascia mai il telefono: non è inclusa nei backup di Android e si sposta solo con il trasferimento .navi cifrato.
 * Non è addestramento del modello: il modello di base resta lo stesso e il Navi gli passa i ricordi pertinenti.
 */
object MemoryStore {
    private const val MAX_ITEMS = 600
    private val file: File get() = File(ProfileStore.baseDir, "brain/memory.json")
    private val _items = MutableStateFlow<List<MemoryItem>>(emptyList())
    val items: StateFlow<List<MemoryItem>> get() = _items
    private val _style = MutableStateFlow(NaviStyle())
    val style: StateFlow<NaviStyle> get() = _style
    private var loaded = false
    private val counters = HashMap<String, Int>()

    @Synchronized
    fun load() {
        runCatching {
            val o = JSONObject(file.readText())
            val a = o.optJSONArray("items") ?: JSONArray()
            _items.value = (0 until a.length()).mapNotNull { runCatching { MemoryItem.fromJson(a.getJSONObject(it)) }.getOrNull() }
            _style.value = NaviStyle.fromJson(o.optJSONObject("style"))
            counters.clear(); o.optJSONObject("counters")?.let { c -> c.keys().forEach { k -> counters[k] = c.optInt(k) } }
        }.onFailure { _items.value = emptyList(); _style.value = NaviStyle(); counters.clear() }
        loaded = true
    }

    private fun ensure() { if (!loaded) load() }

    @Synchronized
    private fun save() {
        file.parentFile?.mkdirs()
        val a = JSONArray(); _items.value.forEach { a.put(it.toJson()) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("version", 1).put("items", a).put("style", _style.value.toJson())
            .put("counters", JSONObject().apply { counters.forEach { (k, v) -> put(k, v) } }).toString())
        tmp.renameTo(file)
    }

    /** Aggiunge un ricordo. Se ne esiste uno quasi uguale lo aggiorna invece di duplicarlo. Restituisce true se è nuovo. */
    @Synchronized
    fun add(kind: MemKind, text: String, key: String = "", source: String = "tu"): Boolean {
        ensure()
        val t = text.trim().trimEnd('.'); if (t.length < 2) return false
        val list = _items.value.toMutableList()
        val dup = list.indexOfFirst {
            it.kind == kind && when (kind) {
                MemKind.RULE, MemKind.EXAMPLE, MemKind.CORRECTION -> TextIndex.normalize(it.key) == TextIndex.normalize(key) || TextIndex.jaccard(it.key, key) >= 0.85
                else -> TextIndex.jaccard(it.text, t) >= 0.8
            }
        }
        val isNew = dup < 0
        if (dup >= 0) list[dup] = list[dup].copy(text = t, key = key.ifBlank { list[dup].key }, created = System.currentTimeMillis())
        else list.add(0, MemoryItem(System.currentTimeMillis() + list.size, kind, t, key.trim(), source = source))
        // un ricordo corretto sostituisce l'esempio approvato sulla stessa domanda
        if (kind == MemKind.CORRECTION) list.removeAll { it.kind == MemKind.EXAMPLE && TextIndex.jaccard(it.key, key) >= 0.85 }
        _items.value = trim(list); save(); return isNew
    }

    private fun trim(l: List<MemoryItem>): List<MemoryItem> =
        if (l.size <= MAX_ITEMS) l else l.sortedByDescending { maxOf(it.created, it.lastUsed) + it.uses * 86_400_000L }.take(MAX_ITEMS)

    /** Sostituisce il ricordo con la stessa chiave (es. "profilo:libro_ora"): tiene solo la risposta più recente. */
    @Synchronized
    fun replaceByKey(kind: MemKind, key: String, text: String) {
        ensure()
        val list = _items.value.filterNot { it.key == key }.toMutableList()
        list.add(0, MemoryItem(System.currentTimeMillis() + list.size, kind, text.trim().trimEnd('.'), key, source = "domanda"))
        _items.value = trim(list); save()
    }

    @Synchronized fun remove(id: Long) { ensure(); _items.value = _items.value.filterNot { it.id == id }; save() }
    @Synchronized fun clear() { _items.value = emptyList(); _style.value = NaviStyle(); counters.clear(); save() }

    /** Contatori per l'auto-apprendimento (es. quante volte metti la sveglia alle 7). */
    @Synchronized
    fun bump(key: String): Int { ensure(); val n = (counters[key] ?: 0) + 1; counters[key] = n; save(); return n }

    /** Cancella i ricordi che corrispondono alla frase ("dimentica Marco"). */
    @Synchronized
    fun forget(query: String): List<MemoryItem> {
        ensure()
        val hits = search(query, k = 20).filter { it.second >= 1.0 }.map { it.first }
        val ids = hits.map { it.id }.toSet()
        if (ids.isNotEmpty()) { _items.value = _items.value.filterNot { it.id in ids }; save() }
        return hits
    }

    @Synchronized
    fun setStyle(s: NaviStyle) { ensure(); _style.value = s; save() }

    fun search(query: String, k: Int = 5, kinds: Set<MemKind>? = null): List<Pair<MemoryItem, Double>> {
        ensure()
        val pool = _items.value.filter { kinds == null || it.kind in kinds }
        return TextIndex.rank(query, pool.map { it.searchText }, k).map { pool[it.first] to it.second }
    }

    /** Risposta insegnata o corretta che corrisponde alla frase (match quasi esatto). */
    fun directAnswer(user: String): MemoryItem? {
        ensure()
        val n = TextIndex.normalize(user).trim(' ', '?', '!', '.')
        val cands = _items.value.filter { it.kind == MemKind.RULE || it.kind == MemKind.CORRECTION || it.kind == MemKind.EXAMPLE }
        return cands.firstOrNull { TextIndex.normalize(it.key).trim(' ', '?', '!', '.') == n }
            ?: cands.map { it to TextIndex.jaccard(it.key, user) }.filter { it.second >= 0.75 }
                .sortedWith(compareByDescending<Pair<MemoryItem, Double>> { it.second }.thenBy { it.first.kind.ordinal }).firstOrNull()?.first
    }

    @Synchronized
    fun touch(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        _items.value = _items.value.map { if (it.id in ids) it.copy(lastUsed = now, uses = it.uses + 1) else it }; save()
    }

    fun count() = run { ensure(); _items.value.size }
}
