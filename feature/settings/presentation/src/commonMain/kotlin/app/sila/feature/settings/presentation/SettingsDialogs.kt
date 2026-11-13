package app.sila.feature.settings.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.sila.core.designsystem.SilaDimens
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.feature.settings.domain.BackupErrorKind
import app.sila.feature.settings.domain.BackupPreview
import app.sila.feature.settings.domain.RestoreMode
import app.sila.feature.settings.domain.RestoreResult
import app.sila.feature.settings.domain.RingtoneItem
import app.sila.feature.settings.domain.UnmatchedBackupEntry

/**
 * حوارات النسخ الاحتياطي والاستعادة المستخرجة (S2-17 — تذكرة #67).
 *
 * المضيفان [SettingsRestorePasswordDialogs] و[SettingsRestoreOutcomeDialogs] يتألّفان
 * دائماً (ليسا داخل `if`) بمطابقة سلوك الأصل: مسودات كلمات السر ووضع الاستعادة وخيار
 * التفضيلات تعيش `remember` على مستوى المضيف فتُحفظ عبر إغلاق الحوار وإعادة فتحه دون
 * مساس الشاشة، وكل حوار وحدة مستقلة ≤ 60 سطراً (بلا خداع LongMethod).
 */

/**
 * قرار بايتات الاستعادة الموحّد (DEF-04 · مسار SAF):
 *
 * دالة نقّي تُستدعى حرفياً في الموضعين المتطابقين — «البيانات المحمّلة» التي يعرضها
 * حوار الاستيراد، و«البايتات المُرسَلة» إلى [SettingsIntent.ConfirmRestore] عند
 * التأكيد — فلا يمكن أن تُستعاد نسخة غير التي عاينها المستخدم. الأولوية لأحدث
 * اختيار للمستخدم: بايتات ملف الجهاز المختارة عبر SAF ([SettingsUiState.importBackupBytes]
 * عبر نية `SetImportBackupBytes`) أولاً، ثم ذاكرة جلسة التصدير [restoreBytesBuffer]،
 * ثم آخر نسخة مُصدَّرة [lastExportedBytes].
 *
 * العطل المصحَّح: `restoreReady` و`onRestore` كانا يأخذان المصدرين الأخيرين فقط،
 * فمسار «اختيار ملف من الجهاز» يموت عند المعاينة (زر التأكيد معطّل دائماً)، ولو
 * مُكِّن لَما أرسل البايتات المعاينة نفسها عند تعايش المصادر.
 */
internal fun resolvedRestoreBytes(
    state: SettingsUiState,
    restoreBytesBuffer: ByteArray?,
    lastExportedBytes: ByteArray?,
): ByteArray? = state.importBackupBytes ?: restoreBytesBuffer ?: lastExportedBytes

/**
 * مضيف حوارات كلمة السر: التصدير والاستيراد ومعاينة الاستعادة. يملك مسودات الإدخال
 * الثلاثة ووضع الاستعادة وخيار استعادة التفضيلات، فيمرّرهما للحوارات الفردية
 * بمعاملاتها الحرفية نفسها.
 */
@Composable
internal fun SettingsRestorePasswordDialogs(
    state: SettingsUiState,
    strings: SettingsStrings,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit,
    lastExportedBytes: ByteArray?,
    restoreBytesBuffer: ByteArray?,
    onPickBackupFile: (() -> Unit)?
) {
    var exportPasswordInput by remember { mutableStateOf("") }
    var restorePasswordInput by remember { mutableStateOf("") }
    var restoreMode by remember { mutableStateOf(RestoreMode.MERGE) }
    var restorePreferencesCheckbox by remember { mutableStateOf(false) }

    // حوار تصدير النسخة الاحتياطية المشفرة
    if (state.showExportPasswordDialog) {
        ExportBackupDialog(
            state = state,
            strings = strings,
            password = exportPasswordInput,
            onPasswordChange = { exportPasswordInput = it },
            onIntent = onIntent
        )
    }

    // حوار استيراد النسخة الاحتياطية ومعاينتها
    if (state.showImportPasswordDialog) {
        ImportBackupDialog(
            state = state,
            strings = strings,
            isArabic = isArabic,
            loadedBytes = resolvedRestoreBytes(state, restoreBytesBuffer, lastExportedBytes),
            password = restorePasswordInput,
            onPasswordChange = { restorePasswordInput = it },
            onPickBackupFile = onPickBackupFile,
            onIntent = onIntent
        )
    }

    // حوار معاينة النسخة الاحتياطية وتأكيد الاستعادة
    state.backupPreview?.let { preview ->
        if (state.showRestorePreviewDialog) {
            // DEF-04: زر التأكيد يُحكَم بالبايتات المعاينة نفسها (القرار الموحّد) —
            // مصدر SAF كان أعمى عنه `restoreReady` سلفاً فيموت مسار الاستعادة الملفي
            // عند المعاينة، وكان `onRestore` قد يرسل بايتات غير المُعاينة عند تعايش المصادر.
            val previewedBytes = resolvedRestoreBytes(state, restoreBytesBuffer, lastExportedBytes)
            RestorePreviewDialog(
                preview = preview,
                strings = strings,
                isArabic = isArabic,
                restoreMode = restoreMode,
                onRestoreModeChange = { restoreMode = it },
                restorePreferences = restorePreferencesCheckbox,
                onRestorePreferencesChange = { restorePreferencesCheckbox = it },
                restoreReady = previewedBytes != null && !state.isImportingBackup,
                onRestore = {
                    if (previewedBytes != null) {
                        onIntent(
                            SettingsIntent.ConfirmRestore(
                                cipherBytes = previewedBytes,
                                password = restorePasswordInput,
                                mode = restoreMode,
                                restorePreferences = restorePreferencesCheckbox
                            )
                        )
                    }
                },
                onDismiss = { onIntent(SettingsIntent.DismissRestorePreviewDialog) }
            )
        }
    }
}

