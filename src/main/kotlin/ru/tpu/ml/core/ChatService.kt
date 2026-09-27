package ru.tpu.ml.core

import ru.tpu.ml.api.ChatRequest
import ru.tpu.ml.api.ChatResponse
import ru.tpu.ml.providers.LlmProvider

class ChatService(private val provider: LlmProvider) {
    suspend fun complete(request: ChatRequest): ChatResponse {
        return provider.complete(request)
    }
}