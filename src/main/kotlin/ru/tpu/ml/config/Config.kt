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
    val host : String
) {
    companion object {
        fun fromEnv() : Config {
            return Config(
                port = getenv("PORT")?.toIntOrNull() ?: 8080,
                backendApiKey = getenv("ML_API_KEY") ?: error("Не задана переменная окружения ML_API_KEY"),
                provider = getenv("ML_PROVIDER")?: "mock",
                upstreamUrl = getenv("UPSTREAM_URL"),
                upstreamKey = getenv("UPSTREAM_KEY"),
                maxConcurrent = getenv("ML_MAX_CONCURRENT")?.toIntOrNull() ?: 8,
                requestTimeoutMs = getenv("ML_TIMEOUT_MS")?.toLongOrNull() ?: 60000,
                upstreamModel = getenv("UPSTREAM_MODEL")?.takeIf { it.isNotBlank() },
                debug = getenv("ML_DEBUG") == "true",
                host = getenv("ML_HOST") ?: "0.0.0.0"
            )
        }
    }
}