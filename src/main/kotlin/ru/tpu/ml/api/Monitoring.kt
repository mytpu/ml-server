package ru.tpu.ml.api

import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import org.slf4j.event.Level

fun Application.configureMonitoring() {
    install(CallId) {
        retrieveFromHeader("X-Request-Id")
        generate(12,"abcdef0123456789")
        verify { it.length in 1..64 }
        replyToHeader("X-Request-Id")
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        filter { call -> call.request.path().startsWith("/v1") }
        format { call ->
            "${call.response.status()} ${call.request.httpMethod.value} ${call.request.path()} ${call.processingTimeMillis()}"
        }
    }
}