package com.mediaviewer.domain

/**
 * CSV and TSV tiles (todo §1a): the first lines as an aligned table for a monospace preview.
 * The separator is sniffed (tab, `;`, `,`, `|`: the one that splits the first lines most evenly);
 * quoted fields keep their separators and `""` quotes. Cells are cut at [maxCell] characters.
 * Text that doesn't split into at least two columns comes back as it was. Pure Kotlin.
 */
object CsvPreview {
    private val SEPARATORS = charArrayOf('\t', ';', ',', '|')

    fun align(text: String, maxLines: Int = 40, maxCell: Int = 18, gap: String = "  "): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().filter { it.isNotBlank() }.take(maxLines)
        if (lines.size < 2) return text
        val sep = sniff(lines) ?: return text
        val rows = lines.map { split(it, sep).map { c -> cut(c.trim(), maxCell) } }
        val cols = rows.maxOf { it.size }
        if (cols < 2) return text
        val widths = IntArray(cols) { c -> rows.maxOf { r -> r.getOrNull(c)?.length ?: 0 } }
        return rows.joinToString("\n") { r ->
            r.mapIndexed { c, cell -> if (c == r.lastIndex) cell else cell.padEnd(widths[c]) }.joinToString(gap).trimEnd()
        }
    }

    /** The separator that gives the same number (≥ 2) of fields on most of the first lines. */
    internal fun sniff(lines: List<String>): Char? {
        val sample = lines.take(10)
        var best: Char? = null
        var bestScore = 0
        for (sep in SEPARATORS) {
            val counts = sample.map { split(it, sep).size }
            val common = counts.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: continue
            if (common.key < 2) continue
            // Lines that agree, weighted a little by the column count, so `a,b;c,d` picks the richer split only when it is as regular.
            val score = common.value * 10 + common.key
            if (score > bestScore) {
                bestScore = score
                best = sep
            }
        }
        return best
    }

    internal fun split(line: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                ch == '"' && (quoted || cell.isBlank()) -> quoted = !quoted
                ch == sep && !quoted -> {
                    out.add(cell.toString())
                    cell.clear()
                }
                else -> cell.append(ch)
            }
            i++
        }
        out.add(cell.toString())
        return out
    }

    private fun cut(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}
