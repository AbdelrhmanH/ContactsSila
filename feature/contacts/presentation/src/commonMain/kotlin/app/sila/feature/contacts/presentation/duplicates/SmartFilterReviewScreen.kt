package app.sila.feature.contacts.presentation.duplicates

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sila.core.designsystem.SilaColors
import app.sila.core.designsystem.SilaDimens
import app.sila.feature.contacts.domain.SmartFilterCategoryGroup
import app.sila.feature.contacts.domain.SmartFilterConfidence
import app.sila.feature.contacts.domain.SmartFilterItem

/**
 * شاشة مراجعة الفلترة الذكية (T7 · Issue #28 — spec 04 §ب): اقتراحات القواعد مجمّعة
 * بالتصنيف، HIGH يُحدد مسبقاً (عقد الـ VM)، والتطبيق **بعد تأكيد صريح حصراً** —
 * كل التفاعل عبر [SmartFilterReviewIntent] حصراً (لا منطق أعمال في العرض).
 *
 * شفافية الدليل: كل بند يعرض ما-طابقه القاعدة ([SmartFilterItem.matchedToken]) عبر
 * موارد الترجمة — المستخدم يرى «لماذا» قبل أن يطبّق.
 */
@Composable
fun SmartFilterReviewScreen(
    viewModel: SmartFilterReviewViewModel,
    strings: SmartFilterReviewStrings,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    SmartFilterReviewContent(
        state = state,
        strings = strings,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

/** العرض العديم الحالة — كل التفاعل عبر [SmartFilterReviewIntent] حصراً. */
@Composable
fun SmartFilterReviewContent(
    state: SmartFilterReviewUiState,
    strings: SmartFilterReviewStrings,
    onIntent: (SmartFilterReviewIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopBar(strings, onIntent)

        when {
            state.isLoading -> CenteredText(strings.loading)
            state.visibleItems.isEmpty() -> EmptyState(strings)
            else -> {
                GroupsList(state, strings, onIntent, Modifier.weight(1f))
                ApplyBar(state, strings, onIntent)
            }
        }
    }
}

@Composable
private fun TopBar(strings: SmartFilterReviewStrings, onIntent: (SmartFilterReviewIntent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SilaDimens.Space8, vertical = SilaDimens.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(SilaDimens.MinTouchTarget)
                .clip(CircleShape)
                .clickable { onIntent(SmartFilterReviewIntent.Back) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "←",
                fontSize = 20.sp,
                modifier = Modifier.semantics { contentDescription = strings.backDescription },
            )
        }
        Spacer(modifier = Modifier.width(SilaDimens.Space8))
        Text(
            text = strings.screenTitle,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun GroupsList(
    state: SmartFilterReviewUiState,
    strings: SmartFilterReviewStrings,
    onIntent: (SmartFilterReviewIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = SilaDimens.Space4),
    ) {
        state.groups.forEach { group ->
            val visible = group.items.filter { it.contactId !in state.rejectedIds }
            if (visible.isEmpty()) return@forEach
            item(key = "header-${group.category.name}") {
                GroupHeader(group, visible.size, strings, onIntent)
            }
            items(items = visible, key = { it.contactId }) { item ->
                SuggestionItemRow(item, state.checkedIds.contains(item.contactId), strings, onIntent)
            }
        }
    }
}

@Composable
private fun GroupHeader(
    group: SmartFilterCategoryGroup,
    visibleCount: Int,
    strings: SmartFilterReviewStrings,
    onIntent: (SmartFilterReviewIntent) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SilaDimens.Space16, vertical = SilaDimens.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.categoryTitle(group.category),
            style = MaterialTheme.typography.labelLarge,
            color = SilaColors.forCategory(group.category),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = strings.groupCount(visibleCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onIntent(SmartFilterReviewIntent.ToggleGroupAll(group.category)) }) {
            Text(text = strings.selectAll, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SuggestionItemRow(
    item: SmartFilterItem,
    checked: Boolean,
    strings: SmartFilterReviewStrings,
    onIntent: (SmartFilterReviewIntent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onIntent(SmartFilterReviewIntent.ToggleItem(item.contactId)) }
            .padding(horizontal = SilaDimens.Space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onIntent(SmartFilterReviewIntent.ToggleItem(item.contactId)) },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8)) {
                    Text(
                        text = strings.evidenceLabel(item.matchedToken),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = confidenceLabel(item.confidence, strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = confidenceColor(item.confidence),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, bottom = SilaDimens.Space4),
            horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
        ) {
            TextButton(onClick = { onIntent(SmartFilterReviewIntent.NotThisCategory(item.contactId)) }) {
                Text(
                    text = strings.notThisCategory,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onIntent(SmartFilterReviewIntent.NeverAsk(item.contactId)) }) {
                Text(
                    text = strings.neverAsk,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    }
}

private fun confidenceLabel(confidence: SmartFilterConfidence, strings: SmartFilterReviewStrings): String =
    when (confidence) {
        SmartFilterConfidence.HIGH -> strings.confidenceHigh
        SmartFilterConfidence.MEDIUM -> strings.confidenceMedium
        SmartFilterConfidence.LOW -> strings.confidenceLow
    }

@Composable
private fun confidenceColor(confidence: SmartFilterConfidence) = when (confidence) {
    SmartFilterConfidence.HIGH -> MaterialTheme.colorScheme.primary
    SmartFilterConfidence.MEDIUM -> MaterialTheme.colorScheme.tertiary
    SmartFilterConfidence.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun ApplyBar(
    state: SmartFilterReviewUiState,
    strings: SmartFilterReviewStrings,
    onIntent: (SmartFilterReviewIntent) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Box(modifier = Modifier.fillMaxWidth().padding(SilaDimens.Space16)) {
            Button(
                onClick = { onIntent(SmartFilterReviewIntent.Apply) },
                enabled = state.canApply,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = strings.applySelected(state.checkedItems.size),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = SilaDimens.Space4),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(strings: SmartFilterReviewStrings) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SilaDimens.Space24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = strings.emptyTitle,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space8))
        Text(
            text = strings.emptyMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(SilaDimens.Space24),
        )
    }
}
