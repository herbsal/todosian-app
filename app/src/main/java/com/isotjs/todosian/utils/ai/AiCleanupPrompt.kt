package com.isotjs.todosian.utils.ai

/**
 * The instruction for Gemini Nano: clean up one dictated task. Placeholders `⟦n⟧` stand for parts
 * that must come back unchanged (see [AiTextGuard]). The examples are what the model copies most.
 */
object AiCleanupPrompt {

    private val examples = listOf(
        "ähm ja also Werkzeugkasten, den Werkzeugkasten aufräumen" to "Werkzeugkasten aufräumen",
        "also ich muss halt äh den Kinderarzt anrufen ⟦1⟧ ⟦2⟧" to "Kinderarzt anrufen ⟦1⟧ ⟦2⟧",
        "ja Termin, nein, Zahnarzt Termin für Lena vereinbaren" to "Zahnarzttermin für Lena vereinbaren",
        "morgen 17 Uhr ähm Müll rausbringen ⟦1⟧" to "Morgen 17 Uhr Müll rausbringen ⟦1⟧",
        "Dienstag, nein Mittwoch quasi Reifen wechseln lassen" to "Mittwoch Reifen wechseln lassen",
        "Geschenk für ⟦1⟧ besorgen" to "Geschenk für ⟦1⟧ besorgen",
        "uh so basically call the uh plumber ⟦1⟧" to "Call the plumber ⟦1⟧",
    )

    fun build(maskedText: String): String = buildString {
        appendLine("Du räumst eine diktierte Aufgabe für eine Aufgabenliste auf.")
        appendLine("Regeln:")
        appendLine("- Entferne Füllwörter (ähm, äh, also, ja, halt, quasi, sozusagen, irgendwie, eben, mal).")
        appendLine("- Fasse Wiederholungen zusammen; bei Selbstkorrekturen gilt die letzte Angabe.")
        appendLine("- Formuliere kurz als Aufgabe, ohne Satzzeichen am Ende, erster Buchstabe groß.")
        appendLine("- Erfinde nichts, ergänze nichts, übersetze nicht. Antworte in der Sprache des Textes.")
        appendLine("- Datum, Uhrzeit, Namen und Zahlen bleiben genau so stehen.")
        appendLine("- Jeder Platzhalter wie ⟦1⟧ kommt genau einmal und unverändert in die Antwort.")
        appendLine("- Antworte nur mit der Aufgabe in einer Zeile, ohne Erklärung und ohne Anführungszeichen.")
        appendLine()
        examples.forEach { (input, output) ->
            appendLine("Text: $input")
            appendLine("Aufgabe: $output")
            appendLine()
        }
        appendLine("Text: $maskedText")
        append("Aufgabe:")
    }

    /** Strips a leading "Aufgabe:" the model may repeat. */
    fun answerOf(raw: String): String = raw.trim().removePrefix("Aufgabe:").trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
}
