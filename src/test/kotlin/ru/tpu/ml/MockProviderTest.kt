package ru.tpu.ml

import kotlinx.coroutines.runBlocking
import ru.tpu.ml.api.ChatMessage
import ru.tpu.ml.api.ChatRequest
import ru.tpu.ml.api.FunctionDef
import ru.tpu.ml.api.ToolDef
import ru.tpu.ml.providers.MockProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockProviderTest {
    private val raspTool = ToolDef(function = FunctionDef(name = "rasp_day"))

    @Test
    fun `расписание и есть инструмент - просит вызвать rasp_day`() = runBlocking {
        val request = ChatRequest(
            model = "tpu-assistant",
            messages = listOf(ChatMessage(role = "user", content = "Покажи расписание")),
            tools = listOf(raspTool),
        )
        val choice = MockProvider.complete(request).choices[0]

        assertEquals("tool_calls", choice.finish_reason)
        assertEquals("rasp_day", choice.message.tool_calls!![0].function.name)
    }

    @Test
    fun `расписание без инструментов - обычный текст`() = runBlocking {
        val request = ChatRequest(
            model = "tpu-assistant",
            messages = listOf(ChatMessage(role = "user", content = "Покажи расписание")),
        )
        val choice = MockProvider.complete(request).choices[0]

        assertEquals("stop", choice.finish_reason)
        assertTrue(choice.message.content!!.startsWith("Заглушка"))
    }

    @Test
    fun `результат инструмента - пересказ, а не новый вызов`() = runBlocking {
        val request = ChatRequest(
            model = "tpu-assistant",
            messages = listOf(
                ChatMessage(role = "user", content = "Покажи расписание"),
                ChatMessage(role = "tool", tool_call_id = "call_1", content = "8:30 Матанализ"),
            ),
            tools = listOf(raspTool),
        )
        val choice = MockProvider.complete(request).choices[0]

        assertEquals("stop", choice.finish_reason)
        assertTrue(choice.message.content!!.startsWith("По данным инструмента"))
    }
}