/** مضيف نتائج الاستعادة: حوار النتيجة + حوار الربط اليدوي (S1-15 — تذكرة #62). */
@Composable
internal fun SettingsRestoreOutcomeDialogs(
    state: SettingsUiState,
    strings: SettingsStrings,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit
) {
    // حوار نتائج الاستعادة مع قائمة السجلات غير المطابقة وربطها اليدوي (S1-15 — تذكرة #62)
    if (state.showRestoreResultDialog) {
        RestoreResultDialog(state = state, strings = strings, isArabic = isArabic, onIntent = onIntent)
    }

    // حوار الربط اليدوي لسجل غير مطابق مع جهة من دفتر العناوين (S1-15 — تذكرة #62)
    state.linkEntry?.let { entry ->
        LinkEntryDialog(
            entry = entry,
            linkQuery = state.linkQuery,
            linkFailed = state.linkFailed,
            linkCandidates = state.linkCandidates,
            strings = strings,
            isArabic = isArabic,
            onIntent = onIntent
        )
    }
}

/** حوار تصدير النسخة الاحتياطية المشفرة (كلمة السر + حالة الخطأ + زر التشفير). */
@Composable
private fun ExportBackupDialog(
    state: SettingsUiState,
    strings: SettingsStrings,
    password: String,
    onPasswordChange: (String) -> Unit,
    onIntent: (SettingsIntent) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onIntent(SettingsIntent.DismissExportDialog) },
        title = { Text(strings.exportDialogTitle) },
        text = {
            Column {
                Text(strings.exportDialogDesc)
                Spacer(modifier = Modifier.height(SilaDimens.Space8))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(strings.passwordLabel) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                state.backupErrorKind?.let { kind ->
                    Spacer(modifier = Modifier.height(SilaDimens.Space4))
                    Text(text = strings.backupError(kind), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (password.isNotBlank()) {
                        onIntent(SettingsIntent.ExportBackup(password))
                    }
                },
                enabled = password.isNotBlank() && !state.isExportingBackup
            ) {
                Text(if (state.isExportingBackup) strings.encrypting else strings.exportButton)
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(SettingsIntent.DismissExportDialog) }) {
                Text(strings.cancel)
            }
        }
    )
}

/** حوار استيراد/استعادة النسخة: حالة البايتات المحمّلة + حقل كلمة السر + زر المعاينة. */
@Composable
private fun ImportBackupDialog(
    state: SettingsUiState,
    strings: SettingsStrings,
    isArabic: Boolean,
    loadedBytes: ByteArray?,
    password: String,
    onPasswordChange: (String) -> Unit,
    onPickBackupFile: (() -> Unit)?,
    onIntent: (SettingsIntent) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onIntent(SettingsIntent.DismissImportDialog) },
        title = { Text(strings.restoreDialogTitle) },
        text = {
            ImportBackupDialogBody(
                strings = strings,
                isArabic = isArabic,
                loadedBytes = loadedBytes,
                onPickBackupFile = onPickBackupFile,
                password = password,
                onPasswordChange = onPasswordChange,
                backupErrorKind = state.backupErrorKind
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (password.isNotBlank() && loadedBytes != null) {
                        onIntent(SettingsIntent.PreviewBackupFile(loadedBytes, password))
                    }
                },
                enabled = password.isNotBlank() && loadedBytes != null && !state.isImportingBackup
            ) {
                Text(if (state.isImportingBackup) strings.verifying else strings.previewButton)
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(SettingsIntent.DismissImportDialog) }) {
                Text(strings.cancel)
            }
        }
    )
}

