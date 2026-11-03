package app.sila.feature.contacts.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sila.core.common.PhoneNormalizer
import app.sila.core.designsystem.ActionSheetItem
import app.sila.core.designsystem.SilaActionSheet
import app.sila.core.designsystem.SilaColors
import app.sila.core.designsystem.SilaDimens
import app.sila.core.model.Category

/**
 * شاشة الإضافة/التعديل (T6 · Issue #27 · docs/features/03 §ب).
 *
 * Stateful رقيقة: تجمع [ContactEditUiState] (وبايتات الصورة المنتقاة للمعاينة) وتفوض
 * العرض إلى [ContactEditContent]. كل تعديل عبر [ContactEditIntent] حصراً، وآثار المنصة
 * (منتقي الصور عبر [ContactEditIntent.RequestPhotoCamera]/[ContactEditIntent.RequestPhotoGallery]
 * ← الغلاف، الرجوع، خطأ الحفظ) تُجمَع في غلاف التطبيق — الشاشة لا تحمل منطق أعمال
 * (ARCHITECTURE.md §7). الحد الأدنى للحفظ: اسم **أو** رقم (AC).
 */
@Composable
fun ContactEditScreen(
    viewModel: ContactEditViewModel,
    strings: ContactEditStrings,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val pendingPhotoBytes by viewModel.pendingPhotoBytes.collectAsState()
    ContactEditContent(
        state = state,
        pendingPhotoBytes = pendingPhotoBytes,
        strings = strings,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

/** العرض العديم الحالة — كل التفاعل عبر [ContactEditIntent] حصراً (لا منطق أعمال هنا). */
@Composable
fun ContactEditContent(
    state: ContactEditUiState,
    pendingPhotoBytes: ByteArray?,
    strings: ContactEditStrings,
    onIntent: (ContactEditIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showPhotoSheet by remember { mutableStateOf(false) }

    if (state.isNotFound) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = strings.notFoundMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        EditTopBar(
            state = state,
            strings = strings,
            onIntent = onIntent,
            onClose = {
                // الخروج مع تغييرات غير محفوظة ← تأكيد (AC) — القرار من حالة الاتساخ مباشرة.
                if (state.isDirty) showDiscardDialog = true else onIntent(ContactEditIntent.DiscardChanges)
            },
        )

        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SilaDimens.Space16),
        ) {
            PhotoHeader(
                state = state,
                pendingPhotoBytes = pendingPhotoBytes,
                strings = strings,
                onShowPhotoSheet = { showPhotoSheet = true },
            )
            CategoryChipsRow(state = state, strings = strings, onIntent = onIntent)

            state.validationError?.let { error ->
                Text(
                    text = if (error == ContactEditValidationError.NAME_OR_PHONE_REQUIRED) {
                        strings.validationNameOrPhone
                    } else {
                        strings.saveError
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // الاسم الأساسي (US-03.1 — اسم برقم وتصنيف فقط بسرعة).
            Row(horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space12)) {
                OutlinedTextField(
                    value = state.firstName,
                    onValueChange = { onIntent(ContactEditIntent.SetNameField(ContactEditNameField.FIRST_NAME, it)) },
                    label = { Text(strings.firstNameLabel) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.lastName,
                    onValueChange = { onIntent(ContactEditIntent.SetNameField(ContactEditNameField.LAST_NAME, it)) },
                    label = { Text(strings.lastNameLabel) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

            Spacer(modifier = Modifier.height(SilaDimens.Space12))

            // الهواتف — تطبيع غير مانع + تحذير تكرار (J-9).
            state.phones.forEach { row ->
                PhoneEditRow(row = row, duplicateName = state.duplicateWarning[row.key], strings = strings, onIntent = onIntent)
            }
            AddRowButton(label = strings.addPhone) { onIntent(ContactEditIntent.AddPhone) }

            // «إظهار المزيد» — يتذكر حالته؛ وتُفتح تلقائياً عند وجود بيانات متقدمة (AC).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onIntent(ContactEditIntent.SetExpanded(!state.isExpanded)) }
                    .padding(vertical = SilaDimens.Space12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (state.isExpanded) strings.showLess else strings.showMore,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(text = if (state.isExpanded) " ⌃" else " ⌄", color = MaterialTheme.colorScheme.primary)
            }

            if (state.isExpanded) {
                AdvancedSections(state = state, strings = strings, onIntent = onIntent)
            }

            // تلميح أرحام (J-4) — عائلة بعيد ميلاد سيلُكتب كمناسبة تلقائياً عند الحفظ.
            if (state.category == Category.FAMILY &&
                state.dates.any { it.type == DATE_BIRTHDAY && it.month != null && it.day != null }
            ) {
                Text(
                    text = strings.arhamBirthdayHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = SilaColors.forCategory(Category.FAMILY),
                    modifier = Modifier.padding(vertical = SilaDimens.Space8),
                )
            }

            Spacer(modifier = Modifier.height(SilaDimens.Space24))
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(strings.discardConfirmTitle) },
            text = { Text(strings.discardConfirmMessage) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onIntent(ContactEditIntent.DiscardChanges)
                }) { Text(strings.discard) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text(strings.keepEditing) }
            },
        )
    }

    if (showPhotoSheet) {
        SilaActionSheet(
            items = listOf(
                ActionSheetItem(iconEmoji = "📷", title = strings.photoCamera) {
                    onIntent(ContactEditIntent.RequestPhotoCamera)
                },
                ActionSheetItem(iconEmoji = "🖼️", title = strings.photoGallery) {
                    onIntent(ContactEditIntent.RequestPhotoGallery)
                },
                ActionSheetItem(iconEmoji = "🗑️", title = strings.removePhoto, destructive = true) {
                    onIntent(ContactEditIntent.RemovePhoto)
                },
            ),
            cancelLabel = strings.keepEditing,
            onDismiss = { showPhotoSheet = false },
        )
    }

    // Sheet «صنّف قبل الحفظ» (AC) — تصنيف يحفظ، «لاحقاً» يحفظ UNCATEGORIZED، والإلغاء يبقى للنموذج.
    if (state.showCategoryPrompt) {
        SilaActionSheet(
            title = strings.categoryPromptTitle,
            items = listOf(
                listOf(Category.FAMILY, Category.FRIENDS, Category.WORK, Category.SERVICES, Category.EMERGENCY)
                    .map { category ->
                        ActionSheetItem(iconEmoji = "🏷️", title = strings.categoryTitle(category)) {
                            onIntent(ContactEditIntent.ConfirmCategory(category))
                        }
                    },
                listOf(
                    ActionSheetItem(iconEmoji = "⏭️", title = strings.categoryPromptLater) {
                        onIntent(ContactEditIntent.ConfirmCategory(null))
                    },
                ),
            ).flatten(),
            cancelLabel = strings.keepEditing,
            onDismiss = { onIntent(ContactEditIntent.DismissCategoryPrompt) },
        )
    }
}

// ===== الرأس =====

@Composable
private fun EditTopBar(
    state: ContactEditUiState,
    strings: ContactEditStrings,
    onIntent: (ContactEditIntent) -> Unit,
    onClose: () -> Unit,
) {
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
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✕",
                fontSize = 20.sp,
                modifier = Modifier.semantics { contentDescription = strings.closeDescription },
            )
        }
        Text(
            text = if (state.isNewContact) strings.newContactTitle else strings.editContactTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = SilaDimens.Space8),
            maxLines = 1,
        )
        if (state.isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            TextButton(onClick = { onIntent(ContactEditIntent.Save) }) { Text(strings.save) }
        }
    }
}

