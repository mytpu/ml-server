package ru.tpu.ml.pii

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.tpu.ml.api.ChatChunk
import ru.tpu.ml.api.ChatRequest
import ru.tpu.ml.api.ChatResponse
import ru.tpu.ml.api.ChunkChoice
import ru.tpu.ml.api.Delta

/**
 * Поиск и замена личных данных (ПДн) метками вида ⟦PHONE_1⟧, ⟦EMAIL_1⟧, ⟦PASSPORT_1⟧, ⟦NAME_1⟧.
 */
object PiiMasker {
    // Телефоны РФ (+7 999 123-45-67, 8(999)1234567, 89991234567)
    private val PHONE_REGEX = Regex(
        """(?<!\d)(?:\+7|8)[\s\-]?(?:\(\d{3}\)|\d{3})[\s\-]?\d{3}[\s\-]?\d{2}[\s\-]?\d{2}(?!\d)"""
    )

    // Электронная почта
    private val EMAIL_REGEX = Regex(
        """(?<![a-zA-Z0-9._%+-])[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(?![a-zA-Z0-9.-])"""
    )

    // Паспорт РФ (4510 123456, 45 10 123456)
    private val PASSPORT_REGEX = Regex(
        """(?<!\d)\d{2}[\s\-]?\d{2}\s+\d{6}(?!\d)"""
    )

    // Фамилия и ФИО / инициалы
    private const val SURNAME =
        """[А-ЯЁ][а-яё]*(?:(?:ов|ев|ёв|ин|ын)(?:[аеу]|ым|ой|ом)?|(?:ск|цк)(?:ий|ого|ому|им|ом|ая|ой|ую))"""
    private const val PATRONYMIC =
        """[А-ЯЁ][а-яё]*(?:(?:вич|ич)(?:[ауе]|ем)?|(?:вн|ичн)(?:[аыеу]|ой)?)"""
    private const val FIRST_NAME = """[А-ЯЁ][а-яё]+"""
    private const val INITIALS = """[А-ЯЁ]\.(?:\s*[А-ЯЁ]\.)?"""

    private val NAME_REGEX = Regex(
        """(?<![а-яёА-ЯЁ])(?:$SURNAME\s+(?:$INITIALS|$FIRST_NAME\s+$PATRONYMIC)|$FIRST_NAME\s+$PATRONYMIC\s+$SURNAME)(?![а-яёА-ЯЁ])"""
    )

    private data class MatchSpan(val range: IntRange, val type: String, val value: String)

