package ru.tpu.ml.providers

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import ru.tpu.ml.api.AppJson
import ru.tpu.ml.config.Config

class UpstreamException(val status : Int, message: String) : RuntimeException(message) {
}
fun createHttpClient(config: Config): HttpClient {
    return HttpClient(CIO) {
        install(ContentNegotiation) {
            json(AppJson)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = config.requestTimeoutMs
            socketTimeoutMillis = 60_000
        }
    }
}