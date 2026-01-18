/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

// Request models
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("top_k")
    val topK: Int? = null
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement  // Can be JsonPrimitive (string) or JsonArray (array of parts)
)

@Serializable
data class ContentPart(
    val type: String, // "text", "image_url", "audio_url"
    val text: String? = null,
    @SerialName("image_url")
    val imageUrl: MediaUrl? = null,
    @SerialName("audio_url")
    val audioUrl: MediaUrl? = null
)

@Serializable
data class MediaUrl(
    val url: String // Supports base64://, data:image/png;base64,, http://
)

// Response models
@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object")
    val objectName: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage
)

@Serializable
data class Choice(
    val index: Int,
    val message: ChatMessage,
    @SerialName("finish_reason")
    val finishReason: String
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)

// Models list response
@Serializable
data class ModelsResponse(
    @SerialName("object")
    val objectName: String,
    val data: List<ModelInfo>
)

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("object")
    val objectName: String,
    val created: Long,
    @SerialName("owned_by")
    val ownedBy: String
)

// Health check response
@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("server_running")
    val serverRunning: Boolean,
    @SerialName("model_loaded")
    val modelLoaded: String?,
    @SerialName("requests_processed")
    val requestsProcessed: Int,
    @SerialName("uptime_seconds")
    val uptimeSeconds: Long
)

// Error response
@Serializable
data class ErrorResponse(
    val error: ErrorDetail
)

@Serializable
data class ErrorDetail(
    val message: String,
    val type: String,
    val code: Int
)

// Server log entry
data class ServerLog(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val message: String,
    val details: String? = null
)

enum class LogLevel {
    INFO, SUCCESS, ERROR, WARNING
}
