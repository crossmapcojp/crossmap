package jp.co.crossmap.crawl

internal object Rfc4180Csv {
    fun parse(value: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character == '"' && quoted && value.getOrNull(index + 1) == '"' -> {
                    cell.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    row += cell.toString()
                    cell.clear()
                }
                (character == '\n' || character == '\r') && !quoted -> {
                    if (character == '\r' && value.getOrNull(index + 1) == '\n') index++
                    row += cell.toString()
                    cell.clear()
                    rows += row
                    row = mutableListOf()
                }
                else -> cell.append(character)
            }
            index++
        }
        require(!quoted) { "Unterminated quoted CSV field" }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows
    }
}