/** جسد حوار الاستيراد: وصف العملية وحالة البيانات المحمّلة وحقل كلمة السر وخطأ النسخ. */
@Composable
private fun ImportBackupDialogBody(
    strings: SettingsStrings,
    isArabic: Boolean,
    loadedBytes: ByteArray?,
    onPickBackupFile: (() -> Unit)?,
    password: String,
    onPasswordChange: (String) -> Unit,
    backupErrorKind: BackupErrorKind?
) {
    Column {
        Text(strings.restoreDialogDesc)
        Spacer(modifier = Modifier.height(SilaDimens.Space8))
        if (loadedBytes != null) {
            Text(
                text = if (isArabic) "✓ تم تحميل بيانات النسخة (${loadedBytes.size} بايت)"
                else "✓ Backup data loaded (${loadedBytes.size} bytes)",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(SilaDimens.Space4))
        } else {
            Text(
                text = if (isArabic) "قم بتصدير نسخة أولاً أو اختيار ملف احتياطي" else "Export a backup first or load a backup file",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(SilaDimens.Space4))
        }
        if (onPickBackupFile != null) {
            TextButton(onClick = { onPickBackupFile.invoke() }) {
                Text(strings.chooseBackupFile)
            }
        }
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(strings.passwordLabel) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        backupErrorKind?.let { kind ->
            Spacer(modifier = Modifier.height(SilaDimens.Space4))
            Text(text = strings.backupError(kind), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** حوار معاينة النسخة المشفَّرة وتأكيد الاستعادة (موجز المحتوى + وضع الاستعادة + التفضيلات). */
@Composable
private fun RestorePreviewDialog(
    preview: BackupPreview,
    strings: SettingsStrings,
    isArabic: Boolean,
    restoreMode: RestoreMode,
    onRestoreModeChange: (RestoreMode) -> Unit,
    restorePreferences: Boolean,
    onRestorePreferencesChange: (Boolean) -> Unit,
    restoreReady: Boolean,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.previewDialogTitle) },
        text = {
            RestorePreviewBody(
                preview = preview,
                strings = strings,
                restoreMode = restoreMode,
                onRestoreModeChange = onRestoreModeChange,
                restorePreferences = restorePreferences,
                onRestorePreferencesChange = onRestorePreferencesChange
            )
        },
        confirmButton = {
            Button(
                onClick = onRestore,
                enabled = restoreReady
            ) {
                Text(strings.startRestore)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        }
    )
}

/**
 * جسد معاينة الاستعادة: فقرة التحقق الناجحة واختيار الوضع وخيار استعادة التفضيلات.
 * السطور كلها من [SettingsStrings] — لا نص مضمن (S1-5 — تذكرة #61: صف سجل
 * النزاعات أُضيف بترجمة، والمضمنات السابقة على الملف نفسه انزاحت إلى المفاتيح).
 */
@Composable
private fun RestorePreviewBody(
    preview: BackupPreview,
    strings: SettingsStrings,
    restoreMode: RestoreMode,
    onRestoreModeChange: (RestoreMode) -> Unit,
    restorePreferences: Boolean,
    onRestorePreferencesChange: (Boolean) -> Unit
) {
    Column {
        Text(
            text = listOf(
                strings.previewVerifiedHeader,
                "${strings.previewRowCategories} ${preview.categoriesCount}",
                "${strings.previewRowArham} ${preview.arhamCount}",
                "${strings.previewRowTags} ${preview.tagsCount}",
                "${strings.previewRowRingtones} ${preview.categoryRingtonesCount}",
                "${strings.previewRowDisputes} ${preview.disputesCount}"
            ).joinToString("\n")
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space12))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(SilaDimens.Space12))

        RestoreModeOptionRow(
            title = strings.restoreModeMerge,
            description = strings.restoreModeMergeDesc,
            selected = restoreMode == RestoreMode.MERGE,
            onSelect = { onRestoreModeChange(RestoreMode.MERGE) }
        )

        Spacer(modifier = Modifier.height(SilaDimens.Space8))

        RestoreModeOptionRow(
            title = strings.restoreModeReplace,
            description = strings.restoreModeReplaceDesc,
            selected = restoreMode == RestoreMode.REPLACE,
            onSelect = { onRestoreModeChange(RestoreMode.REPLACE) }
        )

        Spacer(modifier = Modifier.height(SilaDimens.Space12))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onRestorePreferencesChange(!restorePreferences) }
        ) {
            Checkbox(
                checked = restorePreferences,
                onCheckedChange = { onRestorePreferencesChange(it) }
            )
            Spacer(modifier = Modifier.width(SilaDimens.Space8))
            Text(strings.restorePrefsCheckbox)
        }
    }
}

