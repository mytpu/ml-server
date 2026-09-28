package ru.tpu.ml.api

import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.GatewayTimeout
import io.ktor.http.HttpStatusCode.Companion.InternalServerError
import io.ktor.http.HttpStatusCode.Companion.Unauthorized
import io.ktor.http.HttpStatusCode.Companion.UnsupportedMediaType
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

class MlException(
    val status: HttpStatusCode,
    val type: String,
    val code: String,
    override val message: String,
    val details: String? = null,
    val retryAfter: String? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

fun Application.configureErrors() {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(
                BadRequest,
                ErrorBody(ErrorInfo(cause.message ?: "Некорректный запрос", "invalid_request_error"))
            )
        }
        exception<MlException> { call, cause ->
            val line : String = "${cause.code} ${cause.details ?: cause.message}"
            if (cause.status.value >= 500) call.application.log.warn(line)
            else call.application.log.info(line)
            cause.retryAfter?.let { call.response.header(HttpHeaders.RetryAfter, it) }
            call.respondError(cause.status,cause.type,cause.code,cause.message,cause.details)
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled", cause)
            call.respond(InternalServerError, ErrorBody(ErrorInfo("Внутренняя ошибка ML-сервера", "internal_error")))
        }
        status(Unauthorized) { call, status ->
            call.respond(
                Unauthorized,
                ErrorBody(ErrorInfo("Неверный или отсутствующий ключ API", "authentication_error"))
            )
        }
        status(UnsupportedMediaType) { call, status ->
            call.respond(
                UnsupportedMediaType,
                ErrorBody(ErrorInfo("Нужен заголовок Content-Type: application/json", "invalid_request_error"))
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(HttpStatusCode.NotFound, "invalid_request_error", "not_found", "Нет такого пути")
        }
    }
}

private suspend fun ApplicationCall.respondError(
    status : HttpStatusCode,
    type: String,
    code: String,
    message: String,
    details: String? = null
) {
    respond(status, ErrorBody(ErrorInfo(message = message, type = type, code = code, request_id = callId, details = details)))
}
