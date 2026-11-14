package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.ChatViewModel
import com.example.ui.components.ProviderIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToDocuments: () -> Unit
) {
    val selectedDoc by viewModel.selectedDocument.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val fallbackEvent by viewModel.fallbackEvent.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val uiMessage by viewModel.uiMessage.collectAsState()

    var inputPrompt by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiMessage) {
        if (uiMessage != null) {
            snackbarHostState.showSnackbar(uiMessage!!)
            viewModel.clearUiMessage()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(selectedDoc?.title ?: "No PDF Selected")
                        Text(
                            text = if (selectedDoc != null) "${selectedDoc?.fileName} • ${selectedDoc?.pageCount} pages" else "Select a PDF in Library",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onNavigateToDocuments,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Library")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ProviderIndicator(activeProvider = activeProvider, fallbackEvent = fallbackEvent)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputPrompt,
                            onValueChange = { inputPrompt = it },
                            placeholder = { Text("Waydii su'aal ama xisaab (Soomaali, English, العربية)...") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_input"),
                            maxLines = 4
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (inputPrompt.isNotBlank()) {
                                    val q = inputPrompt
                                    inputPrompt = ""
                                    viewModel.sendMessage(q)
                                }
                            },
                            enabled = !isLoading && selectedDoc != null,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (selectedDoc == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Please select a PDF document from your library to start chatting.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onNavigateToDocuments,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Open Document Library")
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            AssistChip(
                                onClick = { viewModel.solveStepByStep() },
                                enabled = !isLoading,
                                label = { Text("Xisaab (Tallaabo-tallaabo)") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Calculate,
                                        contentDescription = "Solve math step by step",
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chip_solve_math")
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { viewModel.explainSubjectSimply() },
                                enabled = !isLoading,
                                label = { Text("Sharaxaad Arday") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.School,
                                        contentDescription = "Explain subject simply",
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chip_explain_subject")
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { viewModel.generatePracticeQuiz() },
                                enabled = !isLoading,
                                label = { Text("Imtixaan / Tijaabo") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Quiz,
                                        contentDescription = "Practice quiz",
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chip_practice_quiz")
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { viewModel.summarizeDocument() },
                                enabled = !isLoading,
                                label = { Text("Soo Koob") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = "Summarize PDF",
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chip_summarize")
                            )
                        }
                        item {
                            AssistChip(
                                onClick = {
                                    viewModel.sendMessage("Compare the main arguments, formulas, or methods in this document and provide key insights.")
                                },
                                enabled = !isLoading,
                                label = { Text("Isbarbardhig") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                                        contentDescription = "Compare methods or arguments",
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chip_compare")
                            )
                        }
                    }

                    if (messages.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ElevatedCard(
                                modifier = Modifier.widthIn(max = 420.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.School,
                                            contentDescription = "Tutor guide",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "Macallinka PDF-ka & Xisaabta",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                    Text(
                                        text = "Waydii su'aal kasta oo ku jirta buuggan ama taabo badhammada sare si laguugu xalliyo xisaabta tallaabo-tallaabo looguna sharaxo ardayga.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(messages) { msg ->
                                val isUser = msg.role == "user"
                                val bubbleColor = if (isUser) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                                val alignment = if (isUser) Alignment.End else Alignment.Start

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = alignment
                                ) {
                                    Surface(
                                        color = bubbleColor,
                                        shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier.widthIn(max = 340.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            SelectionContainer {
                                                Text(
                                                    text = msg.content,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                            if (!msg.providerUsed.isNullOrBlank() && msg.providerUsed != "USER") {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "Via ${msg.providerUsed}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            if (!msg.citation.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = msg.citation,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