/** صف خيار وضع الاستعادة (دمج/استبدال) — زر راديو بعنوان غامق ووصف مصغّر، والصف كله قابل للنقر. */
@Composable
private fun RestoreModeOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        RadioButton(
            selected = selected,
            onClick = { onSelect() }
        )
        Spacer(modifier = Modifier.width(SilaDimens.Space8))
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** حوار نتيجة الاستعادة: موجز المطابقات وقائمة السجلات غير المطابقة (S1-15 — تذكرة #62). */
@Composable
private fun RestoreResultDialog(
    state: SettingsUiState,
    strings: SettingsStrings,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onIntent(SettingsIntent.DismissRestoreResultDialog) },
        title = { Text(strings.restoreResultTitle) },
        text = {
            RestoreResultBody(
                result = state.restoreResult,
                unmatchedLinkedCount = state.unmatchedLinkedCount,
                unmatchedRemaining = state.unmatchedRemaining,
                strings = strings,
                isArabic = isArabic,
                onIntent = onIntent
            )
        },
        confirmButton = {
            TextButton(onClick = { onIntent(SettingsIntent.DismissRestoreResultDialog) }) {
                Text(strings.resultDone)
            }
        }
    )
}

/** جسد نتيجة الاستعادة: عدّادات المطابقة ثم قائمة غير المطابقة أو رسالة الاكتمال. */
@Composable
private fun RestoreResultBody(
    result: RestoreResult?,
    unmatchedLinkedCount: Int,
    unmatchedRemaining: List<UnmatchedBackupEntry>,
    strings: SettingsStrings,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit
) {
    Column {
        if (result != null) {
            Text(
                text = if (isArabic)
                    "• تصنيفات مطابقة: ${result.matchedCategories} " +
                        "(غير مطابق: ${result.unmatchedCategories})\n" +
                        "• أرحام مطابقة: ${result.matchedArham} (غير مطابق: ${result.unmatchedArham})"
                else
                    "• Matched categories: ${result.matchedCategories} " +
                        "(unmatched: ${result.unmatchedCategories})\n" +
                        "• Matched kinship: ${result.matchedArham} (unmatched: ${result.unmatchedArham})"
            )
        }
        if (unmatchedLinkedCount > 0) {
            Text(
                text = if (isArabic) "• رُبط يدوياً: $unmatchedLinkedCount"
                else "• Manually linked: $unmatchedLinkedCount",
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(SilaDimens.Space12))
        if (unmatchedRemaining.isEmpty()) {
            Text(
                text = strings.linkAllDone,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                text = if (isArabic)
                    "لم يُطابق ${unmatchedRemaining.size} سجل — اربطها يدوياً بجهات موجودة:"
                else
                    "${unmatchedRemaining.size} entries not matched — link them manually:",
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(SilaDimens.Space4))
            UnmatchedEntriesList(
                entries = unmatchedRemaining,
                strings = strings,
                isArabic = isArabic,
                onIntent = onIntent
            )
        }
    }
}

/** قائمة السجلات غير المطابقة مع زر «ربط يدوي» لكل صف (يقود حوار الربط اليدوي). */
@Composable
private fun UnmatchedEntriesList(
    entries: List<UnmatchedBackupEntry>,
    strings: SettingsStrings,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        items(entries) { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = entry.displayName, fontWeight = FontWeight.Bold)
                    Text(
                        text = formatUnmatchedLabel(entry, isArabic),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onIntent(SettingsIntent.OpenLinkEntry(entry)) }) {
                    Text(strings.linkManualButton)
                }
            }
        }
    }
}

/** حوار الربط اليدوي: بحث بجهة موجودة ثم ربط السجل غير المطابق بجهة محددة (S1-15 — تذكرة #62). */
@Composable
private fun LinkEntryDialog(
    entry: UnmatchedBackupEntry,
    linkQuery: String,
    linkFailed: Boolean,
    linkCandidates: List<Contact>,
    strings: SettingsStrings,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onIntent(SettingsIntent.DismissLinkEntry) },
        title = {
            Text(
                text = if (isArabic) "ربط «${entry.displayName}» بجهة"
                else "Link \"${entry.displayName}\" to a contact"
            )
        },
        text = {
            LinkEntryDialogBody(
                entryLabel = formatUnmatchedLabel(entry, isArabic),
                linkQuery = linkQuery,
                linkFailed = linkFailed,
                linkCandidates = linkCandidates,
                strings = strings,
                onIntent = onIntent
            )
        },
        confirmButton = {
            TextButton(onClick = { onIntent(SettingsIntent.DismissLinkEntry) }) {
                Text(strings.cancel)
            }
        }
    )
}

