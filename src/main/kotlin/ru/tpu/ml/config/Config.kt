package ru.tpu.ml.config

import java.lang.System.getenv

data class Config(
    val port: Int,
    val backendApiKey: String,
    val provider : String,
    val upstreamUrl: String?,
    val upstreamKey: String?,
    val maxConcurrent: Int,
    val requestTimeoutMs: Long,
    val upstreamModel : String?,
    val debug : Boolean,
    val host : String,
    val queueWaitMs : Long,
    val maxBodyBytes : Long,
    val streamTimeoutMs : Long,
) {
    companion object {
        private val PROVIDERS = setOf("mock", "openai")

        fun fromEnv() : Config {
            // Без значения по умолчанию: забытая переменная в проде не должна молча включать заглушку
            val provider = getenv("ML_PROVIDER")?.takeIf { it.isNotBlank() }
                ?: error("Не задана переменная окружения ML_PROVIDER: mock или openai")
            // Опечатка (в том числе «OpenAI») раньше молча включала заглушку: прод отвечал бы шаблонами
            require(provider in PROVIDERS) { "ML_PROVIDER=$provider: допустимо только mock или openai" }
            val upstreamUrl = getenv("UPSTREAM_URL")?.takeIf { it.isNotBlank() }
            require(provider != "mock" || upstreamUrl == null || getenv("ML_ALLOW_MOCK") == "true") {
                "ML_PROVIDER=mock при заданном UPSTREAM_URL — похоже на ошибку настройки прода; если заглушка нужна, ML_ALLOW_MOCK=true"
            }
            return Config(
                port = getenv("PORT")?.toIntOrNull() ?: 8080,
                backendApiKey = getenv("ML_API_KEY") ?: error("Не задана переменная окружения ML_API_KEY"),
                provider = provider,
                upstreamUrl = upstreamUrl,
                upstreamKey = getenv("UPSTREAM_KEY")?.takeIf { it.isNotBlank() },
                maxConcurrent = getenv("ML_MAX_CONCURRENT")?.toIntOrNull() ?: 8,
                requestTimeoutMs = getenv("ML_TIMEOUT_MS")?.toLongOrNull() ?: 60000,
                upstreamModel = getenv("UPSTREAM_MODEL")?.takeIf { it.isNotBlank() },
                debug = getenv("ML_DEBUG") == "true",
                host = getenv("ML_HOST")?.takeIf{it.isNotBlank() } ?: "0.0.0.0",
                queueWaitMs = getenv("ML_QUEUE_WAIT_MS")?.toLongOrNull() ?: 5000L,
                maxBodyBytes = getenv("ML_MAX_BODY_BYTES")?.toLongOrNull() ?: 1_000_000L,
                streamTimeoutMs = getenv("ML_STREAM_TIMEOUT_MS")?.toLongOrNull() ?: 300_000L,
            )
        }
    }
}