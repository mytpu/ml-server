package ru.tpu.ml
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.tpu.ml.api.*
import ru.tpu.ml.config.Config
import ru.tpu.ml.core.ChatService
import ru.tpu.ml.providers.LlmProvider
import ru.tpu.ml.providers.MockProvider
import ru.tpu.ml.providers.OpenAiCompatibleProvider
import ru.tpu.ml.providers.createHttpClient
import java.security.MessageDigest

fun main(){
    val config = Config.fromEnv()
    val provider : LlmProvider = when (config.provider) {
        "openai" -> {
            OpenAiCompatibleProvider(
                baseUrl = config.upstreamUrl ?: error("Не задан upstream url"),
                apiKey = config.upstreamKey ?: error("Не задан upstream key"),
                model = config.upstreamModel ?: error("Не задан upstream model"),
                client = createHttpClient(config)
            )
        }
        else -> {
            MockProvider
        }
    }
    val chatService = ChatService(provider)

    embeddedServer(Netty, config.port ) {
        module(config, chatService)
    }.start(true)
}

fun Application.module(config : Config, chatService:ChatService) {
    configureMonitoring()
    configureErrors()
    install(ContentNegotiation) {
        json(AppJson)
    }
    install(Authentication) {
        bearer ("backend"){
            authenticate { credential ->
                if (MessageDigest.isEqual(credential.token.toByteArray(),config.backendApiKey.toByteArray()))
                    BackendPrincipal("backend")
                else
                    null }
        }
    }
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        authenticate("backend") {
            get("/v1/models"){
                call.respond(ModelList(data = listOf(ModelInfo(id = "tpu-assistant"), ModelInfo(id = "tpu-analyze"))))
            }
            post("/v1/chat/completions"){
                val request = call.receive<ChatRequest>()
                if (request.messages.isEmpty()) {
                    throw MlException(BadRequest, "invalid_request_error", "empty_messages", "messages пустой")
                }
                call.respond(chatService.complete(request))
            }
        }
    }
}