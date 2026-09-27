package ru.tpu.ml.providers

import kotlinx.coroutines.flow.Flow
import ru.tpu.ml.api.ChatChunk
import ru.tpu.ml.api.ChatRequest
import ru.tpu.ml.api.ChatResponse

// Договор для всех моделей: заглушка, внешнее API, vLLM на Spark.
// Только заголовки функций — как они работают, решает каждый провайдер сам.
interface LlmProvider {
    val name: String

    suspend fun complete(request: ChatRequest): ChatResponse

    fun stream(request: ChatRequest): Flow<ChatChunk>
}
