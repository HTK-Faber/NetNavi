package com.tuo.navi.actions

import com.tuo.navi.data.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Note(val id: Long, val text: String, val created: Long)

/** Note salvate in locale (navi/notes.json), incluse nel trasferimento. */
object NotesStore {
    private val file: File get() = File(ProfileStore.baseDir, "notes.json")
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> get() = _notes

    fun load() {
        _notes.value = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Note(o.getLong("id"), o.getString("text"), o.getLong("created")) } }
        }.getOrDefault(emptyList())
    }

    fun add(text: String): Note {
        val n = Note(System.currentTimeMillis(), text.trim(), System.currentTimeMillis())
        _notes.value = listOf(n) + _notes.value; save(); return n
    }

    fun delete(id: Long) { _notes.value = _notes.value.filterNot { it.id == id }; save() }

    private fun save() {
        val a = JSONArray(); _notes.value.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("created", it.created)) }
        file.writeText(a.toString())
    }
}
