package ru.tpu.ml.providers

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpStatusCode.Companion.BadGateway
import io.ktor.http.HttpStatusCode.Companion.GatewayTimeout
import io.ktor.http.HttpStatusCode.Companion.ServiceUnavailable
import io.ktor.serialization.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import ru.tpu.ml.api.*
import kotlin.coroutines.cancellation.CancellationException

class OpenAiCompatibleProvider(
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    private val client: HttpClient,
    private val streamTimeoutMs: Long
) : LlmProvider {
    override val name = "openai"
    override fun stream(request: ChatRequest): Flow<ChatChunk> = flow {
        try {
            client.preparePost("$baseUrl/chat/completions") {
                if (apiKey != null) {
                    bearerAuth(apiKey)
                }
                timeout { requestTimeoutMillis = streamTimeoutMs }
                contentType(ContentType.Application.Json)
                setBody(request.copy(model = model, stream = true))
            }.execute { resp ->
                if (!resp.status.isSuccess()) {
                    throw upstreamError(
                        resp.status.value, errorText(resp),
                        resp.headers[HttpHeaders.RetryAfter]
                    )
                }
                val channel = resp.bodyAsChannel()
                var cuttedChunks = 0
                while (true) {
                    val line = channel.readUTF8Line(MAX_LINE_CHARS) ?: break
                    if (!line.startsWith("data:")) continue
                    val cut = line.substringAfter("data:").trim()
                    if (cut != "[DONE]") {
                        val json = AppJson.decodeFromString<ChatChunk>(cut)
                        emit(json.copy(model = request.model))
                        cuttedChunks++
                    } else break
                }
                if (cuttedChunks == 0) {
                    throw MlException(
                        BadGateway,
                        code = "upstream_bad_response",
                        message = "Модель дала пустой ответ",
                        type = "upstream_error",
                        details = "stream: no data chunks"
                    )
                }
            }
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
            throw MlException(
                BadGateway,
                "upstream_error",
                "upstream_unreachable",
                "Модель недоступна",
                cause = e,
                details = "адрес не найден в DNS"
            )
        } catch (e: SerializationException) {
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

    override suspend fun complete(request: ChatRequest): ChatResponse {
        try {


            // execute, а не post: тело ошибки читается с потолком (errorText), а не буферизуется целиком
            return client.preparePost("$baseUrl/chat/completions") {
                if (apiKey != null) {
                    bearerAuth(apiKey)
                }
                contentType(ContentType.Application.Json)
                setBody(request.copy(model = model, stream = false))
            }.execute { resp ->
                if (!resp.status.isSuccess()) {
                    throw upstreamError(
                        resp.status.value, errorText(resp),
                        resp.headers[HttpHeaders.RetryAfter]
                    )
                }
                val answer: ChatResponse = resp.body()
                answer.copy(model = request.model)
            }
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
            throw MlException(
                BadGateway,
                "upstream_error",
                "upstream_unreachable",
                "Модель недоступна",
                cause = e,
                details = "адрес не найден в DNS"
            )
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


    /** Тело ошибки апстрима — не больше 4 КБ в память, в сообщение — первые 300 символов. */
    private suspend fun errorText(resp: HttpResponse): String =
        resp.bodyAsChannel().readRemaining(ERROR_BODY_LIMIT).readByteArray().decodeToString().take(300)

    private companion object {
        const val ERROR_BODY_LIMIT = 4096L
        const val MAX_LINE_CHARS = 1_000_000
    }
}
