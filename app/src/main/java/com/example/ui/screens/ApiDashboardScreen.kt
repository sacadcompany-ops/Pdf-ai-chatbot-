package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.ProviderConfigEntity
import com.example.ui.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiDashboardScreen(
    viewModel: ChatViewModel
) {
    val providers by viewModel.providers.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val uiMessage by viewModel.uiMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiMessage) {
        if (uiMessage != null) {
            snackbarHostState.showSnackbar(uiMessage!!)
            viewModel.clearUiMessage()
        }
    }

    var editingProvider by remember { mutableStateOf<ProviderConfigEntity?>(null) }
    var tempApiKey by remember { mutableStateOf("") }
    var tempModelId by remember { mutableStateOf("") }
    var tempAccountId by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Multi-API Management Dashboard") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Intelligent Automatic Fallback Engine",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Active Provider: $activeProvider\nPriority Order: 1. Gemini -> 2. Groq -> 3. Cloudflare -> 4. OpenRouter -> 5. AnyRouter.\nAutomatic failover triggers on HTTP 429, 503, timeouts, and network errors.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.testAllConnections() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Auto Check")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Auto-Check All APIs (Hubi Dhammaan)")
                            }
                        }
                    }
                }

                items(providers) { provider ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = provider.providerName,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Switch(
                                    checked = provider.isEnabled,
                                    onCheckedChange = { enabled ->
                                        viewModel.updateProviderConfig(provider.copy(isEnabled = enabled))
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "Model: ${provider.modelId}", style = MaterialTheme.typography.bodyMedium)
                            val isKeySet = viewModel.isKeyConfigured(provider)
                            Text(
                                text = if (isKeySet) "API Key: Configured" else "API Key: Not configured",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isKeySet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            if (provider.providerName == "CLOUDFLARE" && provider.accountId.isNotBlank()) {
                                Text(text = "Account ID: ${provider.accountId}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(text = "Health: ${provider.healthStatus}", style = MaterialTheme.typography.bodySmall)
                            if (!provider.lastError.isNullOrBlank()) {
                                Text(text = "Last Error: ${provider.lastError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        editingProvider = provider
                                        tempApiKey = provider.apiKey
                                        tempModelId = provider.modelId
                                        tempAccountId = provider.accountId
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Configure")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Configure")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.testConnection(provider.providerName) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Test")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Test")
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // Edit dialog
            if (editingProvider != null) {
                val p = editingProvider!!
                AlertDialog(
                    onDismissRequest = { editingProvider = null },
                    title = { Text("Configure ${p.providerName}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = tempApiKey,
                                onValueChange = { tempApiKey = it },
                                label = { Text(if (p.providerName == "CLOUDFLARE") "API Token" else "API Key") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (p.providerName == "CLOUDFLARE") {
                                OutlinedTextField(
                                    value = tempAccountId,
                                    onValueChange = { tempAccountId = it },
                                    label = { Text("Cloudflare Account ID") },
                                    supportingText = { Text("Found in Cloudflare Dashboard under Account ID") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedTextField(
                                value = tempModelId,
                                onValueChange = { tempModelId = it },
                                label = { Text("Model ID") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.updateProviderConfig(
                                    p.copy(apiKey = tempApiKey, modelId = tempModelId, accountId = tempAccountId)
                                )
                                editingProvider = null
                            }
                        ) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { editingProvider = null }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}