/** دائرة الصورة — 📷 إضافة / 🖼️ منتقاة / أحرف أولى (العرض النهائي من photoUri بنمط القائمة). */
@Composable
private fun PhotoHeader(
    state: ContactEditUiState,
    pendingPhotoBytes: ByteArray?,
    strings: ContactEditStrings,
    onShowPhotoSheet: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SilaDimens.Space12),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val categoryColor = SilaColors.forCategory(state.category)
        val hasPending = pendingPhotoBytes != null
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(categoryColor.copy(alpha = 0.15f))
                .clickable(onClick = onShowPhotoSheet),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    hasPending -> "🖼️"
                    state.isPhotoRemoved || state.photoUri == null -> "📷"
                    else -> state.displayNamePreview.take(1).ifBlank { "📷" }
                },
                fontSize = 30.sp,
                color = categoryColor,
            )
        }
        val photoLabel = when {
            hasPending -> strings.changePhoto
            state.isPhotoRemoved -> strings.addPhoto
            state.photoUri != null -> strings.changePhoto
            else -> strings.addPhoto
        }
        Text(
            text = photoLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(SilaDimens.Space8))
                .clickable(onClick = onShowPhotoSheet)
                .padding(SilaDimens.Space8),
        )
    }
}

@Composable
private fun CategoryChipsRow(state: ContactEditUiState, strings: ContactEditStrings, onIntent: (ContactEditIntent) -> Unit) {
    Column {
        Text(
            text = strings.categoryLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space8))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
        ) {
            listOf(Category.FAMILY, Category.FRIENDS, Category.WORK, Category.SERVICES, Category.EMERGENCY)
                .forEach { category ->
                    FilterChip(
                        selected = state.category == category,
                        onClick = { onIntent(ContactEditIntent.SetCategory(category)) },
                        label = { Text(strings.categoryTitle(category)) },
                    )
                }
        }
        Spacer(modifier = Modifier.height(SilaDimens.Space12))
    }
}

