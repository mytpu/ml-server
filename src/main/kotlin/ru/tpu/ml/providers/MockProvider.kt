package ru.tpu.ml.providers

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import ru.tpu.ml.api.*

object MockProvider : LlmProvider {
    override val name = "mock"

    override suspend fun complete(request: ChatRequest): ChatResponse {
        val last = request.messages.last()
        val userText = request.messages.lastOrNull { it.role == "user" }?.content ?: ""
        fun hasTool(name :String) : Boolean {
            return request.tools?.any { it.function.name == name } == true
        }
        val rule = TOOL_RULES.firstOrNull { r->
            hasTool(r.tool) &&
                    r.keywords.any{word -> userText.contains(word,ignoreCase = true)}}
        val message = when {
            last.role == "tool" -> ChatMessage(
                role = "assistant",
                content = "По данным инструмента: ${(last.content ?: "").take(200)}",
            )

           rule != null -> ChatMessage(
                role = "assistant",
                tool_calls = listOf(
                    ToolCall(
                        id = "call_${rule.tool}",
                        function = FunctionCall(
                            name = rule.tool,
                            arguments = AppJson.encodeToString(rule.arguments),
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
            created = System.currentTimeMillis() / 1000, // секунды, не миллисекунды
            model = request.model,
            choices = listOf(Choice(message = message, finish_reason = finishReason)),
            usage = Usage(0, 0, 0),
        )
    }
    fun fillArgs(args : Map<String, String>) : Map<String, String> =
        args.mapValues {
            it.value.replace("{{text}}", "userText")
                .replace("{{today}}", "date")
                .replace("{{tomorrow}}", "date+day")}
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
            if (message.tool_calls == null) {
                for (part in (message.content ?: "").chunked(10)) {
                    delay(50)
                    emit(chunk(Delta(content = part)))
                }
            } else {
                emit(chunk(Delta(tool_calls = message.tool_calls.mapIndexed { index, call ->
                    ToolCallDelta(
                        index = index,
                        id = call.id,
                        type = call.type,
                        function = FunctionCallDelta(
                            name = call.function.name,
                            arguments = call.function.arguments
                        ))
                }
                )))
            }
            emit(chunk(Delta(), finish_reason = finish_reason))
        }
}
