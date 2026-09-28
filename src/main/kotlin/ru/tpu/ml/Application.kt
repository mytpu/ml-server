package ru.tpu.ml
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import ru.tpu.ml.api.AppJson
import ru.tpu.ml.api.BackendPrincipal
import ru.tpu.ml.api.ChatRequest
import ru.tpu.ml.api.ModelInfo
import ru.tpu.ml.api.ModelList
import ru.tpu.ml.api.configureErrors
import ru.tpu.ml.api.configureMonitoring
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
                if (request.messages.isEmpty()) {throw BadRequestException("messages is empty")
                }
                call.respond(chatService.complete(request))
            }
        }
    }
}