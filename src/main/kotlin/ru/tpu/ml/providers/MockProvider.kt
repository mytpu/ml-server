package ru.tpu.ml.providers

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.tpu.ml.api.*
import java.time.LocalDate
import java.time.ZoneId

object MockProvider : LlmProvider {
    override val name = "mock"

    override suspend fun complete(request: ChatRequest): ChatResponse {
        val last = request.messages.last()
        val userText = request.messages.lastOrNull { it.role == "user" }?.content ?: ""
        fun hasTool(name: String): Boolean {
            return request.tools?.any { it.function.name == name } == true
        }

        val rule = TOOL_RULES.firstOrNull { r ->
            hasTool(r.tool) &&
                    r.keywords.any { word -> userText.contains(word, ignoreCase = true) }
        }

        val message = when {
            last.role == "tool" -> ChatMessage(
                role = "assistant",
                content = statusOf(last.content)?.let { STATUS_REPLIES[it] }
                    ?: "По данным инструмента: ${(last.content ?: "").take(200)}",
            )

            rule != null -> ChatMessage(
                role = "assistant",
                tool_calls = listOf(
                    ToolCall(
                        id = "call_${rule.tool}",
                        function = FunctionCall(
                            name = rule.tool,
                            arguments = AppJson.encodeToString(fillArgs(rule.arguments, userText)),
                        ),
                    ),
                ),
            )

            else -> ChatMessage(
                role = "assistant",
                content = "Заглушка: вы написали «$userText»",
            )
        }

        val finishReason = if (message.tool_calls != null) "tool_calls" else "stop"

        return ChatResponse(
            id = "mock-${System.nanoTime()}",
            created = System.currentTimeMillis() / 1000,
            model = request.model,
            choices = listOf(
                Choice(
                    message = message.copy(reasoning_content = thinkingFor(last, rule)),
                    finish_reason = finishReason
                )
            ),
            usage = Usage(0, 0, 0),
        )
    }

    private fun fillArgs(args: Map<String, String>, userText: String): Map<String, String> {
        val today = LocalDate.now(ZoneId.of("Asia/Tomsk"))
        val tomorrow = today.plusDays(1)

        return args.mapValues {
            it.value.replace("{{text}}", userText)
                .replace("{{today}}", today.toString())
                .replace("{{tomorrow}}", tomorrow.toString())
        }
    }

    private fun statusOf(content: String?): String? {
        try {
            if (content == null) return null
            return AppJson.parseToJsonElement(content)
                .jsonObject["status"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            return null
        }
    }

    private fun thinkingFor(last: ChatMessage, rule: ToolRule?): String {
        return when {
            last.role == "tool" ->
                "Инструмент вернул результат, собираю итоги"

            rule != null ->
                "Для решения запроса лучше всего подойдет ${rule.tool}"

            else ->
                "Подходящего инструмента нет, отвечу текстом"
        }
    }

    override fun stream(request: ChatRequest): Flow<ChatChunk> =
        flow {
            val response = complete(request)
            val message = response.choices[0].message
            val finish_reason = response.choices[0].finish_reason
            fun chunk(delta: Delta, finish_reason: String? = null): ChatChunk =
                ChatChunk(
                    id = response.id, created = response.created, model = response.model, choices = listOf(
                        ChunkChoice(delta = delta, finish_reason = finish_reason)
                    )
                )
            emit(chunk(Delta(role = "assistant")))
            for (part in (message.reasoning_content ?: "").chunked(10)) {
                delay(50)
                emit(chunk(Delta(reasoning_content = part)))
            }
            if (message.tool_calls == null) {
                for (part in (message.content ?: "").chunked(10)) {
                    delay(50)
                    emit(chunk(Delta(content = part)))
                }
            } else {
                for ((index, call) in message.tool_calls.withIndex()) {
                    emit(
                        chunk(
                            Delta(
                                tool_calls =
                                    listOf(
                                        ToolCallDelta(
                                            index = index,
                                            id = call.id,
                                            type = call.type,
                                            function = FunctionCallDelta(
                                                name = call.function.name,
                                                arguments = ""

                                            )

                                        )
                                    )
                            )
                        )
                    )
                    for (part in call.function.arguments.chunked(10)) {
                        delay(50)
                        emit(
                            chunk(
                                Delta(
                                    tool_calls =
                                        listOf(
                                            ToolCallDelta(
                                                index = index,
                                                function = FunctionCallDelta(
                                                    arguments = part
                                                )
                                            )
                                        )
                                )
                            )
                        )
                    }
                }
            }
            emit(chunk(Delta(), finish_reason = finish_reason))
        }
}
