package ru.uvw.dshnc.logic

/**
 * Разбор ссылки подключения.
 *
 * Вынесено из активности, чтобы проверялось тестами: именно здесь легко
 * ошибиться (кодирование, лишние пробелы, чужой адрес), а ошибка стоит
 * «не подключается, и непонятно почему».
 */
object LinkParser {
    /** Что вытащили из ссылки: адрес сервера и код. */
    data class Parsed(val base: String, val code: String)

    /**
     * @param raw строка из QR или из поля ввода
     * @return разобранное или null, если это не ссылка подключения
     */
    fun parse (raw: String?): Parsed? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = Regex("[?&]v=([^&]+)").find(text) ?: return null
        val base = text.substringBefore("/pair").trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        val code = try {
            java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
        } catch (error: Throwable) {
            match.groupValues[1]
        }
        if (code.isBlank()) return null
        return Parsed(base, code)
    }
}
