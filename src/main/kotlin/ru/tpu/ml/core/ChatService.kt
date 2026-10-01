package ru.tpu.ml.core

import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import ru.tpu.ml.api.ChatChunk
import ru.tpu.ml.api.ChatRequest
import ru.tpu.ml.api.ChatResponse
import ru.tpu.ml.api.MlException
import ru.tpu.ml.providers.LlmProvider

class ChatService(
    private val provider: LlmProvider,
    private val maxConcurrent: Int,
    private val queueWaitMs: Long
) {
    private val semaphore = Semaphore(maxConcurrent)
    suspend fun complete(request: ChatRequest): ChatResponse {
        return limited { provider.complete(request) }
    }




    suspend fun <T> limited(block: suspend () -> T): T {
        var acquired: Boolean = semaphore.tryAcquire()
        if (!acquired) {
            withTimeoutOrNull(queueWaitMs) {
                semaphore.acquire()
                acquired = true
            }
        }
        if (!acquired) {
            throw MlException(
                status = HttpStatusCode.TooManyRequests,
                type = "rate_limit_error",
                code = "server_busy",
                message = "ML-сервер занят, повторите запрос позже",
                retryAfter = "2"
            )
        }
        try {
            return block()
        } finally {
            semaphore.release()
        }

    }

    fun stream(request: ChatRequest): Flow<ChatChunk> {
        return provider.stream(request)
    }
}