// ===== صفوف الهواتف =====

@Composable
private fun PhoneEditRow(
    row: EditablePhoneRow,
    duplicateName: String?,
    strings: ContactEditStrings,
    onIntent: (ContactEditIntent) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
        ) {
            OutlinedTextField(
                value = row.raw,
                onValueChange = { onIntent(ContactEditIntent.UpdatePhone(row.key, it)) },
                label = { Text(strings.phoneLabel) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                isError = row.raw.trim().isNotBlank() && !PhoneNormalizer.isValidPhone(row.raw),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            TypeDropdown(
                current = row.type,
                options = PHONE_TYPE_OPTIONS,
                labelFor = { type -> labelForType(type, strings) },
                customLabel = strings.typeCustom,
                customPlaceholder = strings.customTypePlaceholder,
                onSelect = { onIntent(ContactEditIntent.UpdatePhoneType(row.key, it)) },
            )
            RemoveRowButton(strings.removeRowDescription) { onIntent(ContactEditIntent.RemovePhone(row.key)) }
        }
        // تحذير غير مانع — محفوظ مسبقاً (J-9) أو رقم غير صالح (تحقق بلا منع حفظ — AC).
        when {
            duplicateName != null -> Text(
                text = strings.duplicateWarning(duplicateName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            row.raw.trim().isNotBlank() && !PhoneNormalizer.isValidPhone(row.raw) -> Text(
                text = strings.invalidPhoneWarning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** الأنواع المعروفة للهاتف — ما عداها يُعدّ مخصصاً (AC «نوع لكل عنصر مع مخصص»). */
private val PHONE_TYPE_OPTIONS = listOf("MOBILE", "HOME", "WORK", "OTHER")

/** الأنواع المعروفة للبريد. */
private val EMAIL_TYPE_OPTIONS = listOf("PERSONAL", "HOME", "WORK", "OTHER")

/** خريطة النوع ← نص مترجم (القيم غير المعروفة تمر كما هي — تسميات مخصصة). */
private fun labelForType(type: String, strings: ContactEditStrings): String = when (type) {
    "MOBILE" -> strings.typeMobile
    "HOME" -> strings.typeHome
    "WORK" -> strings.typeWork
    "OTHER", "PERSONAL" -> strings.typeOther
    else -> type
}

/** خريطة نوع التاريخ ← نص مترجم. */
private fun labelForDateType(type: String, strings: ContactEditStrings): String = when (type) {
    "BIRTHDAY" -> strings.dateBirthday
    "ANNIVERSARY" -> strings.dateAnniversary
    else -> strings.dateCustomDate
}

/** منتقي نوع عام — الأنواع المعروفة + «مخصص» (حقل نصي حر يظهر بديلاً عنها). */
@Composable
private fun TypeDropdown(
    current: String,
    options: List<String>,
    labelFor: (String) -> String,
    customLabel: String,
    customPlaceholder: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    if (current in options) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(SilaDimens.Space8))
                .clickable { expanded = true }
                .padding(horizontal = SilaDimens.Space8, vertical = SilaDimens.Space12),
        ) {
            Text(text = labelFor(current), style = MaterialTheme.typography.bodyMedium)
            Text(text = " ▾", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        // تسمية مخصصة — حقل نصي حر يظهر في موضع القائمة.
        OutlinedTextField(
            value = current,
            onValueChange = onSelect,
            placeholder = { Text(customPlaceholder) },
            modifier = Modifier.width(110.dp),
            singleLine = true,
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(labelFor(option)) },
                onClick = {
                    expanded = false
                    onSelect(option)
                },
            )
        }
        DropdownMenuItem(
            text = { Text(customLabel) },
            onClick = {
                expanded = false
                onSelect(customPlaceholder)
            },
        )
    }
}

@Composable
private fun AddRowButton(label: String, onClick: () -> Unit) {
    Text(
        text = "+ $label",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(SilaDimens.Space8))
            .clickable(onClick = onClick)
            .padding(vertical = SilaDimens.Space8),
    )
}

@Composable
private fun RemoveRowButton(description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(SilaDimens.MinTouchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "⊖",
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

// ===== الأقسام المتقدمة =====

@Composable
private fun AdvancedSections(state: ContactEditUiState, strings: ContactEditStrings, onIntent: (ContactEditIntent) -> Unit) {
    Column {
        SectionDivider()
        // الاسم المتقدم — بادئة · أوسط · لاحقة · كنية · حفظ باسم · نطق (التخطيط).
        SimpleField(label = strings.prefixLabel, value = state.prefix) {
            onIntent(ContactEditIntent.SetNameField(ContactEditNameField.PREFIX, it))
        }
        SimpleField(label = strings.middleNameLabel, value = state.middleName) {
            onIntent(ContactEditIntent.SetNameField(ContactEditNameField.MIDDLE_NAME, it))
        }
        SimpleField(label = strings.suffixLabel, value = state.suffix) {
            onIntent(ContactEditIntent.SetNameField(ContactEditNameField.SUFFIX, it))
        }
        SimpleField(label = strings.nicknameLabel, value = state.nickname) {
            onIntent(ContactEditIntent.SetNameField(ContactEditNameField.NICKNAME, it))
        }
        SimpleField(label = strings.fileAsLabel, value = state.fileAs) {
            onIntent(ContactEditIntent.SetNameField(ContactEditNameField.FILE_AS, it))
        }
        SimpleField(label = strings.phoneticLabel, value = state.phoneticName) {
            onIntent(ContactEditIntent.SetNameField(ContactEditNameField.PHONETIC, it))
        }

        SectionDivider()
        // البريد ⊕.
        state.emails.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = row.address,
                    onValueChange = { onIntent(ContactEditIntent.UpdateEmail(row.key, it)) },
                    label = { Text(strings.emailLabel) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                TypeDropdown(
                    current = row.type,
                    options = EMAIL_TYPE_OPTIONS,
                    labelFor = { type -> labelForType(type, strings) },
                    customLabel = strings.typeCustom,
                    customPlaceholder = strings.customTypePlaceholder,
                    onSelect = { onIntent(ContactEditIntent.UpdateEmailType(row.key, it)) },
                )
                RemoveRowButton(strings.removeRowDescription) { onIntent(ContactEditIntent.RemoveEmail(row.key)) }
            }
        }
        AddRowButton(label = strings.addEmail) { onIntent(ContactEditIntent.AddEmail) }

        SectionDivider()
        // المواقع ⊕.
        state.websites.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = row.url,
                    onValueChange = { onIntent(ContactEditIntent.UpdateWebsite(row.key, it)) },
                    label = { Text(strings.websiteLabel) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                RemoveRowButton(strings.removeRowDescription) { onIntent(ContactEditIntent.RemoveWebsite(row.key)) }
            }
        }
        AddRowButton(label = strings.addWebsite) { onIntent(ContactEditIntent.AddWebsite) }

        SectionDivider()
        // الشركة · القسم · المسمى الوظيفي (التخطيط).
        SimpleField(label = strings.companyLabel, value = state.company) {
            onIntent(ContactEditIntent.SetOrganizationField(ContactEditOrgField.COMPANY, it))
        }
        SimpleField(label = strings.departmentLabel, value = state.department) {
            onIntent(ContactEditIntent.SetOrganizationField(ContactEditOrgField.DEPARTMENT, it))
        }
        SimpleField(label = strings.jobTitleLabel, value = state.jobTitle) {
            onIntent(ContactEditIntent.SetOrganizationField(ContactEditOrgField.JOB_TITLE, it))
        }

        SectionDivider()
        // تواريخ هامة ⊕ — نوع + شهر/يوم إلزاميان، السنة اختيارية.
        Text(
            text = strings.datesLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.dates.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8), verticalAlignment = Alignment.CenterVertically) {
                TypeDropdown(
                    current = row.type,
                    options = listOf("BIRTHDAY", "ANNIVERSARY"),
                    labelFor = { type -> labelForDateType(type, strings) },
                    customLabel = strings.dateCustomDate,
                    customPlaceholder = strings.dateCustomDate,
                    onSelect = { type -> onIntent(ContactEditIntent.UpdateDate(row.key, type, row.month, row.day, row.year)) },
                )
                NumberField(label = strings.dateMonth, value = row.month?.toString().orEmpty()) { text ->
                    onIntent(ContactEditIntent.UpdateDate(row.key, row.type, text.toIntOrNull(), row.day, row.year))
                }
                NumberField(label = strings.dateDay, value = row.day?.toString().orEmpty()) { text ->
                    onIntent(ContactEditIntent.UpdateDate(row.key, row.type, row.month, text.toIntOrNull(), row.year))
                }
                NumberField(label = strings.dateYear, value = row.year?.toString().orEmpty()) { text ->
                    onIntent(ContactEditIntent.UpdateDate(row.key, row.type, row.month, row.day, text.toIntOrNull()))
                }
                RemoveRowButton(strings.removeRowDescription) { onIntent(ContactEditIntent.RemoveDate(row.key)) }
            }
        }
        AddRowButton(label = strings.addDate) { onIntent(ContactEditIntent.AddDate) }

        SectionDivider()
        // العنوان ⊕ — خمسة حقول + نوع (تخطيط البريد/العنوان في §أ).
        state.addresses.forEach { row ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8)) {
                    OutlinedTextField(
                        value = row.street,
                        onValueChange = { onIntent(ContactEditIntent.UpdateAddressField(row.key, ContactEditAddressField.STREET, it)) },
                        label = { Text(strings.streetLabel) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    RemoveRowButton(strings.removeRowDescription) { onIntent(ContactEditIntent.RemoveAddress(row.key)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8)) {
                    SimpleField(label = strings.cityLabel, value = row.city) {
                        onIntent(ContactEditIntent.UpdateAddressField(row.key, ContactEditAddressField.CITY, it))
                    }
                    SimpleField(label = strings.regionLabel, value = row.region) {
                        onIntent(ContactEditIntent.UpdateAddressField(row.key, ContactEditAddressField.REGION, it))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8)) {
                    SimpleField(label = strings.postalCodeLabel, value = row.postalCode) {
                        onIntent(ContactEditIntent.UpdateAddressField(row.key, ContactEditAddressField.POSTAL_CODE, it))
                    }
                    SimpleField(label = strings.countryLabel, value = row.country) {
                        onIntent(ContactEditIntent.UpdateAddressField(row.key, ContactEditAddressField.COUNTRY, it))
                    }
                }
            }
        }
        AddRowButton(label = strings.addAddress) { onIntent(ContactEditIntent.AddAddress) }

        SectionDivider()
        // ملاحظات (صِلَة-فقط — Room حصراً، ADR-0012).
        OutlinedTextField(
            value = state.notes,
            onValueChange = { onIntent(ContactEditIntent.SetNotes(it)) },
            label = { Text(strings.notesLabel) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        SectionDivider()
        // حقول مخصصة ⊕.
        state.customFields.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = row.label,
                    onValueChange = { onIntent(ContactEditIntent.UpdateCustomField(row.key, ContactEditCustomSlot.LABEL, it)) },
                    label = { Text(strings.customFieldLabelLabel) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = row.value,
                    onValueChange = { onIntent(ContactEditIntent.UpdateCustomField(row.key, ContactEditCustomSlot.VALUE, it)) },
                    label = { Text(strings.customFieldValueLabel) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                RemoveRowButton(strings.removeRowDescription) { onIntent(ContactEditIntent.RemoveCustomField(row.key)) }
            }
        }
        AddRowButton(label = strings.addCustomField) { onIntent(ContactEditIntent.AddCustomField) }

        SectionDivider()
        // حفظ في: الحساب (قراءة فقط — منتقي الحسابات مؤجل — ADR-0012).
        Text(
            text = strings.accountLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = state.account?.accountName ?: strings.accountDevice,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = SilaDimens.Space8),
        )
    }
}

private const val DATE_BIRTHDAY = "BIRTHDAY"

@Composable
private fun SimpleField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

/** حقل رقمي صغير (شهر/يوم/سنة) — إدخال حر بلا منع (التحقق عند الحفظ). */
@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(84.dp),
        singleLine = true,
    )
}

@Composable
private fun SectionDivider() {
    Spacer(modifier = Modifier.height(SilaDimens.Space12))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    Spacer(modifier = Modifier.height(SilaDimens.Space12))
}