/** جسد الربط اليدوي: تسمية السجل + اقتراح البحث + قائمة الجهات المرشّحة عند حرفين فأكثر. */
@Composable
private fun LinkEntryDialogBody(
    entryLabel: String,
    linkQuery: String,
    linkFailed: Boolean,
    linkCandidates: List<Contact>,
    strings: SettingsStrings,
    onIntent: (SettingsIntent) -> Unit
) {
    Column {
        Text(
            text = entryLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space8))
        OutlinedTextField(
            value = linkQuery,
            onValueChange = { onIntent(SettingsIntent.SetLinkQuery(it)) },
            label = { Text(strings.linkSearchHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space4))
        if (linkFailed) {
            Text(text = strings.linkFailedLabel, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(SilaDimens.Space4))
        }
        if (linkQuery.trim().length >= 2) {
            if (linkCandidates.isEmpty()) {
                Text(
                    text = strings.linkNoCandidates,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    items(linkCandidates) { candidate ->
                        TextButton(
                            onClick = { onIntent(SettingsIntent.LinkUnmatchedEntry(candidate.id)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(candidate.name.displayName)
                                val phones = candidate.phones.take(2)
                                if (phones.isNotEmpty()) {
                                    Text(
                                        text = phones.joinToString(" · ") { it.e164 },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** حوار الساعات الهادئة: شريطا بداية ونهاية الهدوء (يُبدأ من قيمة التفضيل في كل فتح). */
@Composable
internal fun QuietHoursDialog(
    strings: SettingsStrings,
    startHour: Int,
    endHour: Int,
    onSave: (startHour: Int, endHour: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var startH by remember { mutableStateOf(startHour.toFloat()) }
    var endH by remember { mutableStateOf(endHour.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.quietHoursPickTitle) },
        text = {
            Column {
                Text("${strings.quietHoursStart}: ${startH.toInt().toString().padStart(2, '0')}:00")
                Slider(
                    value = startH,
                    onValueChange = { startH = it },
                    valueRange = 0f..23f,
                    steps = 22
                )
                Spacer(modifier = Modifier.height(SilaDimens.Space8))
                Text("${strings.quietHoursEnd}: ${endH.toInt().toString().padStart(2, '0')}:00")
                Slider(
                    value = endH,
                    onValueChange = { endH = it },
                    valueRange = 0f..23f,
                    steps = 22
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(startH.toInt(), endH.toInt()) }) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        }
    )
}

/** حوار اختيار النغمة لتصنيف (قائمة النغمات المتاحة وفراغ صادق + زر إعادة التعيين). */
@Composable
internal fun RingtonePickerDialog(
    strings: SettingsStrings,
    isArabic: Boolean,
    category: Category,
    availableRingtones: List<RingtoneItem>,
    onPick: (uri: String) -> Unit,
    onResetDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("${strings.chooseRingtone}: ${formatCategory(category, isArabic)}")
        },
        text = {
            Column {
                Text(strings.ringtoneExplain)
                Spacer(modifier = Modifier.height(SilaDimens.Space8))

                RingtoneOptionsList(
                    availableRingtones = availableRingtones,
                    unavailableText = strings.noRingtonesAvailable,
                    onPick = onPick
                )

                HorizontalDivider()
                TextButton(
                    onClick = onResetDefault,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(strings.resetDefault)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        }
    )
}

/** قائمة النغمات المتاحة، أو حالة فراغ صادقة إن لم توفّر المنصة أي نغمة. */
@Composable
private fun RingtoneOptionsList(
    availableRingtones: List<RingtoneItem>,
    unavailableText: String,
    onPick: (uri: String) -> Unit
) {
    if (availableRingtones.isNotEmpty()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            items(availableRingtones) { ringtoneItem ->
                TextButton(
                    onClick = { onPick(ringtoneItem.uri) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(ringtoneItem.title)
                }
            }
        }
    } else {
        // حالة فراغ صادقة: لا نغمات من النظام — لا يُخلق URI غير موجود أبداً
        // (مراجع T9 — S1: «نغمة صِلَة الملفقة» كانت تُخزَّن كنغمة عقيمة).
        Text(
            text = unavailableText,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
