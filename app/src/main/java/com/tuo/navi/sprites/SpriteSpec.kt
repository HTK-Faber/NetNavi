package com.tuo.navi.sprites

data class SpriteRow(
    val id: Int, val key: String, val title: String, val frames: Int, val fps: Int,
    val loop: Boolean, val master: Boolean, val notes: String, val prompt: String,
    /** Altezza della posa rispetto al personaggio in piedi (1 = in piedi): serve a non ingrandire le pose basse. */
    val heightRatio: Float = 1f
)

/** Regole condivise: tabella, celle e prompt. Mostrate nella Guida dell'app. */
object SpriteSpec {
    const val CELL = 128
    const val CHAR_H = 96
    const val BASELINE = 112
    const val GREEN = "#00FF00"

    val rows = listOf(
        SpriteRow(1, "idle", "Idle", 6, 6, true, true, "Respiro, mantello che ondeggia, ciclo",
            "standing guard, visible breathing: chest and shoulders rise and fall by 2-3 pixels, loose clothing or hair sways noticeably, seamless loop"),
        SpriteRow(2, "walk", "Camminata", 8, 10, true, true, "Ciclo",
            "clear walking cycle with distinct leg positions: frame 1 right leg forward contact, frame 2 down (knees bent, body 2 px lower), frame 3 passing (legs crossing), frame 4 up (body 2 px higher), frames 5-8 same sequence with the left leg forward; exaggerated stride, arms swing opposite to the legs, loose clothing follows the motion"),
        SpriteRow(3, "run", "Corsa", 8, 14, true, true, "Ciclo, corpo inclinato",
            "fast running cycle, body leaning forward, long strides, hair and clothing streaming behind, 8 frames"),
        SpriteRow(4, "jump", "Salto", 6, 10, false, true, "Preparazione, salita, apice, discesa, atterraggio, ripresa",
            "crouch, takeoff, rising, apex, falling, landing with bent knees"),
        SpriteRow(5, "slide", "Scivolata / scatto", 4, 12, false, true, "Pose basse, in avanti",
            "low slide dash along the ground, one leg extended, clothing trailing", heightRatio = 0.75f),
        SpriteRow(6, "fly", "Volo / fluttuazione", 6, 8, true, true, "Ciclo, mantello verso l'alto",
            "floating in the air, legs relaxed, clothing and hair lifted upward, subtle up-and-down bobbing, loop"),
        SpriteRow(7, "attack", "Attacco base", 6, 14, false, true, "Colpo rapido",
            "quick basic attack with the character's weapon or bare hands, wind-up, strike, follow-through, recovery"),
        SpriteRow(8, "sword", "Attacco pesante", 8, 14, false, false, "Carica, colpo forte, ritorno",
            "heavy overhead attack with the character's weapon, wind-up, big downward slash, follow-through, return to stance"),
        SpriteRow(9, "special", "Attacco speciale", 8, 12, false, false, "Carica, scatto, colpo finale",
            "special attack, charge pose with energy gathering in the hands or weapon, dash forward, final thrust"),
        SpriteRow(10, "guard", "Difesa", 4, 8, true, false, "Guardia alzata, ciclo",
            "blocking pose with arms or weapon raised in front, subtle shake, loop"),
        SpriteRow(11, "dodge", "Schivata", 4, 12, false, false, "Balzo indietro",
            "quick backward evasion hop, body leaning back, clothing flaring, landing"),
        SpriteRow(12, "hurt", "Danno", 4, 10, false, false, "Colpo subito, ripresa",
            "hit reaction, body recoiling backward, wince, recovering"),
        SpriteRow(13, "knock", "Atterramento e rialzata", 6, 8, false, false, "Caduta, a terra, in piedi",
            "knocked down, lying on the ground, then getting back up to standing"),
        SpriteRow(14, "defeat", "Sconfitta", 8, 8, false, false, "Da in piedi a svanire",
            "defeat, collapse to the ground, lying still, then fading into particles"),
        SpriteRow(15, "talk", "Parlare", 4, 8, true, true, "Solo la bocca e una mano si muovono, ciclo (le espressioni sono nelle righe 23, 25 e 26)",
            "talking while standing still: frame 1 mouth closed, frame 2 mouth half open, frame 3 mouth wide open with one hand making a small open-palm gesture, frame 4 mouth half open; head bobs 1 px, body and feet do not move, friendly expression, seamless loop"),
        SpriteRow(16, "teleport", "Teletrasporto", 8, 12, false, false, "Si dissolve in luce, poi riappare",
            "frames 1-4 dematerializing into a vertical beam of light, frames 5-8 rematerializing"),
        SpriteRow(17, "sleep", "Sonno", 6, 3, true, false, "Seduto o accovacciato, occhi chiusi, respiro lento, ciclo",
            "sleeping while sitting curled up on the ground, eyes closed, very slow breathing (body rises 1-2 px), head slightly nodding, seamless loop", heightRatio = 0.7f),
        SpriteRow(18, "read", "Lettura dati", 6, 6, true, false, "Consulta uno schermo o un libro olografico, ciclo",
            "standing and reading data from a small floating holographic screen held in front of the face, eyes moving, one hand scrolling, seamless loop"),
        SpriteRow(19, "scan", "Verifica sistema", 8, 8, true, false, "Analizza il sistema con concentrazione, ciclo",
            "standing in a focused stance, one hand raised forward as if scanning, head turning slowly left and right, determined look, seamless loop"),
        SpriteRow(20, "repair", "Riparazione", 6, 10, true, false, "Ripara qualcosa a terra, ciclo",
            "kneeling and repairing something on the ground with quick hand movements, small tool in hand, seamless loop", heightRatio = 0.8f),
        SpriteRow(21, "alert", "Allarme", 4, 10, false, false, "Si mette in guardia: pericolo trovato",
            "sudden alarmed reaction: startled jump back, then a ready defensive stance facing right, serious expression"),
        SpriteRow(22, "cheer", "Festa / incoraggiamento", 6, 10, false, false, "Pugno alzato, salto di gioia",
            "cheerful encouragement: fist pump, small jump of joy with both arms raised, landing with a smile"),
        SpriteRow(23, "think", "Riflette", 6, 4, true, false, "Mano al mento, sguardo pensieroso, ciclo",
            "thinking: hand on chin, looking slightly upward, small head tilt, occasional blink, seamless loop"),
        SpriteRow(24, "pet", "Felice per una carezza", 6, 8, false, false, "Chiude gli occhi contento, piccolo saltello",
            "happy reaction to being petted: eyes closed with a big smile, shoulders raised, small happy bounce"),
        SpriteRow(25, "surprised", "Sorpreso", 4, 8, false, false, "Sobbalza, occhi spalancati, torna normale",
            "surprised reaction: frame 1 normal stance, frame 2 small startled hop with eyes and mouth wide open and hands raised, frame 3 still surprised back on the ground, frame 4 relaxing back to normal stance"),
        SpriteRow(26, "wave", "Saluta", 6, 8, false, false, "Alza la mano, saluta due volte, la abbassa",
            "waving hello: frame 1 normal stance, frame 2 raising one hand, frames 3-5 hand waving left and right above the shoulder with a smile, frame 6 lowering the hand")
    )

