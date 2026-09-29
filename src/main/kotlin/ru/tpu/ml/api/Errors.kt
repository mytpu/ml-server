package ru.tpu.ml.api

import io.ktor.http.*
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.InternalServerError
import io.ktor.http.HttpStatusCode.Companion.Unauthorized
import io.ktor.http.HttpStatusCode.Companion.UnsupportedMediaType
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import ru.tpu.ml.config.Config

class MlException(
    val status: HttpStatusCode,
    val type: String,
    val code: String,
    override val message: String,
    val details: String? = null,
    val retryAfter: String? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

fun Application.configureErrors(config: Config) {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respondError(
                status = BadRequest,
                code = "invalid_json",
                message = "Некорректный запрос",
                type = "invalid_request_error",
            )
        }
        exception<MlException> { call, cause ->
            val line: String = "${cause.code} ${cause.details ?: cause.message}"
            if (cause.status.value >= 500) call.application.log.warn(line)
            else call.application.log.info(line)
            cause.retryAfter?.let {
                call.response.header(HttpHeaders.RetryAfter, it) }
            call.respondError(
                cause.status,
                cause.type,
                cause.code,
                cause.message,
                cause.details.takeIf { config.debug })
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled", cause)
            call.respondError(
                status = InternalServerError,
                code = "internal_error",
                message = "Внутренняя ошибка ML-сервера",
                type = "internal_error"
            )
        }
        status(Unauthorized) { call, status ->
            call.respondError(
                status = Unauthorized,
                code = "invalid_api_key",
                message = "Неверный или отсутствующий ключ API",
                type = "authentication_error"
            )
        }
        status(UnsupportedMediaType) { call, status ->
            call.respondError(
                status = UnsupportedMediaType,
                code = "unsupported_media_type",
                message = "Нужен заголовок Content-Type: application/json",
                type = "invalid_request_error"
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(HttpStatusCode.NotFound, "invalid_request_error", "not_found", "Нет такого пути")
        }
    }
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    type: String,
    code: String,
    message: String,
    details: String? = null
) {
    respond(
        status,
        ErrorBody(ErrorInfo(message = message, type = type, code = code, request_id = callId, details = details))
    )
}