    /** Находит ПДн в тексте и заменяет их на метки сессии */
    fun maskText(text: String, session: MaskSession): String {
        if (text.isEmpty()) return text
        val matches = mutableListOf<MatchSpan>()
        EMAIL_REGEX.findAll(text).forEach { matches.add(MatchSpan(it.range, "EMAIL", it.value)) }
        PHONE_REGEX.findAll(text).forEach { matches.add(MatchSpan(it.range, "PHONE", it.value)) }
        PASSPORT_REGEX.findAll(text).forEach { matches.add(MatchSpan(it.range, "PASSPORT", it.value)) }
        NAME_REGEX.findAll(text).forEach { matches.add(MatchSpan(it.range, "NAME", it.value)) }

        if (matches.isEmpty()) return text
        matches.sortWith(compareBy({ it.range.first }, { -(it.range.last - it.range.first) }))

        val sb = StringBuilder()
        var cursor = 0
        for (match in matches) {
            if (match.range.first < cursor) continue
            sb.append(text, cursor, match.range.first)
            sb.append(session.getOrCreateTag(match.type, match.value))
            cursor = match.range.last + 1
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }

    /** Маскирует сообщения user, tool и аргументы tool_calls в истории запроса */
    fun maskRequest(request: ChatRequest, session: MaskSession): ChatRequest {
        val maskedMessages = request.messages.map { msg ->
            val maskedContent = if (msg.role == "user" || msg.role == "tool") {
                msg.content?.let { maskText(it, session) }
            } else msg.content
            val maskedToolCalls = msg.tool_calls?.map { tc ->
                tc.copy(function = tc.function.copy(arguments = maskText(tc.function.arguments, session)))
            }
            msg.copy(content = maskedContent, tool_calls = maskedToolCalls)
        }
        return request.copy(messages = maskedMessages)
    }

    /** Размаскирует ответ модели: content, reasoning_content и аргументы tool_calls */
    fun unmaskResponse(response: ChatResponse, session: MaskSession): ChatResponse {
        val unmaskedChoices = response.choices.map { choice ->
            val msg = choice.message
            val unmaskedContent = msg.content?.let { session.unmask(it) }
            val unmaskedReasoning = msg.reasoning_content?.let { session.unmask(it) }
            val unmaskedToolCalls = msg.tool_calls?.map { tc ->
                tc.copy(function = tc.function.copy(arguments = session.unmask(tc.function.arguments)))
            }
            choice.copy(
                message = msg.copy(
                    content = unmaskedContent,
                    reasoning_content = unmaskedReasoning,
                    tool_calls = unmaskedToolCalls
                )
            )
        }
        return response.copy(choices = unmaskedChoices)
    }

    /** Размаскирует поток кусков SSE с обработкой разрезанных меток */
    fun unmaskStream(upstreamFlow: Flow<ChatChunk>, session: MaskSession): Flow<ChatChunk> = flow {
        val contentUnmasker = StreamUnmasker(session)
        val reasoningUnmasker = StreamUnmasker(session)
        val toolCallUnmaskers = mutableMapOf<Int, StreamUnmasker>()
        var lastChunk: ChatChunk? = null

        upstreamFlow.collect { chunk ->
            lastChunk = chunk
            val newChoices = chunk.choices.map { choice ->
                val delta = choice.delta
                var newContent = contentUnmasker.feed(delta.content)
                var newReasoning = reasoningUnmasker.feed(delta.reasoning_content)
                var newToolCalls = delta.tool_calls?.map { tc ->
                    val args = tc.function?.arguments
                    val unmaskedArgs = if (args != null) {
                        val unmasker = toolCallUnmaskers.getOrPut(tc.index) { StreamUnmasker(session) }
                        unmasker.feed(args)
                    } else null
                    tc.copy(function = tc.function?.copy(arguments = unmaskedArgs))
                }

                if (choice.finish_reason != null) {
                    val flushContent = contentUnmasker.finish()
                    if (flushContent.isNotEmpty()) newContent = (newContent ?: "") + flushContent
                    val flushReasoning = reasoningUnmasker.finish()
                    if (flushReasoning.isNotEmpty()) newReasoning = (newReasoning ?: "") + flushReasoning
                    newToolCalls = newToolCalls?.map { tc ->
                        val flushArgs = toolCallUnmaskers[tc.index]?.finish() ?: ""
                        if (flushArgs.isNotEmpty()) {
                            tc.copy(function = tc.function?.copy(arguments = (tc.function?.arguments ?: "") + flushArgs))
                        } else tc
                    }
                }

                choice.copy(
                    delta = delta.copy(
                        content = newContent,
                        reasoning_content = newReasoning,
                        tool_calls = newToolCalls
                    )
                )
            }
            emit(chunk.copy(choices = newChoices))
        }

        val remainingContent = contentUnmasker.finish()
        val remainingReasoning = reasoningUnmasker.finish()
        if (remainingContent.isNotEmpty() || remainingReasoning.isNotEmpty()) {
            val fallbackChunk = lastChunk ?: ChatChunk(
                id = "flush-${System.currentTimeMillis()}",
                created = System.currentTimeMillis() / 1000,
                model = "",
                choices = emptyList()
            )
            emit(
                fallbackChunk.copy(
                    choices = listOf(
                        ChunkChoice(
                            delta = Delta(
                                content = remainingContent.takeIf { it.isNotEmpty() },
                                reasoning_content = remainingReasoning.takeIf { it.isNotEmpty() }
                            )
                        )
                    )
                )
            )
        }
    }
}
