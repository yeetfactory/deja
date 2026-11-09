package top.yeetfactory.deja.core

/** Plain CSV. Fields that a spreadsheet could read as a formula are neutralised. */
object Csv {
    fun row(fields: List<Any?>): String =
        fields.joinToString(",") {
            if (it is Number) it.toString() else field(it?.toString().orEmpty())
        }

    private fun field(raw: String): String {
        val safe = if (raw.firstOrNull() in FORMULA_STARTS) "'$raw" else raw
        val quote = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (quote) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private val FORMULA_STARTS = setOf('=', '+', '-', '@')
}
