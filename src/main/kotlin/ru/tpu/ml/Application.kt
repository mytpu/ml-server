package ru.tpu.ml

import io.ktor.http.*
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import ru.tpu.ml.api.*
import ru.tpu.ml.config.Config
import ru.tpu.ml.core.ChatService
import ru.tpu.ml.providers.LlmProvider
import ru.tpu.ml.providers.MockProvider
import ru.tpu.ml.providers.OpenAiCompatibleProvider
import ru.tpu.ml.providers.createHttpClient
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

fun main() {
    val config = Config.fromEnv()
    val provider: LlmProvider = when (config.provider) {
        "openai" -> {
            OpenAiCompatibleProvider(
                baseUrl = config.upstreamUrl ?: error("Не задан upstream url"),
                apiKey = config.upstreamKey,
                model = config.upstreamModel ?: error("Не задан upstream model"),
                client = createHttpClient(config)
            )
        }

        else -> {
            MockProvider
        }
    }
    val chatService = ChatService(provider, config.maxConcurrent, config.queueWaitMs)

    embeddedServer(CIO, config.port, config.host) {
        module(config, chatService)
    }.start(true)
}

fun Application.module(config: Config, chatService: ChatService) {
    configureMonitoring()
    configureErrors(config)
    install(ContentNegotiation) {
        json(AppJson)
    }
    install(Authentication) {
        bearer("backend") {
            authenticate { credential ->
                if (MessageDigest.isEqual(credential.token.toByteArray(), config.backendApiKey.toByteArray()))
                    BackendPrincipal("backend")
                else
                    null
            }
        }
    }
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        authenticate("backend") {
            get("/v1/models") {
                call.respond(ModelList(data = listOf(ModelInfo(id = "tpu-assistant"), ModelInfo(id = "tpu-analyze"))))
            }
            post("/v1/chat/completions") {
                // Потолок тела запроса: куча нативной сборки — 80 МБ, одно огромное тело не должно её съесть
                val length = call.request.contentLength()
                if (length == null || length > config.maxBodyBytes) {
                    throw MlException(HttpStatusCode.PayloadTooLarge, "invalid_request_error", "body_too_large",
                        "Тело запроса должно быть с Content-Length и не больше ${config.maxBodyBytes} байт")
                }
                val request = call.receive<ChatRequest>()
                if (request.messages.isEmpty()) {
                    throw MlException(BadRequest, "invalid_request_error", "empty_messages", "messages пустой")
                }
                if (request.stream) {
                    val requestId = call.callId
                    chatService.limited {
                        call.response.header(HttpHeaders.CacheControl, "no-cache")
                        call.response.header("X-Accel-Buffering", "no")
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            try {
                                chatService.stream(request).collect { chunk ->
                                    write("data: ${AppJson.encodeToString(chunk)}\n\n")
                                    flush()
                                }
                                write("data: [DONE]\n\n")
                                flush()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: MlException) {
                                if (e.status.value >= 500) {
                                    call.application.log.warn("stream ${e.code} ${e.details ?: e.message}")
                                } else {
                                    call.application.log.info("stream ${e.code} ${e.details ?: e.message}")
                                }
                                write(
                                    "data: ${
                                        AppJson.encodeToString(
                                            ErrorBody(
                                                ErrorInfo(
                                                    message = e.message,
                                                    type = e.type,
                                                    code = e.code,
                                                    request_id = requestId
                                                )
                                            )
                                        )
                                    }\n\n"
                                )
                                flush()
                            } catch (e: Throwable) {
                                call.application.log.error("stream internal_error", e)
                                write(
                                    "data: ${
                                        AppJson.encodeToString(
                                            ErrorBody(
                                                ErrorInfo(
                                                    message = "Внутренняя ошибка ML-сервера",
                                                    type = "internal_error",
                                                    code = "internal_error",
                                                    request_id = requestId
                                                )
                                            )
                                        )
                                    }\n\n"
                                )
                                flush()
                            }
                        }
                    }
                } else {
                    call.respond(chatService.complete(request))
                }
            }
        }
    }
}