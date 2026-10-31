package app.sila.feature.contacts.presentation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactSwipeAction
import app.sila.feature.contacts.domain.AccountFilter
import app.sila.feature.contacts.domain.SuggestionCard
import kotlinx.coroutines.launch

/**
 * شاشة جهات الاتصال (Issue #24 · docs/features/02-contacts-list.md).
 *
 * Stateful رقيقة: تجمع [ContactsUiState] وتفوض العرض إلى [ContactsContent] العديم
 * الحالة (نمط T5). آثار المنصة (اتصال/رسالة/مشاركة/إعدادات/منتقي أرقام) تُجمَع
 * في غلاف التطبيق (AndroidApp/T4) عبر `ContactsEffect` — الشاشة لا تحمل أي منطق
 * أعمال ولا تلمس Room/المنصة إطلاقاً (ARCHITECTURE.md §7).
 */
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    strings: ContactsStrings,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    ContactsContent(
        state = state,
        strings = strings,
        onIntent = viewModel::onIntent,
        modifier = modifier
    )
}

/** العرض العديم الحالة — كل التفاعل عبر [ContactsIntent] حصراً (لا منطق أعمال هنا). */
@Composable
fun ContactsContent(
    state: ContactsUiState,
    strings: ContactsStrings,
    onIntent: (ContactsIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showCategorizeMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.isSelectionMode) {
            SelectionBar(
                count = state.selection.size,
                strings = strings,
                showCategorizeMenu = showCategorizeMenu,
                onToggleCategorizeMenu = { showCategorizeMenu = !showCategorizeMenu },
                onCategory = {
                    showCategorizeMenu = false
                    onIntent(ContactsIntent.BulkCategorize(it))
                },
                onFavorite = { onIntent(ContactsIntent.BulkSetFavorite(isFavorite = true)) },
                onUnfavorite = { onIntent(ContactsIntent.BulkSetFavorite(isFavorite = false)) },
                onShare = { onIntent(ContactsIntent.ShareSelection) },
                onDelete = { showDeleteConfirmation = true },
                onDone = { onIntent(ContactsIntent.ClearSelection) }
            )
        } else {
            HeaderRow(state = state, strings = strings, onIntent = onIntent)
        }

        when {
            // رفض الصلاحية يطغى على كل الشاشة — لا قائمة أصلاً بلا مصدر بيانات.
            state.isPermissionDenied -> PermissionDeniedState(strings, onIntent)
            state.hasError && state.contacts.isEmpty() -> ErrorState(strings, onIntent)
            else -> {
                FavoritesRow(state = state, strings = strings, onIntent = onIntent)
                SearchField(state = state, strings = strings, onIntent = onIntent)
                CategoryChipsRow(state = state, strings = strings, onIntent = onIntent)
                SuggestionCards(state = state, strings = strings, onIntent = onIntent)
                ContactsListArea(state = state, strings = strings, onIntent = onIntent)
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(strings.deleteConfirmationTitle) },
            text = { Text(strings.deleteConfirmationMessage(state.selection.size)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onIntent(ContactsIntent.BulkDelete)
                }) { Text(strings.confirmDelete) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text(strings.cancel) }
            }
        )
    }
}

// ===== الرأس وفلتر الحساب =====

@Composable
private fun HeaderRow(state: ContactsUiState, strings: ContactsStrings, onIntent: (ContactsIntent) -> Unit) {
    var showAccountMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = strings.screenTitle,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { showAccountMenu = !showAccountMenu }) {
                Text(text = accountFilterLabel(state.accountFilter, strings))
            }
            DropdownMenu(expanded = showAccountMenu, onDismissRequest = { showAccountMenu = false }) {
                AccountFilter.entries.forEach { filter ->
                    DropdownMenuItem(
                        text = { Text(accountFilterLabel(filter, strings)) },
                        onClick = {
                            showAccountMenu = false
                            onIntent(ContactsIntent.SetAccountFilter(filter))
                        }
                    )
                }
            }
        }
    }
}

