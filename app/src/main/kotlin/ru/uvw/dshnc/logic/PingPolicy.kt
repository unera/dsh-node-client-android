package ru.uvw.dshnc.logic

/**
 * Расписание пинга: интервал растёт, пока сервер молчит, и сбрасывается, когда
 * была работа.
 *
 * Арифметика вынесена из службы, потому что её надо проверять: ошибка здесь
 * означала «узел выглядит мёртвым» или «пингуем чаще, чем нужно».
 */
object PingPolicy {
    /** Следующий интервал в секундах и новое значение растяжения. */
    fun next (baseInterval: Int, factor: Int, stretch: Int, hadWork: Boolean): Pair<Int, Int> {
        val safeFactor = factor.coerceAtLeast(1)
        val safeBase = baseInterval.coerceAtLeast(0)
        if (safeBase == 0) return 0 to 1
        if (hadWork) return safeBase to 1
        val nextStretch = (stretch + 1).coerceAtMost(safeFactor)
        return safeBase * nextStretch to nextStretch
    }

    /** Пора ли считать канал мёртвым: нет ответа дольше двух интервалов. */
    fun isSilent (silenceMs: Long, intervalSeconds: Int, stretch: Int): Boolean {
        val interval = intervalSeconds.coerceAtLeast(1) * stretch.coerceAtLeast(1) * 1000L
        return silenceMs > interval * 2
    }
}
