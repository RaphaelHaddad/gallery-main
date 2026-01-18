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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

class LlmServerTask @Inject constructor() : CustomTask {

    override val task = Task(
        id = "llm_server",
        label = "Start Server",
        category = Category.LLM,
        icon = Icons.Outlined.Dns,
        description = "Transform your phone into a remote LLM backend server. Accept OpenAI-compatible API requests over WiFi for text, images, and audio processing.",
        models = mutableListOf(),
        experimental = false
    )

    override fun initializeModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        onDone: (String) -> Unit
    ) {
        // Use same initialization as LlmChat
        com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper.initialize(
            context = context,
            model = model,
            supportImage = true,
            supportAudio = true,
            onDone = onDone
        )
    }

    override fun cleanUpModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        onDone: () -> Unit
    ) {
        // Use same cleanup as LlmChat
        com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper.cleanUp(
            model = model,
            onDone = onDone
        )
    }

    @Composable
    override fun MainScreen(data: Any) {
        val taskData = data as CustomTaskData
        val viewModel: LlmServerViewModel = hiltViewModel()

        LlmServerScreen(
            viewModel = viewModel,
            modelManagerViewModel = taskData.modelManagerViewModel,
            navigateUp = { /* Navigation handled by CustomTaskScreen wrapper */ }
        )
    }
}
