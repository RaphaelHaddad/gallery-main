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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "OpenAiApiServer"

class OpenAiApiServer(
    private val context: Context,
    private val port: Int,
    private val modelName: String,
    private val onRequestProcessed: (Int) -> Unit,
    private val onLog: (ServerLog) -> Unit
) : NanoHTTPD("0.0.0.0", port) {  // Bind to all interfaces for remote access

    // Server state
    private val isProcessing = AtomicBoolean(false)
    private val requestCounter = AtomicInteger(0)
    private val serverScope = CoroutineScope(Dispatchers.IO)
    private val startTime = System.currentTimeMillis()

    // JSON configuration for kotlinx.serialization
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    // Model reference (will be set from ViewModel)
    var model: Model? = null
    var modelInstance: LlmModelInstance? = null

    init {
        log(LogLevel.INFO, "Server initialized on port $port")
    }

    override fun start() {
        log(LogLevel.INFO, "Starting server...")
        super.start()
    }

    override fun stop() {
        log(LogLevel.INFO, "Stopping server...")
        super.stop()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        log(LogLevel.INFO, "Request: $method $uri from ${session.remoteIpAddress}")

        return try {
            when {
                uri == "/health" && method == Method.GET -> handleHealthCheck()
                uri == "/v1/models" && method == Method.GET -> handleListModels()
                uri == "/v1/chat/completions" && method == Method.POST -> handleChatCompletion(session)
                else -> handleNotFound(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            log(LogLevel.ERROR, "Error: ${e.message}")
            handleError(e.message ?: "Unknown error")
        }
    }

    private fun handleHealthCheck(): Response {
        val uptime = (System.currentTimeMillis() - startTime) / 1000
        val response = HealthResponse(
            status = "healthy",
            serverRunning = true,
            modelLoaded = modelName,
            requestsProcessed = requestCounter.get(),
            uptimeSeconds = uptime
        )

        log(LogLevel.SUCCESS, "Health check: ${response.requestsProcessed} requests processed")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            json.encodeToString(response)
        )
    }

    private fun handleListModels(): Response {
        // For now, just return the current model
        val models = listOf(
            ModelInfo(
                id = modelName,
                objectName = "model",
                created = startTime / 1000,
                ownedBy = "google"
            )
        )

        val response = ModelsResponse(
            objectName = "list",
            data = models
        )

        log(LogLevel.SUCCESS, "Models listed: ${models.size} available")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            json.encodeToString(response)
        )
    }

    private fun handleChatCompletion(session: IHTTPSession): Response {
        // Check if already processing (no concurrency for now)
        if (isProcessing.get()) {
            log(LogLevel.WARNING, "Request rejected: Server busy")
            val error = ErrorResponse(
                error = ErrorDetail(
                    message = "Server is currently processing a request. Please try again later.",
                    type = "server_busy",
                    code = 503
                )
            )
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                json.encodeToString(error)
            )
        }

        // Check if model is initialized
        if (modelInstance == null) {
            log(LogLevel.ERROR, "Model not initialized")
            val error = ErrorResponse(
                error = ErrorDetail(
                    message = "Model is not initialized. Please start the server properly.",
                    type = "model_not_initialized",
                    code = 503
                )
            )
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                json.encodeToString(error)
            )
        }

        // Parse request body
        val body = mutableMapOf<String, String>()
        try {
            session.parseBody(body)
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Failed to parse request body: ${e.message}")
            return handleError("Failed to parse request body: ${e.message}")
        }

        val jsonBody = body["postData"]

        // Validate request body is not empty
        if (jsonBody.isNullOrBlank()) {
            log(LogLevel.ERROR, "Empty request body")
            val error = ErrorResponse(
                error = ErrorDetail(
                    message = "Request body is empty",
                    type = "invalid_request",
                    code = 400
                )
            )
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                json.encodeToString(error)
            )
        }

        // Parse JSON
        val request = try {
            json.decodeFromString<ChatCompletionRequest>(jsonBody)
        } catch (e: SerializationException) {
            log(LogLevel.ERROR, "Invalid JSON: ${e.message}")
            val error = ErrorResponse(
                error = ErrorDetail(
                    message = "Invalid JSON format: ${e.message}",
                    type = "invalid_request",
                    code = 400
                )
            )
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                json.encodeToString(error)
            )
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Failed to parse request: ${e.message}")
            val error = ErrorResponse(
                error = ErrorDetail(
                    message = "Failed to parse request: ${e.message}",
                    type = "invalid_request",
                    code = 400
                )
            )
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                json.encodeToString(error)
            )
        }

        // Validate required fields
        if (request.model.isBlank()) {
            log(LogLevel.ERROR, "Missing model field")
            val error = ErrorResponse(
                error = ErrorDetail(
                    message = "Field 'model' is required",
                    type = "invalid_request",
                    code = 400
                )
            )
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                json.encodeToString(error)
            )
        }

        if (request.messages.isEmpty()) {
            log(LogLevel.ERROR, "Empty messages array")
            val error = ErrorResponse(
                error = ErrorDetail(
                    message = "Field 'messages' must not be empty",
                    type = "invalid_request",
                    code = 400
                )
            )
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                json.encodeToString(error)
            )
        }

        // Validate model name
        if (request.model != modelName) {
            log(LogLevel.ERROR, "Model not found: ${request.model}")
            val error = ErrorResponse(
                error = ErrorDetail(
                    message = "Model '${request.model}' not found. Available model: '$modelName'",
                    type = "invalid_request",
                    code = 404
                )
            )
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                json.encodeToString(error)
            )
        }

        // Validate there's at least one user message
        val hasUserMessage = request.messages.any { it.role == "user" }
        if (!hasUserMessage) {
            log(LogLevel.ERROR, "No user message found")
            val error = ErrorResponse(
                error = ErrorDetail(
                    message = "At least one user message is required",
                    type = "invalid_request",
                    code = 400
                )
            )
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                json.encodeToString(error)
            )
        }

        // Process request
        isProcessing.set(true)
        requestCounter.incrementAndGet()

        log(LogLevel.INFO, "Processing chat completion (request #${requestCounter.get()})")

        return try {
            // Parse content from request
            val (text, images, audioBase64) = try {
                parseContent(request)
            } catch (e: IllegalArgumentException) {
                log(LogLevel.ERROR, "Invalid content: ${e.message}")
                isProcessing.set(false)
                val error = ErrorResponse(
                    error = ErrorDetail(
                        message = "Invalid content: ${e.message}",
                        type = "invalid_request",
                        code = 400
                    )
                )
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    json.encodeToString(error)
                )
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Failed to parse content: ${e.message}")
                isProcessing.set(false)
                val error = ErrorResponse(
                    error = ErrorDetail(
                        message = "Failed to parse content: ${e.message}",
                        type = "invalid_request",
                        code = 400
                    )
                )
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    json.encodeToString(error)
                )
            }

            // Validate content is not empty
            if (text.isBlank() && images.isEmpty() && audioBase64 == null) {
                log(LogLevel.ERROR, "Empty content: no text, images, or audio")
                isProcessing.set(false)
                val error = ErrorResponse(
                    error = ErrorDetail(
                        message = "Message content must contain at least text, images, or audio",
                        type = "invalid_request",
                        code = 400
                    )
                )
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    json.encodeToString(error)
                )
            }

            // Validate image count (prevent memory issues)
            if (images.size > 10) {
                log(LogLevel.ERROR, "Too many images: ${images.size}")
                isProcessing.set(false)
                val error = ErrorResponse(
                    error = ErrorDetail(
                        message = "Too many images. Maximum 10 images per request",
                        type = "invalid_request",
                        code = 400
                    )
                )
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    json.encodeToString(error)
                )
            }

            log(LogLevel.INFO, "Request parsed: text=${text.length} chars, images=${images.size}, audio=${if (audioBase64 != null) "yes" else "no"}")

            // Run inference
            val result = try {
                runInference(text, images, audioBase64)
            } catch (e: IllegalStateException) {
                log(LogLevel.ERROR, "Model error: ${e.message}")
                throw e // Re-throw to be caught by outer try-catch
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Inference error: ${e.message}")
                isProcessing.set(false)
                val error = ErrorResponse(
                    error = ErrorDetail(
                        message = "Inference failed: ${e.message}",
                        type = "api_error",
                        code = 500
                    )
                )
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    json.encodeToString(error)
                )
            }

            // Validate result is not empty
            if (result.isBlank()) {
                log(LogLevel.WARNING, "Empty result from inference")
            }

            // Format response
            val response = ChatCompletionResponse(
                id = "chatcmpl-${System.currentTimeMillis()}",
                objectName = "chat.completion",
                created = System.currentTimeMillis() / 1000,
                model = modelName,
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(
                            role = "assistant",
                            content = JsonPrimitive(result)  // Return as string (JSON primitive)
                        ),
                        finishReason = "stop"
                    )
                ),
                usage = Usage(
                    promptTokens = estimateTokens(text),
                    completionTokens = estimateTokens(result),
                    totalTokens = estimateTokens(text) + estimateTokens(result)
                )
            )

            log(LogLevel.SUCCESS, "Request completed successfully")
            onRequestProcessed(requestCounter.get())

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                json.encodeToString(response)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error processing request", e)
            log(LogLevel.ERROR, "Processing error: ${e.message}")
            handleError(e.message ?: "Processing error")
        } finally {
            isProcessing.set(false)
        }
    }

    private fun parseContent(request: ChatCompletionRequest): Triple<String, List<Bitmap>, String?> {
        val lastMessage = request.messages.lastOrNull { it.role == "user" }
            ?: throw IllegalArgumentException("No user message found")

        val textParts = mutableListOf<String>()
        val images = mutableListOf<Bitmap>()
        var audioBase64: String? = null

        // Parse the JsonElement content
        val content = lastMessage.content

        when {
            // Case 1: content is a string (text-only message)
            content is JsonPrimitive -> {
                val textValue = content.content
                if (textValue.isNotBlank()) {
                    textParts.add(textValue)
                }
            }
            // Case 2: content is an array (multimodal message)
            content is JsonArray -> {
                if (content.size == 0) {
                    throw IllegalArgumentException("Content parts array is empty")
                }

                // Parse each part from JSON array
                for (item in content) {
                    val partObj = item.jsonObject
                    val type = partObj["type"]?.jsonPrimitive?.content

                    when (type) {
                        "text" -> {
                            val text = partObj["text"]?.jsonPrimitive?.content
                            if (!text.isNullOrBlank()) {
                                textParts.add(text)
                            }
                        }
                        "image_url" -> {
                            val imageUrlObj = partObj["image_url"]?.jsonObject
                            val url = imageUrlObj?.get("url")?.jsonPrimitive?.content

                            if (url.isNullOrBlank()) {
                                log(LogLevel.WARNING, "Skipping image_url with empty URL")
                                continue
                            }

                            if (url.length > 10_000_000) {
                                throw IllegalArgumentException("Image URL too large (max 10MB)")
                            }

                            try {
                                val bitmap = decodeImageFromUrl(url)
                                if (bitmap != null) {
                                    val bitmapSize = bitmap.byteCount
                                    if (bitmapSize > 50_000_000) {
                                        throw IllegalArgumentException("Image too large (max 50MB)")
                                    }
                                    images.add(bitmap)
                                } else {
                                    log(LogLevel.WARNING, "Failed to decode image, skipping")
                                }
                            } catch (e: IllegalArgumentException) {
                                throw e
                            } catch (e: Exception) {
                                log(LogLevel.ERROR, "Failed to decode image: ${e.message}")
                                throw IllegalArgumentException("Failed to decode image: ${e.message}")
                            }
                        }
                        "audio_url" -> {
                            val audioUrlObj = partObj["audio_url"]?.jsonObject
                            val url = audioUrlObj?.get("url")?.jsonPrimitive?.content

                            if (url.isNullOrBlank()) {
                                log(LogLevel.WARNING, "Skipping audio_url with empty URL")
                                continue
                            }

                            if (url.length > 10_000_000) {
                                throw IllegalArgumentException("Audio URL too large (max 10MB)")
                            }

                            try {
                                val extractedAudio = extractBase64FromUrl(url)
                                if (extractedAudio != null) {
                                    val audioBytes = Base64.decode(extractedAudio, Base64.DEFAULT)
                                    if (audioBytes.size > 50_000_000) {
                                        throw IllegalArgumentException("Audio too large (max 50MB)")
                                    }
                                    audioBase64 = extractedAudio
                                } else {
                                    log(LogLevel.WARNING, "Failed to extract audio from URL, skipping")
                                }
                            } catch (e: IllegalArgumentException) {
                                throw e
                            } catch (e: Exception) {
                                log(LogLevel.ERROR, "Failed to decode audio: ${e.message}")
                                throw IllegalArgumentException("Failed to decode audio: ${e.message}")
                            }
                        }
                        else -> {
                            log(LogLevel.WARNING, "Unknown content type: $type, skipping")
                        }
                    }
                }
            }
            else -> {
                throw IllegalArgumentException("Invalid content type: must be string or array")
            }
        }

        return Triple(textParts.joinToString("\n"), images, audioBase64)
    }

    private fun decodeImageFromUrl(url: String): Bitmap? {
        return try {
            val base64Data = extractBase64FromUrl(url)
            if (base64Data.isNullOrBlank()) {
                log(LogLevel.ERROR, "Empty base64 data in URL")
                return null
            }

            val bytes = try {
                Base64.decode(base64Data, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                log(LogLevel.ERROR, "Invalid base64 encoding")
                return null
            }

            if (bytes.isEmpty()) {
                log(LogLevel.ERROR, "Decoded bytes are empty")
                return null
            }

            val options = BitmapFactory.Options().apply {
                // Just decode bounds first to check size
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            // Check if image dimensions are reasonable
            if (options.outWidth > 8192 || options.outHeight > 8192) {
                log(LogLevel.ERROR, "Image dimensions too large: ${options.outWidth}x${options.outHeight}")
                return null
            }

            // Decode the actual image
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory when decoding image")
            log(LogLevel.ERROR, "Out of memory when decoding image")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode image", e)
            log(LogLevel.ERROR, "Failed to decode image: ${e.message}")
            null
        }
    }

    private fun extractBase64FromUrl(url: String): String? {
        if (url.isBlank()) return null

        return when {
            url.startsWith("data:") -> {
                // Format: data:image/png;base64,iVBOR...
                val commaIndex = url.indexOf(',')
                if (commaIndex == -1 || commaIndex == url.length - 1) {
                    log(LogLevel.ERROR, "Invalid data URL format")
                    return null
                }
                url.substring(commaIndex + 1)
            }
            url.startsWith("base64://") -> {
                // Format: base64://iVBOR...
                if (url.length <= 9) {
                    log(LogLevel.ERROR, "Invalid base64 URL format")
                    return null
                }
                url.substring(9)
            }
            else -> {
                log(LogLevel.WARNING, "Unknown URL format: ${url.take(50)}...")
                null
            }
        }
    }

    private fun runInference(text: String, images: List<Bitmap>, audioBase64: String?): String {
        val instance = modelInstance
            ?: throw IllegalStateException("Model not initialized")

        val audioClips = if (audioBase64 != null) {
            listOf(Base64.decode(audioBase64, Base64.DEFAULT))
        } else emptyList()

        // Synchronous inference (blocking call)
        val result = mutableListOf<String>()
        var done = false
        var error: Exception? = null

        LlmChatModelHelper.runInference(
            model = model!!,
            input = text,
            images = images,
            audioClips = audioClips,
            resultListener = { partialResult, isDone ->
                result.add(partialResult)
                done = isDone
            },
            cleanUpListener = {},
            onError = { message -> error = Exception(message) }
        )

        // Wait for completion
        val timeout = 300000L // 5 minutes
        val startTime = System.currentTimeMillis()
        while (!done && error == null && System.currentTimeMillis() - startTime < timeout) {
            Thread.sleep(100)
        }

        if (error != null) {
            throw error
        }

        if (!done) {
            throw Exception("Inference timeout")
        }

        return result.joinToString("")
    }

    private fun estimateTokens(text: String): Int {
        // Rough estimation: ~4 chars per token
        return (text.length / 4).coerceAtLeast(1)
    }

    private fun handleNotFound(uri: String): Response {
        val error = ErrorResponse(
            error = ErrorDetail(
                message = "Endpoint not found: $uri",
                type = "not_found",
                code = 404
            )
        )
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "application/json",
            json.encodeToString(error)
        )
    }

    private fun handleError(message: String): Response {
        val error = ErrorResponse(
            error = ErrorDetail(
                message = message,
                type = "api_error",
                code = 500
            )
        )
        return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            "application/json",
            json.encodeToString(error)
        )
    }

    private fun log(level: LogLevel, message: String) {
        val logEntry = ServerLog(
            level = level,
            message = message
        )
        serverScope.launch {
            onLog(logEntry)
        }
    }

    fun getLocalIpAddress(): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            String.format(
                "%d.%d.%d.%d",
                ipAddress and 0xFF,
                ipAddress shr 8 and 0xFF,
                ipAddress shr 16 and 0xFF,
                ipAddress shr 24 and 0xFF
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP address", e)
            "Unknown"
        }
    }

    fun getRequestCount(): Int = requestCounter.get()

    fun getUptimeSeconds(): Long = (System.currentTimeMillis() - startTime) / 1000

    fun isBusy(): Boolean = isProcessing.get()
}
