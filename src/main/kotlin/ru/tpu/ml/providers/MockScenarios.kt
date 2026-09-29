package ru.tpu.ml.providers

data class ToolRule(
    val tool: String,
    val keywords: List<String>,
    val arguments: Map<String, String>
)

val TOOL_RULES =
    listOf(

        ToolRule(
            "mail_send",
            listOf("письм"),
            arguments = mapOf(
                "to" to "petrov@tpu.ru",
                "subject" to "Сообщение от студента",
                "body" to "{{text}}",
            ),
        ),
        ToolRule(
            "help_create",
            listOf("заявк"),
            arguments = mapOf(
                "service" to "Wi-Fi и сеть",
                "description" to "{{text}}",
            ),
        ),
        ToolRule(
            "rasp_day",
            listOf("распис"),
            arguments = mapOf(
                "date" to "{{tomorrow}}",
            ),
        ),
        ToolRule(
            "docs_search",
            listOf("как ", "где ", "инструкц"),
            arguments = mapOf(
                "query" to "{{text}}",
            ),
        )
    )
val STATUS_REPLIES = mapOf(
    "needs_confirmation" to "Подготовил черновик. Проверьте его в карточке и подтвердите или отмените.",
    "done" to "Готово, выполнено.",
    "sent" to "Готово, отправлено.",
    "cancelled_by_user" to "Хорошо, отменяю. Если нужно что-то изменить — напишите.",
    "expired" to "Черновик устарел. Подготовить заново?",
    "failed" to "Не получилось выполнить действие. Попробуйте позже или оставьте заявку.",
)