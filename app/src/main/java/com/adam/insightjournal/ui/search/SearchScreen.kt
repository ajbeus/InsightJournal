package com.adam.insightjournal.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adam.insightjournal.search.SearchHit
import com.adam.insightjournal.search.SearchOrder
import com.adam.insightjournal.search.Snippet
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    /** A quick note that was never turned into an entry: there is no entry to open, so go to the notes. */
    onOpenQuickNotes: () -> Unit,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val order by viewModel.order.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Open with the keyboard up, but not again when coming back from a result.
    LaunchedEffect(Unit) { if (query.isEmpty()) focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text("Search your journal") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = {
                                    viewModel.onQueryChange("")
                                    focusRequester.requestFocus()
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
            when (val state = results) {
                SearchResults.Idle -> Message(
                    "Find when you wrote about something",
                    "Type a word or a few. Results include your journal entries and quick notes.",
                )
                SearchResults.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SearchResults.Error -> Message("Couldn't search", state.message)
                is SearchResults.Found ->
                    if (state.hits.isEmpty()) {
                        Message("Nothing found", "No entry or note contains \"${query.trim()}\".")
                    } else {
                        ResultList(
                            hits = state.hits,
                            order = order,
                            onOrderChange = viewModel::onOrderChange,
                            onOpen = { hit ->
                                val entryId = if (hit.kind == SearchHit.Kind.ENTRY) hit.id else hit.linkedEntryId
                                if (entryId != null) onOpenEntry(entryId) else onOpenQuickNotes()
                            },
                        )
                    }
            }
        }
    }
}

@Composable
private fun ResultList(
    hits: List<SearchHit>,
    order: SearchOrder,
    onOrderChange: (SearchOrder) -> Unit,
    onOpen: (SearchHit) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    if (hits.size == 1) "1 result" else "${hits.size} results",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = order == SearchOrder.BEST_MATCH,
                    onClick = { onOrderChange(SearchOrder.BEST_MATCH) },
                    label = { Text("Best match") },
                )
                FilterChip(
                    selected = order == SearchOrder.NEWEST,
                    onClick = { onOrderChange(SearchOrder.NEWEST) },
                    label = { Text("Newest") },
                )
            }
        }
        items(hits, key = { "${it.kind}:${it.id}" }) { hit ->
            ResultCard(hit, onClick = { onOpen(hit) })
        }
    }
}

@Composable
private fun ResultCard(hit: SearchHit, onClick: () -> Unit) {
    val highlight = SpanStyle(
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.SemiBold,
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The date leads: "when did I write about this" is the question being answered.
                Text(
                    hit.date.atZone(ZoneId.systemDefault()).format(dateFormat),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (hit.kind == SearchHit.Kind.QUICK_NOTE) {
                    Text(
                        "Quick note",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hit.kind == SearchHit.Kind.ENTRY) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hit.title.text.isBlank()) AnnotatedString("Untitled") else hit.title.highlighted(highlight),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hit.body.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    hit.body.highlighted(highlight),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun Snippet.highlighted(style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    highlights.forEach { range -> addStyle(style, range.first, range.last + 1) }
}

@Composable
private fun Message(title: String, detail: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(32.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
