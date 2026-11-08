package app.sila.feature.contacts.presentation.duplicates

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
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
import app.sila.core.designsystem.SilaContactRow
import app.sila.core.designsystem.SilaDimens
import app.sila.core.designsystem.SilaUndoBar
import app.sila.feature.contacts.domain.DuplicateConfidence
import app.sila.feature.contacts.domain.DuplicateGroup
import app.sila.feature.contacts.domain.MergePlanner

/**
 * شاشة مراجعة المكررات (T7 · Issue #28 — spec 04 §أ «شاشة المكررات»).
 *
 * Stateful رقيقة: تجمع [DuplicatesReviewUiState] وتفوض العرض إلى [DuplicatesReviewContent]
 * العديم الحالة (نمط T3/T6). كل التفاعل عبر [DuplicatesReviewIntent] حصراً — آثار المنصة
 * (خطأ مُترجم Toast) تُجمَع في غلاف التنقل المشترك عبر [DuplicatesReviewEffect]، وفتح
 * عضوٍ/اتصال به قرارات غلاف (onOpenContact/onDial) لا نوايا VM.
 *
 * بطاقة المجموعة: شريحة الثقة + الدليل + أعضاؤها بـ [SilaContactRow]، والتوسيع يكشف
 * معاينة الدمج النقية ([MergePlanner]) قبل الالتزام — «دمج» و«ليست مكررات» فقط هنا.
 */
@Composable
fun DuplicatesReviewScreen(
    viewModel: DuplicatesReviewViewModel,
    strings: DuplicatesReviewStrings,
    modifier: Modifier = Modifier,
    onOpenContact: (Long) -> Unit = {},
    onDial: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    DuplicatesReviewContent(
        state = state,
        strings = strings,
        onIntent = viewModel::onIntent,
        modifier = modifier,
        onOpenContact = onOpenContact,
        onDial = onDial,
    )
}

