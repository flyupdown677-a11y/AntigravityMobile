package com.antigravity.mobile.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.mobile.data.model.Account
import com.antigravity.mobile.data.model.AgentMessage
import com.antigravity.mobile.data.model.MessageRole
import com.antigravity.mobile.data.model.Project
import com.antigravity.mobile.data.repository.AccountRepository
import com.antigravity.mobile.data.repository.ProjectRepository
import com.antigravity.mobile.engine.AntigravityBridge
import com.antigravity.mobile.engine.EngineEvent
import com.antigravity.mobile.utils.StoragePermissionHelper
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    accountRepository: AccountRepository,
    projectRepository: ProjectRepository,
    bridge: AntigravityBridge?,
    hasStoragePermission: Boolean,
    onRequestStoragePermission: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val activeAccount by accountRepository.activeAccount.collectAsState()
    val activeProject by projectRepository.activeProject.collectAsState()

    var showAccountDialog by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<AgentMessage>() }
    var currentThinking by remember { mutableStateOf<String?>(null) }
    var currentStreamingText by remember { mutableStateOf("") }
    var isAgentBusy by remember { mutableStateOf(false) }

    // Listen to bridge events from Debian container
    LaunchedEffect(bridge) {
        bridge?.engineEvents?.collect { event ->
            when (event) {
                is EngineEvent.TokenDelta -> {
                    isAgentBusy = true
                    currentStreamingText += event.token
                }
                is EngineEvent.ThoughtDelta -> {
                    isAgentBusy = true
                    currentThinking = (currentThinking ?: "") + event.thought
                }
                is EngineEvent.TurnFinished -> {
                    messages.add(
                        AgentMessage(
                            id = System.currentTimeMillis().toString(),
                            role = MessageRole.AGENT,
                            content = event.fullResponse,
                            thinking = currentThinking
                        )
                    )
                    currentStreamingText = ""
                    currentThinking = null
                    isAgentBusy = false
                    coroutineScope.launch {
                        listState.animateScrollToItem(messages.size)
                    }
                }
                is EngineEvent.ErrorOccurred -> {
                    messages.add(
                        AgentMessage(
                            id = System.currentTimeMillis().toString(),
                            role = MessageRole.SYSTEM,
                            content = "⚠️ ${event.message}"
                        )
                    )
                    isAgentBusy = false
                }
                else -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = activeProject?.name ?: "Antigravity Project",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Debian Headless Virtual Environment",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Open in Files button
                    IconButton(onClick = {
                        activeProject?.let {
                            StoragePermissionHelper.openProjectInFileManager(context, File(it.absolutePath))
                        }
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Open Folder in Files")
                    }

                    // Account switch pill
                    ElevatedAssistChip(
                        onClick = { showAccountDialog = true },
                        label = {
                            Text(
                                text = activeAccount?.name ?: "No Account",
                                maxLines = 1
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Storage Permission Warning Banner
            if (!hasStoragePermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Storage Permission Needed",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Grant storage permission so you can open project files directly from your file manager.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onRequestStoragePermission,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Grant")
                        }
                    }
                }
            }

            // Missing API Key Banner
            if (activeAccount == null || activeAccount?.apiKey.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Connect API Key",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Enter your Gemini API key to activate the Antigravity engine.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { showAccountDialog = true }) {
                            Text("Connect")
                        }
                    }
                }
            }

            // Message History
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                if (messages.isEmpty() && currentStreamingText.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Terminal,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Google Antigravity for Android",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Headless Debian runtime • Direct folder access\nAsk to build a website, app, or script in /sdcard/AntigravityProjects",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }

                items(messages) { message ->
                    MessageBubble(message)
                }

                // In-flight streaming response
                if (isAgentBusy) {
                    item {
                        InFlightBubble(thinking = currentThinking, text = currentStreamingText)
                    }
                }
            }

            // Input Row
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("What would you like Antigravity to build?") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank() && activeAccount != null) {
                                val prompt = inputText.trim()
                                messages.add(
                                    AgentMessage(
                                        id = System.currentTimeMillis().toString(),
                                        role = MessageRole.USER,
                                        content = prompt
                                    )
                                )
                                inputText = ""
                                isAgentBusy = true
                                bridge?.sendChatPrompt(prompt)
                            }
                        },
                        enabled = inputText.isNotBlank() && !isAgentBusy && activeAccount != null,
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (inputText.isNotBlank() && !isAgentBusy) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape
                            )
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (inputText.isNotBlank() && !isAgentBusy) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showAccountDialog) {
        AccountDialog(
            accountRepository = accountRepository,
            onDismiss = { showAccountDialog = false }
        )
    }
}

@Composable
fun MessageBubble(message: AgentMessage) {
    val isUser = message.role == MessageRole.USER
    var isThinkingExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(12.dp)
        ) {
            // Thinking Section (Collapsible)
            if (!message.thinking.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                        .clickable { isThinkingExpanded = !isThinkingExpanded }
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isThinkingExpanded) "Hide Reasoning" else "Thought Process",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }

                AnimatedVisibility(visible = isThinkingExpanded) {
                    Text(
                        text = message.thinking,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Text(
                text = message.content,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun InFlightBubble(thinking: String?, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            if (!thinking.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Thinking...",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 3
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Antigravity is working...", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
