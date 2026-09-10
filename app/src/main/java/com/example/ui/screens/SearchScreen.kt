package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.gemini.AISearchResult
import com.example.data.gemini.GeminiService
import com.example.data.model.Memory
import com.example.data.repository.MemoryRepository
import com.example.ui.components.MemoryCard
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.IndigoPrimary
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    userId: String,
    memoryRepository: MemoryRepository,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val allMemories by memoryRepository.getMemoriesFlow(userId).collectAsState(initial = emptyList())

    var queryText by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf<AISearchResult?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun executeSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) {
            searchResult = null
            return
        }

        searchJob?.cancel()
        isSearching = true
        searchJob = scope.launch {
            try {
                val result = GeminiService.searchMemories(q, allMemories)
                searchResult = result
            } catch (e: Exception) {
                // Fallback
            } finally {
                isSearching = false
            }
        }
    }

    // Matching memories list from the search result or query matching
    val matchingMemories = remember(searchResult, allMemories, queryText) {
        val result = searchResult
        if (result != null && result.matchingMemoryIds.isNotEmpty()) {
            allMemories.filter { it.memoryId in result.matchingMemoryIds }
        } else if (queryText.isNotBlank()) {
            val lower = queryText.lowercase()
            allMemories.filter {
                it.title.lowercase().contains(lower) ||
                        it.content.lowercase().contains(lower) ||
                        it.date.lowercase().contains(lower) ||
                        it.detectedPeople.any { p -> p.lowercase().contains(lower) } ||
                        (it.detectedTask?.lowercase()?.contains(lower) == true)
            }
        } else {
            emptyList()
        }
    }

    val sampleSuggestions = listOf(
        "John",
        "proposal",
        "September 10",
        "client meeting",
        "What tasks did I create yesterday?",
        "What did I need to do on Friday?",
        "What ideas did I have this month?"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Search",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Query your memories using natural language",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = IndigoPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Search Bar Input
        item {
            OutlinedTextField(
                value = queryText,
                onValueChange = {
                    queryText = it
                    if (it.isBlank()) {
                        searchResult = null
                    } else {
                        executeSearch(it)
                    }
                },
                placeholder = { Text("Ask or search memories...", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = IndigoPrimary
                    )
                },
                trailingIcon = {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = IndigoPrimary
                        )
                    } else if (queryText.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                queryText = ""
                                searchResult = null
                            },
                            modifier = Modifier.testTag("clear_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = IndigoPrimary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_text_input")
            )
        }

        // Quick Suggestions
        item {
            Column {
                Text(
                    text = "SAMPLE QUERIES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sampleSuggestions.forEach { suggestion ->
                        SuggestionChip(
                            onClick = {
                                queryText = suggestion
                                executeSearch(suggestion)
                            },
                            label = { Text(suggestion, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        // AI Answer Card
        if (searchResult != null && queryText.isNotBlank()) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = IndigoPrimary.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_answer_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = IndigoPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "AI MEMORY SYNTHESIS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = IndigoPrimary,
                                letterSpacing = 1.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = searchResult?.answer ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 22.sp
                        )
                    }
                }
            }
        }

        // Results count label
        if (queryText.isNotBlank()) {
            item {
                Text(
                    text = "${matchingMemories.size} MATCHING ${if (matchingMemories.size == 1) "MEMORY" else "MEMORIES"}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp
                )
            }
        }

        // Matching Memories
        if (matchingMemories.isEmpty() && queryText.isNotBlank() && !isSearching) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No matching memories",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Try different keywords or questions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(matchingMemories, key = { it.memoryId }) { memory ->
                MemoryCard(
                    memory = memory,
                    onDelete = { id ->
                        scope.launch { memoryRepository.deleteMemory(userId, id) }
                    }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