private fun accountFilterLabel(filter: AccountFilter, strings: ContactsStrings): String = when (filter) {
    AccountFilter.ALL -> strings.accountFilterAll
    AccountFilter.DEVICE -> strings.accountFilterDevice
    AccountFilter.GOOGLE -> strings.accountFilterGoogle
    AccountFilter.OTHER -> strings.accountFilterOther
}

// ===== صف المفضلة =====

@Composable
private fun FavoritesRow(state: ContactsUiState, strings: ContactsStrings, onIntent: (ContactsIntent) -> Unit) {
    if (state.favorites.isEmpty()) return // معيار القبول: يخفى تماماً بلا مفضلات.

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items = state.favorites, key = { it.id }) { contact ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val isFamily = contact.category == Category.FAMILY
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = if (isFamily) 2.dp else 1.dp,
                            color = if (isFamily) {
                                MaterialTheme.colorScheme.tertiary // حلقة أرحام للعائلة (تصميم 02).
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape
                        )
                        .semantics { contentDescription = contact.name.displayName },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = initialOf(contact), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = contact.name.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(56.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun initialOf(contact: Contact): String =
    contact.name.displayName.trim().firstOrNull()?.toString() ?: "#"

// ===== البحث وشرائح التصنيف =====

@Composable
private fun SearchField(state: ContactsUiState, strings: ContactsStrings, onIntent: (ContactsIntent) -> Unit) {
    OutlinedTextField(
        value = state.searchQuery,
        onValueChange = { query -> onIntent(ContactsIntent.UpdateSearchQuery(query)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(strings.searchPlaceholder) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun CategoryChipsRow(state: ContactsUiState, strings: ContactsStrings, onIntent: (ContactsIntent) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "chip-all") {
            FilterChip(
                selected = state.selectedCategory == null,
                onClick = { onIntent(ContactsIntent.SelectCategory(null)) },
                label = { Text("${strings.categoryAll} ${state.totalContactsCount}") }
            )
        }
        Category.entries.forEach { category ->
            item(key = "chip-${category.name}") {
                FilterChip(
                    selected = state.selectedCategory == category,
                    onClick = { onIntent(ContactsIntent.SelectCategory(category)) },
                    label = { Text("${strings.categoryTitle(category)} ${state.categoryCounts[category] ?: 0}") }
                )
            }
        }
    }
}

// ===== بطاقات الاقتراح القابلة للإغلاق =====

@Composable
private fun SuggestionCards(state: ContactsUiState, strings: ContactsStrings, onIntent: (ContactsIntent) -> Unit) {
    if (state.suggestions.isEmpty()) return

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        state.suggestions.forEach { card ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                onClick = {
                    // "راجعها": بطاقة الفلترة الذكية تنقلك لتصنيف غير المصنّف؛
                    // بطاقة المكررات تنتظر شاشات الدمج (T7) — بلا فعل الآن (موثق في PR).
                    if (card.kind == SuggestionCard.Kind.SMART_FILTER) {
                        onIntent(ContactsIntent.SelectCategory(Category.UNCATEGORIZED))
                    }
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (card.kind) {
                            SuggestionCard.Kind.DUPLICATES -> strings.suggestionDuplicates(card.count)
                            SuggestionCard.Kind.SMART_FILTER -> strings.suggestionSmartFilter(card.count)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onIntent(ContactsIntent.DismissSuggestion(card.id)) }) {
                        Text(strings.suggestionDismiss)
                    }
                }
            }
        }
    }
}

// ===== القائمة المجمعة حرفياً + فهرس جانبي + سحب وتحديد =====

private sealed interface ListItem {
    data class Header(val letter: String) : ListItem
    data class ContactRowItem(val contact: Contact) : ListItem
}

private data class LetterGroup(val letter: String, val startIndex: Int)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactsListArea(state: ContactsUiState, strings: ContactsStrings, onIntent: (ContactsIntent) -> Unit) {
    when {
        state.contacts.isEmpty() && state.isSearchActive -> NoResultsState(strings)
        state.contacts.isEmpty() && !state.isLoading -> EmptyState(strings)
        state.contacts.isEmpty() -> LoadingState(strings)
        else -> GroupedContactList(state = state, strings = strings, onIntent = onIntent)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupedContactList(state: ContactsUiState, strings: ContactsStrings, onIntent: (ContactsIntent) -> Unit) {
    val flatItems = remember(state.contacts) { buildFlatItems(state.contacts) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 88.dp) // مساحة الـ FAB الذكي في الغلاف.
        ) {
            itemsIndexed(
                items = flatItems,
                key = { _, item ->
                    when (item) {
                        is ListItem.Header -> "header-${item.letter}"
                        is ListItem.ContactRowItem -> item.contact.id
                    }
                }
            ) { _, item ->
                when (item) {
                    is ListItem.Header -> Text(
                        text = item.letter,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    is ListItem.ContactRowItem -> ContactRow(
                        contact = item.contact,
                        state = state,
                        strings = strings,
                        onIntent = onIntent
                    )
                }
            }
        }
        LetterIndexBar(
            groups = flatItems.mapIndexedNotNull { index, item ->
                (item as? ListItem.Header)?.let { LetterGroup(it.letter, index) }
            },
            listState = listState,
            scope = scope,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

private fun buildFlatItems(contacts: List<Contact>): List<ListItem> {
    val items = mutableListOf<ListItem>()
    var currentLetter: String? = null
    contacts.forEach { contact ->
        val letter = letterOf(contact)
        if (letter != currentLetter) {
            items += ListItem.Header(letter)
            currentLetter = letter
        }
        items += ListItem.ContactRowItem(contact)
    }
    return items
}

/** الحرف الجماعي: أول حرف من الاسم الظاهر؛ غير الحرفي (أرقام/رموز) ⇒ «#». */
private fun letterOf(contact: Contact): String {
    val first = contact.name.displayName.trim().firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}

/** الفهرس الجانبي — أحرف المجموعات الظاهرة فقط (عربي + لاتيني كما في البيانات). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LetterIndexBar(
    groups: List<LetterGroup>,
    listState: LazyListState,
    scope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier
) {
    val letters = groups.map { it.letter }.distinct()
    if (letters.size < 2) return // فهرس بحرف واحد بلا فائدة.

    Column(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(vertical = 4.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        letters.forEach { letter ->
            Text(
                text = letter,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(vertical = 1.dp)
                    .combinedClickable(onClick = {
                        groups.firstOrNull { it.letter == letter }?.let { group ->
                            scope.launch { listState.scrollToItem(group.startIndex) }
                        }
                    })
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    contact: Contact,
    state: ContactsUiState,
    strings: ContactsStrings,
    onIntent: (ContactsIntent) -> Unit
) {
    val isSelected = contact.id in state.selection

    SwipeToDismissContactRow(
        contact = contact,
        state = state,
        strings = strings,
        onIntent = onIntent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = {
                        if (state.isSelectionMode) {
                            onIntent(ContactsIntent.ToggleSelect(contact.id))
                        } else {
                            onIntent(ContactsIntent.Call(contact.id))
                        }
                    },
                    onLongClick = { onIntent(ContactsIntent.ToggleSelect(contact.id)) }
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics {
                    contentDescription = "${contact.name.displayName} — ${strings.selectContact}"
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onIntent(ContactsIntent.ToggleSelect(contact.id)) }
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = initialOf(contact), style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = strings.categoryTitle(contact.category),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            TextButton(onClick = { onIntent(ContactsIntent.ToggleFavorite(contact.id, !contact.isFavorite)) }) {
                Text(
                    text = if (contact.isFavorite) "♥" else "♡",
                    color = if (contact.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = { onIntent(ContactsIntent.Call(contact.id)) },
                modifier = Modifier.semantics { contentDescription = strings.callRow }
            ) {
                Text(text = "📞")
            }
        }
    }
}

/**
 * سطر قابل للسحب (Issue #24 — "يمين = اتصال، يسار = رسالة، RTL-aware وقابل للتغيير"):
 * دلالة البداية/النهاية منطقية من تفضيلات المستخدم ([ContactsUiState.swipeStartAction]
 * / [swipeEndAction]) وM3 يعكسها تلقائياً في RTL. الفعل يُطلق عند العبور والصف يعود
 * مستقراً (فعل إطلاق لا إزالة).
 */
@Composable
private fun SwipeToDismissContactRow(
    contact: Contact,
    state: ContactsUiState,
    strings: ContactsStrings,
    onIntent: (ContactsIntent) -> Unit,
    row: @Composable () -> Unit
) {
    if (state.isSelectionMode) {
        row() // السحب معطّل في وضع التحديد (يد ينظفان — لا تضارب إيماءات).
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onIntent(ContactsIntent.Swipe(contact.id, state.swipeStartAction))
                    false // فعل عابر — الصف يعيد الاستقرار.
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    onIntent(ContactsIntent.Swipe(contact.id, state.swipeEndAction))
                    false
                }

                SwipeToDismissBoxValue.Settled -> true
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.5f }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val (label, color, alignment) = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(
                    actionLabel(state.swipeStartAction, strings),
                    MaterialTheme.colorScheme.primaryContainer,
                    Alignment.CenterStart
                )

                else -> Triple(
                    actionLabel(state.swipeEndAction, strings),
                    MaterialTheme.colorScheme.secondaryContainer,
                    Alignment.CenterEnd
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment
            ) {
                Text(text = label, style = MaterialTheme.typography.titleMedium)
            }
        }
    ) {
        row()
    }
}

private fun actionLabel(action: ContactSwipeAction, strings: ContactsStrings): String = when (action) {
    ContactSwipeAction.CALL -> strings.swipeCall
    ContactSwipeAction.MESSAGE -> strings.swipeMessage
}

// ===== حالات الفراغ/الخطأ/الصلاحية/التحميل =====

@Composable
private fun LoadingState(strings: ContactsStrings) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(strings: ContactsStrings) {
    StateMessage(title = strings.emptyContactsTitle, body = strings.emptyContactsMessage)
}

@Composable
private fun NoResultsState(strings: ContactsStrings) {
    StateMessage(title = strings.emptySearchTitle, body = strings.emptySearchMessage)
}

@Composable
private fun ErrorState(strings: ContactsStrings, onIntent: (ContactsIntent) -> Unit) {
    StateMessage(
        title = strings.errorTitle,
        body = strings.errorMessage,
        actionLabel = strings.retry,
        onAction = { onIntent(ContactsIntent.Retry) }
    )
}

@Composable
private fun PermissionDeniedState(strings: ContactsStrings, onIntent: (ContactsIntent) -> Unit) {
    StateMessage(
        title = strings.permissionDeniedTitle,
        body = strings.permissionDeniedMessage,
        actionLabel = strings.openSettings,
        onAction = { onIntent(ContactsIntent.OpenSettings) } // الغلاف يجمع الأثر ويفتح الإعدادات.
    )
}

@Composable
private fun StateMessage(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

// ===== شريط التحديد المتعدد =====

@Composable
private fun SelectionBar(
    count: Int,
    strings: ContactsStrings,
    showCategorizeMenu: Boolean,
    onToggleCategorizeMenu: () -> Unit,
    onCategory: (Category) -> Unit,
    onFavorite: () -> Unit,
    onUnfavorite: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = strings.selectionTitle(count),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = onToggleCategorizeMenu) { Text(strings.bulkCategorize) }
            DropdownMenu(expanded = showCategorizeMenu, onDismissRequest = onToggleCategorizeMenu) {
                Category.entries.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(strings.categoryTitle(category)) },
                        onClick = { onCategory(category) }
                    )
                }
            }
        }
        TextButton(onClick = onFavorite) { Text("♥") }
        TextButton(onClick = onUnfavorite) { Text("♡") }
        TextButton(onClick = onShare) { Text(strings.bulkShare) }
        TextButton(onClick = onDelete) { Text(strings.bulkDelete) }
        TextButton(onClick = onDone) { Text(strings.selectionDone) }
    }
}
