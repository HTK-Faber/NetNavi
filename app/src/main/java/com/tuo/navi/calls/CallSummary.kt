package com.tuo.navi.calls

/**
 * Riassunto delle informazioni importanti di una chiamata a partire dal testo riconosciuto.
 * Il testo resta solo in memoria per pochi secondi: si salva soltanto il riassunto (tema, azienda, numero da richiamare, parole chiave).
 */
object CallSummary {
    private val themes = linkedMapOf(
        "luce e gas" to listOf("luce", "gas", "bolletta", "energia", "fornitura", "contatore", "kilowatt", "pod", "pdr"),
        "telefonia e internet" to listOf("fibra", "internet", "telefonia", "offerta mobile", "giga", "linea fissa", "modem", "operatore"),
        "assicurazioni" to listOf("assicurazione", "polizza", "rc auto", "sinistro"),
        "banca o finanziamenti" to listOf("prestito", "finanziamento", "mutuo", "carta di credito", "conto corrente", "investimento", "trading", "criptovalut"),
        "sondaggio" to listOf("sondaggio", "questionario", "ricerca di mercato", "intervista"),
        "consegna" to listOf("corriere", "pacco", "spedizione", "consegna", "ritiro"),
        "recupero crediti" to listOf("recupero crediti", "insoluto", "debito", "sollecito", "pagamento in sospeso"),
        "vendita" to listOf("offerta", "promozione", "sconto", "gratuito", "omaggio", "vantaggio", "risparmio", "proposta"))
    private val scam = listOf("codice", "pin", "password", "otp", "bonifico", "conto bloccato", "carta bloccata", "iban", "verifica dei dati", "dati della carta")
    private val brands = listOf("Enel", "Eni", "Plenitude", "Edison", "A2A", "Hera", "Iren", "Sorgenia", "Acea", "Engie", "Illumia", "Iberdrola", "Octopus",
        "TIM", "Vodafone", "WindTre", "Wind", "Iliad", "Fastweb", "Sky", "Very", "Ho Mobile", "Kena", "PosteMobile", "Poste",
        "Intesa", "UniCredit", "BPER", "Fineco", "Findomestic", "Agos", "Compass", "Generali", "Allianz", "UnipolSai", "Unipol", "Amazon", "BRT", "GLS", "DHL", "SDA")
    private val stop = setOf("buongiorno", "buonasera", "salve", "sono", "della", "delle", "degli", "dalla", "nella", "questo", "questa", "quello", "perché", "perche",
        "anche", "molto", "come", "dove", "quando", "allora", "quindi", "però", "pero", "volevo", "vorrei", "sentire", "parlare", "chiamo", "chiamata",
        "signor", "signora", "grazie", "arrivederci", "possiamo", "potrebbe", "essere", "avere", "fare", "dire", "abbiamo", "siamo", "stiamo", "nostra", "nostro", "vostra", "vostro")

    fun summarize(text: String, seconds: Int): String {
        val t = text.lowercase()
        if (t.isBlank()) return if (seconds >= 3) "Ha parlato per circa $seconds secondi, ma non sono riuscito a capire le parole." else "Non ha detto niente."
        val parts = mutableListOf<String>()
        val theme = themes.entries.maxByOrNull { (_, k) -> k.count { t.contains(it) } }?.takeIf { (_, k) -> k.any { t.contains(it) } }?.key
        theme?.let { parts += "Tema: $it" }
        val b = brands.filter { Regex("\\b${Regex.escape(it.lowercase())}\\b").containsMatchIn(t) }.distinct().take(2)
        if (b.isNotEmpty()) parts += "Dice di chiamare da: ${b.joinToString(", ")}"
        Regex("(?:\\+39\\s?)?\\b\\d[\\d .]{5,14}\\d\\b").findAll(text).map { it.value.filter(Char::isDigit) }.firstOrNull { it.length in 6..12 }?.let { parts += "Numero citato: $it" }
        if (scam.any { t.contains(it) }) parts += "Attenzione: chiede codici o dati bancari, potrebbe essere una truffa. Non richiamare"
        val kw = Regex("[\\p{L}]{5,}").findAll(t).map { it.value }.filter { it !in stop }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.filterNot { w -> b.any { it.lowercase() == w } }.take(5)
        if (kw.isNotEmpty()) parts += "Parole chiave: ${kw.joinToString(", ")}"
        return parts.joinToString(". ") + "."
    }
}
