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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "LlmServerViewModel"
private const val DEFAULT_PORT = 8888
private const val MAX_LOGS = 500

data class LlmServerUiState(
    val isServerRunning: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val ipAddress: String? = null,
    val logs: List<ServerLog> = emptyList(),
    val requestCount: Int = 0,
    val uptimeSeconds: Long = 0,
    val isModelInitializing: Boolean = false,
    val isServerBusy: Boolean = false
)

@HiltViewModel
class LlmServerViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(LlmServerUiState())
    val uiState = _uiState.asStateFlow()

    private val manualLogs = mutableListOf<ServerLog>()
    private var serviceLogsSnapshot: List<ServerLog> = emptyList()
    private var boundService: LlmServerService? = null
    private var serviceConnection: ServiceConnection? = null
    private var serviceStatusJob: Job? = null
    private var boundContext: Context? = null

    // Model pending assignment to the service-server instance after binding
    private var pendingModelToSet: Model? = null

    fun startServer(context: Context, model: Model, port: Int = DEFAULT_PORT) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isModelInitializing = true,
                    port = port
                )

                if (model.instance == null) {
                    addLog(ServerLog(level = LogLevel.INFO, message = "Initializing model: ${model.name}"))
                    initializeModel(context, model)
                    addLog(ServerLog(level = LogLevel.SUCCESS, message = "Model initialized successfully"))
                } else {
                    addLog(ServerLog(level = LogLevel.INFO, message = "Model already initialized: ${model.name}"))
                }

                addLog(ServerLog(level = LogLevel.INFO, message = "Starting server on port $port"))
                // Remember which model to assign to the service once we bind
                pendingModelToSet = model

                LlmServerService.startService(context.applicationContext, port, model.name)
                bindToService(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                addLog(ServerLog(level = LogLevel.ERROR, message = "Failed to start server: ${e.message}"))
            } finally {
                _uiState.value = _uiState.value.copy(isModelInitializing = false)
            }
        }
    }

    fun stopServer(context: Context) {
        viewModelScope.launch {
            try {
                addLog(ServerLog(level = LogLevel.INFO, message = "Stopping server"))
                LlmServerService.stopService(context.applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop server", e)
                addLog(ServerLog(level = LogLevel.ERROR, message = "Failed to stop server: ${e.message}"))
            } finally {
                _uiState.value = _uiState.value.copy(
                    isServerRunning = false,
                    requestCount = 0,
                    uptimeSeconds = 0,
                    isServerBusy = false
                )
                unbindFromService()
            }
        }
    }

    private fun bindToService(context: Context) {
        if (serviceConnection != null) return

        val appContext = context.applicationContext
        val intent = Intent(appContext, LlmServerService::class.java)
        boundContext = appContext

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as? LlmServerService.LocalBinder)?.getService()
                boundService?.let { service ->
                    // If there's a pending model, start HTTP server with it
                    pendingModelToSet?.let { modelToAssign ->
                        // Start HTTP server with model - it will assign after creation
                        service.startHttpServer(modelToAssign)
                        pendingModelToSet = null
                    }
                    
                    // Now observe service flows
                    observeServiceFlows(service)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
                serviceStatusJob?.cancel()
                serviceStatusJob = null
                _uiState.value = _uiState.value.copy(
                    isServerRunning = false,
                    requestCount = 0,
                    uptimeSeconds = 0,
                    isServerBusy = false
                )
            }
        }

        serviceConnection = connection
        try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to server service", e)
        }
    }

    private fun unbindFromService() {
        serviceStatusJob?.cancel()
        serviceStatusJob = null

        val ctx = boundContext
        serviceConnection?.let { connection ->
            if (ctx != null) {
                try {
                    ctx.unbindService(connection)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Service was not bound", e)
                }
            }
        }

        boundContext = null
        serviceConnection = null
        boundService = null
        serviceLogsSnapshot = emptyList()
    }

    private fun observeServiceFlows(service: LlmServerService) {
        serviceStatusJob?.cancel()
        serviceStatusJob = viewModelScope.launch {
            combine(
                service.isRunning,
                service.requestCount,
                service.uptimeSeconds,
                service.isBusy,
                service.ipAddress,
                service.logs,
            ) { values ->
                val isRunning = values[0] as Boolean
                val requestCount = values[1] as Int
                val uptimeSeconds = values[2] as Long
                val isBusy = values[3] as Boolean
                val ipAddress = values[4] as String?
                val logs = values[5] as List<ServerLog>

                ServiceStatus(
                    isRunning = isRunning,
                    requestCount = requestCount,
                    uptimeSeconds = uptimeSeconds,
                    isBusy = isBusy,
                    ipAddress = ipAddress,
                    logs = logs,
                    port = service.port.value,
                )
            }.collect { updateFromServiceStatus(it) }
        }
    }

    private fun updateFromServiceStatus(status: ServiceStatus) {
        serviceLogsSnapshot = status.logs
        _uiState.value = _uiState.value.copy(
            isServerRunning = status.isRunning,
            requestCount = status.requestCount,
            uptimeSeconds = status.uptimeSeconds,
            isServerBusy = status.isBusy,
            ipAddress = status.ipAddress,
            port = status.port,
            logs = combinedLogs()
        )
    }

    private fun addLog(log: ServerLog) {
        manualLogs.add(0, log)
        if (manualLogs.size > MAX_LOGS) {
            manualLogs.removeLast()
        }
        refreshLogState()
    }

    private fun refreshLogState() {
        _uiState.value = _uiState.value.copy(logs = combinedLogs())
    }

    private fun combinedLogs(): List<ServerLog> {
        val combined = mutableListOf<ServerLog>()
        combined.addAll(manualLogs)
        combined.addAll(serviceLogsSnapshot)
        if (combined.size > MAX_LOGS) {
            combined.subList(MAX_LOGS, combined.size).clear()
        }
        return combined.toList()
    }

    private fun initializeModel(context: Context, model: Model) {
        LlmChatModelHelper.initialize(
            context = context,
            model = model,
            supportImage = true,
            supportAudio = true,
            onDone = { error ->
                if (error.isNotEmpty()) {
                    throw Exception("Model initialization failed: $error")
                }
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        unbindFromService()
        Log.d(TAG, "ViewModel cleared, service connection released")
    }

    private data class ServiceStatus(
        val isRunning: Boolean,
        val requestCount: Int,
        val uptimeSeconds: Long,
        val isBusy: Boolean,
        val ipAddress: String?,
        val logs: List<ServerLog>,
        val port: Int,
    )
}
