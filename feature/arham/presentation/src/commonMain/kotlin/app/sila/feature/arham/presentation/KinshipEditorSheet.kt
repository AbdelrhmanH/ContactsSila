package app.sila.feature.arham.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.sila.core.designsystem.ActionSheetItem
import app.sila.core.designsystem.SilaActionSheet
import app.sila.core.designsystem.SilaDimens
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.Relation
import app.sila.feature.arham.domain.UpdateKinshipUseCase

/**
 * ورقة تحرير القرابة (T8-11 · AC-1 · Issue #29) — حوارٌ بنمط `SilaActionSheet`
 * (السطح المطوي نفسه): الخطوة 1 القرابة (مصفاة بالطبقة عند «+» صف التوليدة)،
 * الخطوة 2 الوتيرة مع **الاقتراح الموثق** (AC-1 — الاختيار إلزامي بلا اقتراح للقرابات
 * غير المذكورة)، ثم الربط بقريبٍ آخر (حافة الشجرة AC-13) والإيقاف المؤقت (الخيار
 * الموثق الوحيد: 30 يوماً). لا منطق أعمال: الحفظ نيةُ [ArhamIntent.SaveKinship]
 * والافتراضيات من `UpdateKinshipUseCase.defaultFrequencyFor` حصراً.
 */
@Composable
fun KinshipEditorSheet(
    editor: KinshipEditorUi,
    parentCandidates: List<Pair<Long, String>>,
    strings: ArhamStrings,
    onIntent: (ArhamIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedRelation by remember(editor.contactId) {
        mutableStateOf(editor.preselectedRelation ?: editor.currentRelation)
    }
    var selectedFrequency by remember(editor.contactId) {
        mutableStateOf(editor.currentFrequency ?: editor.proposedFrequency)
    }
    var selectedParentId by remember(editor.contactId) { mutableStateOf(editor.currentParentContactId) }
    var pauseOneMonth by remember(editor.contactId) { mutableStateOf(false) }
    var showParentPicker by remember(editor.contactId) { mutableStateOf(false) }

    Dialog(onDismissRequest = { onIntent(ArhamIntent.DismissKinshipEditor) }) {
        Surface(
            shape = RoundedCornerShape(SilaDimens.SheetCornerRadius),
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(SilaDimens.Space20)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SilaDimens.Space12),
            ) {
                Text(
                    text = strings.kinshipTitle(editor.contactName),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )

                // الخطوة 1: القرابة — مصفاة بالطبقة عند الإضافة من «+» (restrictToLayer).
                Text(
                    text = strings.kinshipStepRelation,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val allowedRelations: List<Relation> = editor.restrictToLayer
                    ?.let { layer -> Relation.entries.filter { it.layer == layer } }
                    ?: Relation.entries
                allowedRelations.forEach { relation ->
                    SelectionRow(
                        label = strings.relationLabel(relation),
                        selected = selectedRelation == relation,
                        onClick = {
                            selectedRelation = relation
                            // اقتراح الوتيرة الموثق للقرابة الجديدة — لا إجبار (AC-1).
                            if (selectedFrequency == null || selectedFrequency == editor.proposedFrequency) {
                                selectedFrequency = UpdateKinshipUseCase.defaultFrequencyFor(relation)
                            }
                        },
                    )
                }

                // الخطوة 2: الوتيرة — الإلزام موثق (بلا وتيرة لا حفظ).
                Text(
                    text = strings.kinshipStepFrequency,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ArhamFrequency.entries.forEach { frequency ->
                    SelectionRow(
                        label = strings.frequencyLabel(frequency),
                        selected = selectedFrequency == frequency,
                        onClick = { selectedFrequency = frequency },
                    )
                }
                if (selectedFrequency == null) {
                    Text(
                        text = strings.kinshipFrequencyRequired,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // الربط بقريب آخر (حافة الشجرة — AC-13) — عبر منقيٍ منبثق موحد.
                val parentName = selectedParentId
                    ?.let { parentId -> parentCandidates.firstOrNull { it.first == parentId }?.second }
                SelectionRow(
                    label = strings.kinshipParentLabel,
                    selected = false,
                    onClick = { showParentPicker = true },
                    trailing = parentName ?: strings.kinshipParentNone,
                )

                // الإيقاف المؤقت — الخيار الموثق الوحيد: 30 يوماً (O-5).
                SelectionRow(
                    label = strings.kinshipPauseLabel,
                    selected = pauseOneMonth,
                    onClick = { pauseOneMonth = !pauseOneMonth },
                    trailing = if (pauseOneMonth) strings.kinshipPauseOneMonth else null,
                )

                // الحفظ — مفعلٌ فقط بقرابة ووتيرة (الإلزام الموثق).
                val canSave = selectedRelation != null && selectedFrequency != null
                Surface(
                    shape = RoundedCornerShape(SilaDimens.MinTouchTarget / 2),
                    color = if (canSave) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = strings.kinshipSave,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (canSave) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .clickable(enabled = canSave) {
                                onIntent(
                                    ArhamIntent.SaveKinship(
                                        contactId = editor.contactId,
                                        relation = selectedRelation!!,
                                        frequency = selectedFrequency,
                                        parentContactId = selectedParentId,
                                        pauseForDays = if (pauseOneMonth) 30 else null,
                                    ),
                                )
                            }
                            .padding(horizontal = SilaDimens.Space24, vertical = 12.dp),
                    )
                }

                Text(
                    text = strings.actionCancel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { onIntent(ArhamIntent.DismissKinshipEditor) }
                        .padding(vertical = SilaDimens.Space8),
                )
            }
        }
    }

    if (showParentPicker) {
        SilaActionSheet(
            title = strings.kinshipParentLabel,
            items = buildList {
                add(
                    ActionSheetItem(
                        iconEmoji = "∅",
                        title = strings.kinshipParentNone,
                        selected = selectedParentId == null,
                        onClick = {
                            selectedParentId = null
                            showParentPicker = false
                        },
                    ),
                )
                parentCandidates.forEach { (contactId, displayName) ->
                    add(
                        ActionSheetItem(
                            iconEmoji = "👤",
                            title = displayName,
                            selected = selectedParentId == contactId,
                            onClick = {
                                selectedParentId = contactId
                                showParentPicker = false
                            },
                        ),
                    )
                }
            },
            cancelLabel = strings.actionCancel,
            onDismiss = { showParentPicker = false },
        )
    }
}

/** صف اختيار موحد — حبة منتقاة بلون الطبقة الدلالية (داكن/فاتح تلقائياً). */
@Composable
private fun SelectionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    Surface(
        shape = RoundedCornerShape(SilaDimens.MinTouchTarget / 2),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = SilaDimens.Space16, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            trailing?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
