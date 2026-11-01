package ru.tpu.ml.core

import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import ru.tpu.ml.api.ChatChunk
import ru.tpu.ml.api.ChatRequest
import ru.tpu.ml.api.ChatResponse
import ru.tpu.ml.api.MlException
import ru.tpu.ml.pii.MaskSession
import ru.tpu.ml.pii.PiiMasker
import ru.tpu.ml.providers.LlmProvider

class ChatService(
    private val provider: LlmProvider,
    private val maxConcurrent: Int,
    private val queueWaitMs: Long,
    private val pii: String = "mask"
) {
    private val log = LoggerFactory.getLogger(ChatService::class.java)
    private val semaphore = Semaphore(maxConcurrent)

    suspend fun complete(request: ChatRequest): ChatResponse {
        return limited {
            if (pii == "mask") {
                val session = MaskSession()
                val maskedRequest = PiiMasker.maskRequest(request, session)
                if (session.hasReplacements() && log.isDebugEnabled) {
                    log.debug("pii masked: {}", session.replacementCounts())
                }
                val response = provider.complete(maskedRequest)
                PiiMasker.unmaskResponse(response, session)
            } else {
                provider.complete(request)
            }
        }
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
        if (pii != "mask") {
            return provider.stream(request)
        }
        val session = MaskSession()
        val maskedRequest = PiiMasker.maskRequest(request, session)
        if (session.hasReplacements() && log.isDebugEnabled) {
            log.debug("pii masked: {}", session.replacementCounts())
        }
        return PiiMasker.unmaskStream(provider.stream(maskedRequest), session)
    }
}