    val masterIds: Set<Int> = rows.filter { it.master }.map { it.id }.toSet()
    fun row(id: Int) = rows.first { it.id == id }
    fun byKey(k: String) = rows.firstOrNull { it.key == k }

    fun prompt(r: SpriteRow): String = """
Use the attached character as the exact reference: keep the same face, hair, outfit, accessories, colors and proportions. Create a 2D pixel art animation sprite strip for a game.

Animation: ${r.title}, exactly ${r.frames} frames, arranged in ONE horizontal row, left to right, in time order.
Frame content: ${r.prompt}

Layout rules (very important):
- The whole image is ONE single continuous flat background of pure green #00FF00, edge to edge.
- NO frame borders, NO boxes, NO panels, NO outlines around cells, NO grid lines, NO dividers, NO separators, NO black lines between frames. The frames are NOT visually separated in any way: only the green background exists between the characters.
- ONLY ONE ROW of frames. Do not add a second row.
- Do not draw a comic strip or a storyboard. This is a raw game sprite sheet.
- Each frame occupies an invisible square area of exactly 128x128 pixels, evenly spaced, no gaps, no margins. Total image size: ${r.frames * CELL} by $CELL pixels.
- No labels, no text, no numbers, no watermark.

Character rules:
- The character faces RIGHT in every frame, side view (three-quarter allowed).
- The character is about 96 px tall and is horizontally centered in each 128 px area.
- Feet (or lowest point) always touch the same invisible baseline at 112 px from the top. No ground line, no floor shadow.
- Same proportions, outfit, colors and palette in every frame. Do not redesign the character.
- Clean pixel art: hard 1-pixel dark outline only on the character, no anti-aliasing, no blur, no motion blur, limited palette (about 24 colors), same pixel size everywhere.
- Hair, clothing, weapons and accessories stay fully inside each 128 px area, nothing cropped.
- Clearly visible differences between consecutive frames, so the motion reads well when played. If the animation is a cycle, the last frame flows back into the first.
- No effects, projectiles, aura, or extra characters.
""".trim()

    const val FIX_PROMPT = "Regenerate the same strip without any borders or lines between frames, in ONE single row: only pure green #00FF00 background between the characters."

    val rules = listOf(
        "Genera una riga (animazione) per volta, allegando sempre l'immagine del personaggio come riferimento.",
        "Sfondo verde puro #00FF00, uniforme, senza riquadri, linee o testo.",
        "Celle invisibili da 128×128 px; personaggio alto circa 96 px; piedi sulla linea a 112 px dall'alto.",
        "Personaggio sempre rivolto a destra: la direzione sinistra si ottiene specchiando.",
        "Master: righe 1-7 e 15. Si caricano tutte, poi si sigillano e non sono più modificabili.",
        "Righe aggiuntive (8-14, 16-26): disponibili solo dopo il sigillo della master; si possono sostituire.",
        "Ogni animazione non caricata viene generata dall'app partendo dalla tua immagine (Idle caricata o avatar creato), così il Navi ha sempre tutte le animazioni.",
        "Ogni riga contiene una sola azione. La 15 (Parlare) contiene solo il parlato; le espressioni sono righe a parte: Riflette (23), Sorpreso (25), Saluta (26).",
        "Una vecchia riga 15 da 8 frame (parla, sorpreso, pensa, saluta) viene divisa da sola: parlato nella 15, sorpreso nella 25, pensa nella 23 se è vuota.",
        "Il personaggio importato viene ridimensionato in base alla sua altezza in piedi, uguale in tutte le righe: le pose larghe (mantello, armi) non lo rimpiccioliscono più.",
        "Se l'immagine ha più righe, l'app ti chiede quale usare. Se compaiono riquadri neri, l'app prova a toglierli.",
        "Gli effetti (scie, onde, aure) andranno in un foglio separato con celle da 256×256 (fase battaglia)."
    )
}