/** العرض العديم الحالة — كل التفاعل عبر [DuplicatesReviewIntent] حصراً. */
@Composable
fun DuplicatesReviewContent(
    state: DuplicatesReviewUiState,
    strings: DuplicatesReviewStrings,
    onIntent: (DuplicatesReviewIntent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenContact: (Long) -> Unit = {},
    onDial: (String) -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(strings, onIntent)

            when {
                state.isLoading -> CenteredText(strings.loading)
                state.groups.isEmpty() -> EmptyState(strings)
                else -> GroupsList(
                    state = state,
                    strings = strings,
                    onIntent = onIntent,
                    onOpenContact = onOpenContact,
                    onDial = onDial,
                )
            }
        }

        // شريط التراجع (ADR-0013 جلسة الدمج) — فوق المحتوى أسفل الشاشة.
        if (state.isUndoBarVisible) {
            SilaUndoBar(
                message = strings.undoMessage,
                undoLabel = strings.undo,
                onUndo = { onIntent(DuplicatesReviewIntent.UndoMerge) },
                onDismiss = { onIntent(DuplicatesReviewIntent.DismissUndoBar) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun TopBar(strings: DuplicatesReviewStrings, onIntent: (DuplicatesReviewIntent) -> Unit) {
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
                .clickable { onIntent(DuplicatesReviewIntent.Back) },
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
    state: DuplicatesReviewUiState,
    strings: DuplicatesReviewStrings,
    onIntent: (DuplicatesReviewIntent) -> Unit,
    onOpenContact: (Long) -> Unit,
    onDial: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        // «دمج كل المؤكدة» — HIGH حصراً (المواصفة)؛ يختفي بلا مؤكدة.
        if (state.confirmedHighGroups.isNotEmpty()) {
            item(key = "merge-all") {
                TextButton(
                    onClick = { onIntent(DuplicatesReviewIntent.MergeAllConfirmed) },
                    enabled = !state.isMerging,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SilaDimens.Space16, vertical = SilaDimens.Space4),
                ) {
                    Text(
                        text = strings.mergeAllConfirmed(state.confirmedHighGroups.size),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        items(items = state.groups, key = { it.id }) { group ->
            DuplicateGroupCard(
                group = group,
                expanded = state.expandedGroupId == group.id,
                preview = state.previews[group.id],
                isMerging = state.isMerging,
                strings = strings,
                onIntent = onIntent,
                onOpenContact = onOpenContact,
                onDial = onDial,
            )
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    expanded: Boolean,
    preview: MergePlanner.MergePlan?,
    isMerging: Boolean,
    strings: DuplicatesReviewStrings,
    onIntent: (DuplicatesReviewIntent) -> Unit,
    onOpenContact: (Long) -> Unit,
    onDial: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SilaDimens.Space16, vertical = SilaDimens.Space4),
        onClick = { onIntent(DuplicatesReviewIntent.ToggleExpanded(group.id)) },
    ) {
        Column(modifier = Modifier.padding(SilaDimens.Space12)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
            ) {
                ConfidenceChip(group.confidence, strings)
                Text(
                    text = strings.reasonLabel(group.reasonKind, group.reasonDetail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(SilaDimens.Space8))
            Text(
                text = group.contacts.joinToString(" ↔ ") { it.name.displayName },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(SilaDimens.Space8))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                group.contacts.forEach { member ->
                    SilaContactRow(
                        contact = member,
                        categoryLabel = strings.categoryTitle(member.category),
                        callLabel = strings.callMemberLabel,
                        onCallClick = onDial,
                        modifier = Modifier.padding(vertical = 4.dp),
                        onRowClick = { onOpenContact(member.id) },
                    )
                }
                preview?.let { PreviewSection(it, strings) }
                ActionButtons(group.id, isMerging, strings, onIntent)
            }
        }
    }
}

/** شريحة الثقة — «مؤكدة/متوسط/اقتراح» بنبرة متدرجة (HIGH هو ما يُدمج جماعياً فقط). */
@Composable
private fun ConfidenceChip(confidence: DuplicateConfidence, strings: DuplicatesReviewStrings) {
    val (label, color) = when (confidence) {
        DuplicateConfidence.HIGH -> strings.confidenceHigh to MaterialTheme.colorScheme.primary
        DuplicateConfidence.MEDIUM -> strings.confidenceMedium to MaterialTheme.colorScheme.tertiary
        DuplicateConfidence.LOW -> strings.confidenceLow to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = SilaDimens.Space12, vertical = 4.dp),
        )
    }
}

/** معاينة الدمج قبل الالتزام — حتمية [MergePlanner] معروضة (لا حساب في العرض). */
@Composable
private fun PreviewSection(plan: MergePlanner.MergePlan, strings: DuplicatesReviewStrings) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SilaDimens.Space8)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(SilaDimens.Space8),
            )
            .padding(SilaDimens.Space12),
    ) {
        Text(
            text = strings.previewTitle,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = plan.merged.name.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = strings.previewSummary(plan.merged.phones.size, plan.merged.emails.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (plan.merged.notes?.contains(MergePlanner.NOTES_SEPARATOR) == true) {
            Text(
                text = strings.previewNotesMerged,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (plan.ringtoneConflict) {
            Text(
                text = strings.ringtoneConflictNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ActionButtons(
    groupId: String,
    isMerging: Boolean,
    strings: DuplicatesReviewStrings,
    onIntent: (DuplicatesReviewIntent) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SilaDimens.Space8),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = { onIntent(DuplicatesReviewIntent.MarkNotDuplicates(groupId)) },
            enabled = !isMerging,
        ) {
            Text(text = strings.notDuplicatesButton)
        }
        TextButton(
            onClick = { onIntent(DuplicatesReviewIntent.MergeGroup(groupId)) },
            enabled = !isMerging,
        ) {
            Text(
                text = strings.mergeButton,
                fontWeight = FontWeight.Bold,
                color = if (isMerging) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun EmptyState(strings: DuplicatesReviewStrings) {
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
