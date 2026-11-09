package com.tuo.navi.media

/** Un file multimediale del telefono (solo metadati, letti da MediaStore). */
data class MediaInfo(
    val id: Long,
    val name: String,
    val size: Long,
    val taken: Long,          // data dello scatto (ms); se manca, data di aggiunta
    val bucket: String,       // cartella (es. Camera, WhatsApp Images)
    val isVideo: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0
)

enum class MediaMode(val label: String) { LARGE("Grandi"), DUPLICATES("Duplicati"), BURSTS("Scatti in serie"), ALL("Tutti") }
enum class MediaType(val label: String) { ALL("Foto e video"), PHOTO("Solo foto"), VIDEO("Solo video") }
enum class MediaPeriod(val label: String, val days: Int, val olderThan: Boolean = false) {
    ANY("Sempre", 0), MONTH("Ultimi 30 giorni", 30), HALF("Ultimi 6 mesi", 182), YEAR("Ultimo anno", 365), OLD("Più vecchi di 1 anno", 365, true)
}

data class MediaFilter(
    val mode: MediaMode = MediaMode.LARGE,
    val type: MediaType = MediaType.ALL,
    val period: MediaPeriod = MediaPeriod.ANY,
    val bucket: String? = null,
    val photoMinMb: Int = 5,
    val videoMinMb: Int = 100,
    val burstSeconds: Int = 5
)

/** Un gruppo di risultati: per "Grandi" un file per gruppo; per duplicati e serie, i file simili insieme. */
data class MediaGroup(val items: List<MediaInfo>, val suggestedKeep: Long?) {
    val total: Long get() = items.sumOf { it.size }
    /** Spazio recuperabile tenendo solo il file suggerito. */
    val reclaim: Long get() = if (suggestedKeep == null) total else items.filter { it.id != suggestedKeep }.sumOf { it.size }
}

/** Logica pura (testabile): filtri, file grandi, duplicati e scatti a pochi secondi di distanza. */
object MediaLogic {
    const val MB = 1024L * 1024L

    fun base(list: List<MediaInfo>, f: MediaFilter, now: Long = System.currentTimeMillis()): List<MediaInfo> = list.filter { m ->
        (f.type == MediaType.ALL || (f.type == MediaType.VIDEO) == m.isVideo) &&
            (f.bucket == null || m.bucket == f.bucket) &&
            when {
                f.period == MediaPeriod.ANY -> true
                f.period.olderThan -> now - m.taken > f.period.days * 86_400_000L
                else -> now - m.taken <= f.period.days * 86_400_000L
            }
    }

    fun isLarge(m: MediaInfo, f: MediaFilter) = m.size >= (if (m.isVideo) f.videoMinMb else f.photoMinMb) * MB

    fun large(list: List<MediaInfo>, f: MediaFilter): List<MediaGroup> =
        list.filter { isLarge(it, f) }.sortedByDescending { it.size }.map { MediaGroup(listOf(it), null) }

    /** Foto della stessa cartella scattate a meno di [seconds] secondi l'una dall'altra. Si suggerisce di tenere la più grande (di solito la più nitida). */
    fun bursts(list: List<MediaInfo>, seconds: Int): List<MediaGroup> {
        val out = mutableListOf<MediaGroup>()
        list.filter { !it.isVideo && it.taken > 0 }.groupBy { it.bucket }.values.forEach { inBucket ->
            var cur = mutableListOf<MediaInfo>()
            for (m in inBucket.sortedBy { it.taken }) {
                if (cur.isNotEmpty() && m.taken - cur.last().taken > seconds * 1000L) {
                    if (cur.size >= 2) out += MediaGroup(cur, cur.maxBy { it.size }.id)
                    cur = mutableListOf()
                }
                cur += m
            }
            if (cur.size >= 2) out += MediaGroup(cur, cur.maxBy { it.size }.id)
        }
        return out.sortedByDescending { it.reclaim }
    }

    /** Primo passo dei duplicati: stessi byte => stessa dimensione. Solo questi gruppi vengono poi confrontati con l'impronta. */
    fun sameSize(list: List<MediaInfo>): List<List<MediaInfo>> =
        list.filter { it.size > 0 }.groupBy { it.size to it.isVideo }.values.filter { it.size >= 2 }

    /** Secondo passo: raggruppa per impronta del contenuto. Si suggerisce di tenere il più vecchio (l'originale). */
    fun duplicates(list: List<MediaInfo>, fingerprint: (MediaInfo) -> String?): List<MediaGroup> =
        sameSize(list).flatMap { g ->
            g.mapNotNull { m -> fingerprint(m)?.let { it to m } }.groupBy({ it.first }, { it.second }).values
                .filter { it.size >= 2 }.map { d -> MediaGroup(d.sortedBy { it.taken }, d.minBy { it.taken }.id) }
        }.sortedByDescending { it.reclaim }

    fun formatSize(b: Long): String = when {
        b >= 1024 * MB -> String.format(java.util.Locale.ITALIAN, "%.1f GB", b / (1024.0 * MB))
        b >= MB -> String.format(java.util.Locale.ITALIAN, "%.1f MB", b / MB.toDouble())
        else -> "${b / 1024} KB"
    }
}
