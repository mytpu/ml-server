package ru.tpu.ml.providers

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import ru.tpu.ml.api.AppJson
import ru.tpu.ml.config.Config


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