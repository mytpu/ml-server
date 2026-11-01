package ru.tpu.ml.pii

/**
 * Сессия маскирования личных данных на время одного запроса.
 * Хранит соответствие метка -> значение, наружу словарь не отдаёт.
 */
class MaskSession {
    private val valueToTag = mutableMapOf<String, String>()
    private val tagToValue = mutableMapOf<String, String>()
    private val counters = mutableMapOf<String, Int>()

    /** Возвращает существующую метку для значения или создает новую вида ⟦TYPE_N⟧ */
    fun getOrCreateTag(type: String, value: String): String {
        return valueToTag.getOrPut(value) {
            val count = counters.compute(type) { _, current -> (current ?: 0) + 1 }!!
            val tag = "⟦${type}_$count⟧"
            tagToValue[tag] = value
            tag
        }
    }

    /** Возвращает исходные значения на место меток; неизвестные метки не трогает */
    fun unmask(text: String): String {
        if (tagToValue.isEmpty() || !text.contains('⟦')) return text
        var result = text
        for ((tag, value) in tagToValue) {
            result = result.replace(tag, value)
        }
        return result
    }

    /** Число замененных значений по типам (для журнала без раскрытия данных) */
    fun replacementCounts(): Map<String, Int> = counters.toMap()

    fun hasReplacements(): Boolean = tagToValue.isNotEmpty()
}

/**
 * Восстановление меток в потоке SSE с обработкой разрезанных кусков (⟦...⟧).
 */
class StreamUnmasker(private val session: MaskSession) {
    private var buffer = ""

    /**
     * Принимает очередной кусок текста, держит незакрытый хвост с ⟦
     * и отдает готовый размаскированный текст.
     */
    fun feed(text: String?): String? {
        if (text == null) return null
        buffer += text
        val lastOpenIdx = buffer.lastIndexOf('⟦')
        if (lastOpenIdx == -1) {
            val toEmit = session.unmask(buffer)
            buffer = ""
            return toEmit
        }
        val closeIdx = buffer.indexOf('⟧', lastOpenIdx)
        return if (closeIdx == -1) {
            // Незакрытый ⟦ — держим хвост куска в буфере
            val toEmit = session.unmask(buffer.substring(0, lastOpenIdx))
            buffer = buffer.substring(lastOpenIdx)
            toEmit
        } else {
            // Все ⟦ закрыты
            val toEmit = session.unmask(buffer)
            buffer = ""
            toEmit
        }
    }

    /** Выпускает остаток буфера в конце потока */
    fun finish(): String {
        if (buffer.isEmpty()) return ""
        val remaining = session.unmask(buffer)
        buffer = ""
        return remaining
    }
}
