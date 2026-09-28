package ru.tpu.ml.api

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.GatewayTimeout
import io.ktor.http.HttpStatusCode.Companion.InternalServerError
import io.ktor.http.HttpStatusCode.Companion.Unauthorized
import io.ktor.http.HttpStatusCode.Companion.UnsupportedMediaType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import ru.tpu.ml.providers.UpstreamException

class MlException(
    val status: HttpStatusCode,
    val type: String,
    val code: String,
    override val message: String,
    val details: String? = null,
    val retryAfter: String? = null,
    cause: Throwable
) : RuntimeException() {

}

fun Application.configureErrors() {
    install(StatusPages) {
        exception<UpstreamException> { call, cause ->
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorBody(ErrorInfo("Модель недоступна, попробуйте позже", "upstream_error"))
            )
        }
        exception<HttpRequestTimeoutException> { call, cause ->
            call.respond(GatewayTimeout, ErrorBody(ErrorInfo("Модель не ответила вовремя", "timeout")))
        }
        exception<MlException> { call, cause ->
            call.respond(
                BadRequest,
                ErrorBody(ErrorInfo(cause.message ?: "Некорректный запрос", "invalid_request_error"))
            )
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
    }
}

private suspend fun ApplicationCall.respondError(
    status : HttpStatusCode,
    type: String,
    code: String,
    message: String,
    details: String? = null
) {
    respond(status, ErrorBody(ErrorInfo(message, type, code, details)))
}
