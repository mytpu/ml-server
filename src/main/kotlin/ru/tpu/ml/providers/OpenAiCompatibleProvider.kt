package ru.tpu.ml.providers

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import ru.tpu.ml.api.ChatChunk
import ru.tpu.ml.api.ChatRequest
import ru.tpu.ml.api.ChatResponse

class OpenAiCompatibleProvider (
    private val baseUrl: String,
    private val apiKey: String,
    private val model : String,
    private val client : HttpClient
) : LlmProvider{
    override val name = "openai"
    override fun stream(request: ChatRequest) : Flow<ChatChunk> = TODO("Шаг 7")
    override suspend fun complete(request: ChatRequest) : ChatResponse{
        val resp = client.post("$baseUrl/chat/completions") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(request.copy(model = model, stream = false))
        }
        if (!resp.status.isSuccess()) {throw UpstreamException(resp.status.value, resp.bodyAsText().take(300))
        }
        val answer : ChatResponse = resp.body()
        return answer.copy(model = request.model)
    }
}