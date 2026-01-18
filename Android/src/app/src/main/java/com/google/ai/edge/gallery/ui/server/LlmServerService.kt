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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "LlmServerService"
private const val DEFAULT_PORT = 8888
private const val MAX_LOGS = 500
private const val MAX_PORT_RETRIES = 10

class LlmServerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var serverInstance: OpenAiApiServer? = null
    private var serviceStartTime = 0L
    private var statusJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _requestCount = MutableStateFlow(0)
    val requestCount = _requestCount.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val _ipAddress = MutableStateFlow<String?>(null)
    val ipAddress = _ipAddress.asStateFlow()

    private val _uptimeSeconds = MutableStateFlow(0L)
    val uptimeSeconds = _uptimeSeconds.asStateFlow()

    private val _logs = MutableStateFlow<List<ServerLog>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port = _port.asStateFlow()

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): LlmServerService = this@LlmServerService
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "llm_server_channel"
        const val ACTION_START = "com.google.ai.edge.gallery.START_SERVER"
        const val ACTION_STOP = "com.google.ai.edge.gallery.STOP_SERVER"

        const val EXTRA_PORT = "port"
        const val EXTRA_MODEL_NAME = "model_name"

        fun startService(context: Context, port: Int, modelName: String) {
            val intent = Intent(context, LlmServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, LlmServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: ""
                startServer(port, modelName)
            }
            ACTION_STOP -> {
                stopServer()
            }
        }

        // If service is killed, restart with last known configuration
        return START_STICKY
    }

    private var pendingPort: Int = DEFAULT_PORT
    private var pendingModelName: String = ""

    private fun startServer(port: Int, modelName: String) {
        if (_isRunning.value) {
            Log.d(TAG, "Server already running, stopping first...")
            stopServer()
        }

        Log.d(TAG, "Preparing server on port $port with model $modelName")
        pendingPort = port
        pendingModelName = modelName
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        addLog(ServerLog(level = LogLevel.INFO, message = "Service ready, waiting for model assignment"))
    }

    fun startHttpServer(model: com.google.ai.edge.gallery.data.Model) {
        if (_isRunning.value) {
            Log.d(TAG, "HTTP server already running")
            return
        }

        Log.d(TAG, "Starting HTTP server on port $pendingPort")
        serviceScope.launch {
            val started = startServerWithRetries(pendingPort, pendingModelName)
            if (started) {
                // Assign model immediately after server is created
                serverInstance?.let { server ->
                    server.model = model
                    server.modelInstance = model.instance as? com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
                    addLog(ServerLog(level = LogLevel.SUCCESS, message = "Model assigned: ${model.name}"))
                    Log.d(TAG, "Model assigned: ${model.name}, instance: ${server.modelInstance != null}")
                } ?: Log.e(TAG, "Server instance is null after successful start!")
            } else {
                _isRunning.value = false
                _isBusy.value = false
                _uptimeSeconds.value = 0
                _ipAddress.value = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun startServerWithRetries(port: Int, modelName: String): Boolean {
        for (offset in 0 until MAX_PORT_RETRIES) {
            val candidatePort = port + offset
            try {
                serverInstance = OpenAiApiServer(
                    context = this,
                    port = candidatePort,
                    modelName = modelName,
                    onRequestProcessed = { count -> _requestCount.value = count },
                    onLog = { log ->
                        addLog(log)
                        Log.d(TAG, "[${log.level}] ${log.message}")
                    }
                )

                serverInstance?.start()
                _isRunning.value = true
                _port.value = candidatePort
                serviceStartTime = System.currentTimeMillis()
                _ipAddress.value = serverInstance?.getLocalIpAddress()

                startStatusTicker()

                val notification = createNotification("Running on port $candidatePort")
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, notification)

                addLog(
                    ServerLog(
                        level = LogLevel.SUCCESS,
                        message = "Server started successfully",
                        details = "http://${_ipAddress.value}:$candidatePort",
                    )
                )

                Log.d(TAG, "Server started successfully on port $candidatePort")
                return true
            } catch (e: Exception) {
                val isPortInUse = isPortInUse(e)
                Log.e(TAG, "Failed to start server on port $candidatePort", e)
                addLog(
                    ServerLog(
                        level = if (isPortInUse) LogLevel.WARNING else LogLevel.ERROR,
                        message =
                            if (isPortInUse) {
                                "Port $candidatePort is in use. Trying next port..."
                            } else {
                                "Failed to start server: ${e.message}"
                            },
                    )
                )

                if (!isPortInUse) {
                    break
                }

                delay(300)
            }
        }

        addLog(ServerLog(level = LogLevel.ERROR, message = "Unable to bind to any port."))
        return false
    }

    private fun stopServer() {
        Log.d(TAG, "Stopping server")
        serverInstance?.stop()
        serverInstance = null
        statusJob?.cancel()
        statusJob = null
        _isRunning.value = false
        _isBusy.value = false
        _uptimeSeconds.value = 0
        _requestCount.value = 0
        _ipAddress.value = null

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Service not in foreground", e)
        }

        try {
            stopSelf()
        } catch (e: Exception) {
            Log.w(TAG, "Service already stopped", e)
        }

        Log.d(TAG, "Server stopped")
    }

    private fun startStatusTicker() {
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            while (_isRunning.value) {
                val srv = serverInstance
                if (srv != null) {
                    _isBusy.value = srv.isBusy()
                    _uptimeSeconds.value = srv.getUptimeSeconds()
                    _requestCount.value = srv.getRequestCount()
                    _ipAddress.value = srv.getLocalIpAddress()
                }
                delay(1000)
            }
        }
    }

    private fun isPortInUse(e: Exception): Boolean {
        return e is java.net.BindException ||
            e.message?.contains("EADDRINUSE") == true ||
            e.message?.contains("Address already in use") == true ||
            e.cause?.message?.contains("EADDRINUSE") == true
    }

    private fun addLog(log: ServerLog) {
        val updated = mutableListOf<ServerLog>()
        updated.addAll(_logs.value)
        updated.add(log)
        if (updated.size > MAX_LOGS) {
            // Remove oldest logs (from beginning)
            updated.subList(0, updated.size - MAX_LOGS).clear()
        }
        _logs.value = updated
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LLM Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "LLM API Server running in background"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LLM Server")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        return notification
    }

    fun getServer(): OpenAiApiServer? = serverInstance

    fun setModel(model: com.google.ai.edge.gallery.data.Model) {
        serverInstance?.let { server ->
            server.model = model
            server.modelInstance = model.instance as? com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
            addLog(ServerLog(level = LogLevel.INFO, message = "Model assigned to server: ${model.name}"))
            Log.d(TAG, "Model assigned: ${model.name}, instance: ${server.modelInstance != null}")
        } ?: run {
            Log.w(TAG, "Cannot assign model: server instance is null")
        }
    }

    fun getUptimeSeconds(): Long {
        return if (serviceStartTime == 0L) 0L else (System.currentTimeMillis() - serviceStartTime) / 1000
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind called")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopServer()
        serviceScope.cancel()
    }
}
