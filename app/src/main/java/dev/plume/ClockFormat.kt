package dev.plume

internal enum class ClockFormat(val key: String, val presets: List<String>, private val fields: String) {
    DATE("date_format", listOf("yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd EEEE"), "yMLdE"),
    TIME("time_format", listOf("h:mm a", "HH:mm"), "ahHms");

    fun accepts(pattern: String): Boolean {
        if (pattern.length !in 1..MAX_LENGTH || pattern.any { it.isISOControl() }) return false
        val tokens = pattern.replace(quotedText, "")
        return tokens.any { it in fields } && tokens.all { it in fields || (!it.isLetter() && it != '\'') }
    }

    companion object {
        const val MAX_LENGTH = 40
        private val quotedText = Regex("'[^']*'")
    }
}
