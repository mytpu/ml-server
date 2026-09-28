package ru.tpu.ml.api
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class ChatRequest(
    val model : String,
    val messages : List<ChatMessage>,
    val tools : List<ToolDef>? = null,
    val tool_choice : JsonElement? = null,
    val stream : Boolean = false,
    val temperature : Double? = null,
    val max_tokens : Int? = null,
    val response_format :JsonObject? = null
)
@Serializable
data class ChatMessage(
    val role : String,
    val content : String? = null,
    val tool_calls : List<ToolCall>? = null,
    val tool_call_id : String? = null,
)
@Serializable
data class ToolDef(
    val type : String = "function",
    val function : FunctionDef
)
@Serializable
data class FunctionDef(
    val name : String,
    val description: String? = null,
    val parameters: JsonObject? = null,
)
@Serializable
data class ToolCall(
    val id : String,
    val type : String = "function",
    val function : FunctionCall
)
@Serializable
data class FunctionCall(
    val name : String,
    val arguments : String
)
@Serializable
data class ChatResponse(
    val id : String,
    @SerialName("object") val obj : String = "chat.completion",
    val created : Long,
    val model : String,
    val choices : List<Choice>,
    val usage : Usage? = null,
)
@Serializable
data class Choice(
    val index: Int = 0,
    val message : ChatMessage,
    val finish_reason : String? = null,
)
@Serializable
data class Usage(
    val prompt_tokens : Int,
    val completion_tokens : Int,
    val total_tokens : Int
)
@Serializable
data class ChatChunk(
    val id : String,
    @SerialName("object") val obj : String = "chat.completion.chunk",
    val created : Long,
    val model : String,
    val choices : List<ChunkChoice>,
    val usage : Usage? = null,
)
@Serializable
data class ChunkChoice(
    val index : Int = 0,
    val delta : Delta,
    val finish_reason : String? = null,
)
@Serializable
data class Delta(
    val role : String? = null,
    val content : String? = null,
    val tool_calls : List<ToolCallDelta>? = null,
)
@Serializable
data class ToolCallDelta(
    val index : Int,
    val id : String? = null,
    val type : String? = null,
    val function : FunctionCallDelta? = null,
)
@Serializable
data class FunctionCallDelta(
    val name : String? = null,
    val arguments : String? = null,
)
@Serializable
data class ErrorBody(
    val error : ErrorInfo,
)
@Serializable
data class ErrorInfo(
    val message: String,
    val type : String,
    val code : String? = null,
    val request_id : String? = null,
    val details: String? = null
)
@Serializable
data class ModelList(
    @SerialName("object") val obj : String = "list",
    val data : List<ModelInfo>
)
@Serializable
data class ModelInfo(
    val id : String,
    @SerialName("object") val obj : String = "model",
)