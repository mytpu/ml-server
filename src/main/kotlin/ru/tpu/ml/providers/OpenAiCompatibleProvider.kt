package ru.tpu.ml.providers

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpStatusCode.Companion.BadGateway
import io.ktor.http.HttpStatusCode.Companion.GatewayTimeout
import io.ktor.http.HttpStatusCode.Companion.ServiceUnavailable
import io.ktor.serialization.JsonConvertException
import io.ktor.util.network.UnresolvedAddressException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import ru.tpu.ml.api.ChatChunk
import ru.tpu.ml.api.ChatRequest
import ru.tpu.ml.api.ChatResponse
import ru.tpu.ml.api.MlException
import kotlin.coroutines.cancellation.CancellationException

class OpenAiCompatibleProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val client: HttpClient
) : LlmProvider {
    override val name = "openai"
    override fun stream(request: ChatRequest): Flow<ChatChunk> = TODO("Шаг 7")
    override suspend fun complete(request: ChatRequest): ChatResponse {
        try {


            val resp = client.post("$baseUrl/chat/completions") {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(request.copy(model = model, stream = false))
            }
            if (!resp.status.isSuccess()) {
                throw upstreamError(
                    resp.status.value, resp.bodyAsText().take(300),
                    resp.headers[HttpHeaders.RetryAfter]
                )
            }
            val answer: ChatResponse = resp.body()
            return answer.copy(model = request.model)
        } catch (e: MlException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectTimeoutException) {
            throw MlException(GatewayTimeout, "timeout", "upstream_connect_timeout", "Модель не отвечает", cause = e)
        } catch (e: HttpRequestTimeoutException) {
            throw MlException(GatewayTimeout, "timeout", "upstream_timeout", "Модель не ответила вовремя", cause = e)
        } catch (e: SocketTimeoutException) {
            throw MlException(GatewayTimeout, "timeout", "upstream_timeout", "Модель не ответила вовремя", cause = e)
        } catch (e: java.net.ConnectException) {
            throw MlException(BadGateway, "upstream_error", "upstream_unreachable", "Модель недоступна", cause = e)
        } catch (e: UnresolvedAddressException) {
            throw MlException(BadGateway, "upstream_error", "upstream_unreachable", "Модель недоступна", cause = e, details = "адрес не найден в DNS")
        } catch (e: JsonConvertException) {
            throw MlException(
                BadGateway,
                "upstream_error",
                "upstream_bad_response",
                "Модель ответила в неизвестном формате",
                cause = e,
                details = e.message
            )
        }
    }

    private fun upstreamError(status: Int, body: String, retryAfter: String?): MlException {
        val details = "upstream $status: $body"
        return when (status) {
            401, 403 -> MlException(
                status = BadGateway,
                type = "upstream_error",
                code = "upstream_auth_failed",
                message = "Модель недоступна",
                details = details,
                retryAfter = retryAfter
            )

            429 -> MlException(
                status = ServiceUnavailable,
                type = "rate_limit_error",
                code = "upstream_rate_limited",
                message = "Модель перегружена, повторите еще раз позже",
                details = details,
                retryAfter = retryAfter
            )

            400, 404, 422 -> MlException(
                status = BadGateway,
                type = "upstream_error",
                code = "upstream_bad_request",
                message = "Модель не приняла запрос",
                details = details,
                retryAfter = retryAfter
            )

            in 500..599 -> MlException(
                status = BadGateway,
                type = "upstream_error",
                code = "upstream_unavailable",
                message = "Модель недоступна",
                details = details,
                retryAfter = retryAfter
            )

            else -> MlException(
                status = BadGateway,
                type = "upstream_error",
                code = "upstream_error",
                message = "Модель недоступна",
                details = details,
                retryAfter = retryAfter
            )
        }

    }

}