package ru.tpu.ml.providers

import kotlinx.coroutines.flow.Flow
import ru.tpu.ml.api.ChatChunk
import ru.tpu.ml.api.ChatMessage
import ru.tpu.ml.api.ChatRequest
import ru.tpu.ml.api.ChatResponse
import ru.tpu.ml.api.Choice
import ru.tpu.ml.api.FunctionCall
import ru.tpu.ml.api.ToolCall
import ru.tpu.ml.api.Usage

// Модель-имитация: отвечает по простым правилам, без нейронки.
// Нужна, чтобы бэк мог подключаться уже сейчас, и для тестов.
object MockProvider : LlmProvider {
    override val name = "mock"

    override suspend fun complete(request: ChatRequest): ChatResponse {
        // А. Факты из запроса
        val last = request.messages.last()
        val userText = request.messages.lastOrNull { it.role == "user" }?.content ?: ""
        val hasRaspTool = request.tools?.any { it.function.name == "rasp_day" } == true

        // Б. Выбор ответа — срабатывает первое подходящее правило.
        // Правило про tool стоит первым: во втором заходе бэка текст пользователя
        // всё ещё «расписание», и без этого заглушка просила бы инструмент бесконечно.
        val message = when {
            last.role == "tool" -> ChatMessage(
                role = "assistant",
                content = "По данным инструмента: ${(last.content ?: "").take(200)}",
            )

            userText.contains("распис", ignoreCase = true) && hasRaspTool -> ChatMessage(
                role = "assistant",
                tool_calls = listOf(
                    ToolCall(
                        id = "call_1",
                        function = FunctionCall(
                            name = "rasp_day",
                            arguments = """{"date":"2026-09-27"}""",
                        ),
                    ),
                ),
            )

            else -> ChatMessage(
                role = "assistant",
                content = "Заглушка: вы написали «$userText»",
            )
        }

        // В. Чем закончился ответ: просьбой вызвать инструмент или текстом
        val finishReason = if (message.tool_calls != null) "tool_calls" else "stop"

        // Г. Собрать ответ в формате OpenAI
        return ChatResponse(
            id = "mock-${System.nanoTime()}",
            created = System.currentTimeMillis() / 1000, // секунды, не миллисекунды
            model = request.model,
            choices = listOf(Choice(message = message, finish_reason = finishReason)),
            usage = Usage(0, 0, 0),
        )
    }

    override fun stream(request: ChatRequest): Flow<ChatChunk> = TODO("шаг 7")
}
