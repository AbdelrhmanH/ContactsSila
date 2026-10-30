package app.sila.feature.contacts.presentation.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import app.sila.core.designsystem.SilaDimens
import app.sila.core.model.Relation

/**
 * خطوة "أهم 5 أشخاص" — بحث + اختيار حتى خمسة + صلة قرابة سريعة.
 *
 * - جدول جهات الاتصال فارغ في المرحلة 1 (قبل مهمة مزامنة T2): حالة فارغة عادية قابلة للتخطي.
 * - رفض/تخطي الصلاحية: شرح + اختصار الإعدادات، والتخطي لا ينشئ أي سجل أرحام.
 * - iOS .limited: تظهر جهات الاتصال المتاحة فقط مع شرح سبب نقصان الباقي.
 * - أرقام الهواتف تُعرض LTR حتى في تصميم RTL.
 */
@Composable
internal fun TopFiveScreen(
    state: OnboardingUiState,
    strings: OnboardingStrings,
    onIntent: (OnboardingIntent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(SilaDimens.Space16)
    ) {
        Text(
            text = strings.topFiveTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space4))
        Text(
            text = strings.topFiveBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space8))
        Text(
            text = strings.topFiveCounter(state.selections.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(SilaDimens.Space12))
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onIntent(OnboardingIntent.UpdateSearchQuery(it)) },
            placeholder = { Text(text = strings.topFiveSearchHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(SilaDimens.Space8))

        // حالات تحميل / خطأ / فارع / مرفوض.
        when {
            state.isLoading -> LoadingContent(strings.topFiveLoadingLabel)
            state.hasContactsError -> {
                ErrorContent(
                    title = strings.errorTitle,
                    body = strings.topFiveErrorBody,
                    retryLabel = strings.errorRetryButton,
                    onRetry = { onIntent(OnboardingIntent.RetryContactsLoad) }
                )
            }

            state.contactsPermission.isAccessMissing() -> {
                PermissionMissingContent(
                    body = strings.topFivePermissionMissingBody,
                    openSettingsLabel = strings.permissionOpenSettingsButton,
                    onOpenSettings = { onIntent(OnboardingIntent.OpenAppSettings) }
                )
            }

            else -> {
                if (state.contactsPermission == OnboardingPermissionState.LIMITED) {
                    Text(
                        text = strings.topFiveLimitedBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(SilaDimens.Space8))
                }
                if (state.selections.isNotEmpty()) {
                    SelectedRows(
                        state = state,
                        strings = strings,
                        onIntent = onIntent
                    )
                    Spacer(modifier = Modifier.height(SilaDimens.Space8))
                }
                when {
                    state.candidates.isEmpty() && state.searchQuery.isBlank() ->
                        EmptyContactsContent(
                            title = strings.topFiveEmptyTitle,
                            body = strings.topFiveEmptyBody
                        )

                    state.candidates.isEmpty() ->
                        Text(
                            text = strings.topFiveNoResultsLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentSize(Alignment.Center)
                                .padding(vertical = SilaDimens.Space24)
                        )

                    else ->
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            items(state.candidates, key = { it.systemContactId }) { contact ->
                                val isSelected = state.selections.any { it.systemContactId == contact.systemContactId }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onIntent(
                                                OnboardingIntent.ToggleSelection(
                                                    systemContactId = contact.systemContactId,
                                                    displayName = contact.name.displayName
                                                )
                                            )
                                        }
                                        .padding(vertical = SilaDimens.Space8),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isSelected) "☑" else "⬜",
                                        fontSize = 20.sp,
                                        modifier = Modifier.padding(end = SilaDimens.Space12)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = contact.name.displayName,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        val primaryPhone = contact.phones.firstOrNull()?.raw
                                        if (primaryPhone != null) {
                                            // أرقام الهواتف تظهر LTR دائماً حتى في تصميم RTL.
                                            Text(
                                                text = primaryPhone,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    textDirection = TextDirection.Ltr
                                                ),
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

        Spacer(modifier = Modifier.height(SilaDimens.Space12))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8)
        ) {
            OutlinedButton(
                onClick = { onIntent(OnboardingIntent.SkipTopFive) },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = strings.topFiveSkipButton)
            }
            Button(
                onClick = { onIntent(OnboardingIntent.SaveTopFive) },
                enabled = state.selections.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text(text = strings.topFiveSaveButton)
            }
        }
    }
}

private fun OnboardingPermissionState.isAccessMissing(): Boolean =
    this == OnboardingPermissionState.DENIED ||
        this == OnboardingPermissionState.DENIED_FOREVER ||
        this == OnboardingPermissionState.SKIPPED

@Composable
private fun SelectedRows(
    state: OnboardingUiState,
    strings: OnboardingStrings,
    onIntent: (OnboardingIntent) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        state.selections.forEach { selection ->
            Text(
                text = selection.displayName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = SilaDimens.Space4)
            )
            var selectedRelation by remember(selection.systemContactId) {
                mutableStateOf(selection.relation)
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
                modifier = Modifier.padding(bottom = SilaDimens.Space8)
            ) {
                items(QUICK_RELATIONS) { relation ->
                    FilterChip(
                        selected = selectedRelation == relation,
                        onClick = {
                            selectedRelation = relation
                            onIntent(
                                OnboardingIntent.SetRelation(
                                    systemContactId = selection.systemContactId,
                                    relation = relation
                                )
                            )
                        },
                        label = { Text(text = strings.relationLabel(relation)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingContent(label: String) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(SilaDimens.Space8))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorContent(
    title: String,
    body: String,
    retryLabel: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(SilaDimens.Space16),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(SilaDimens.Space8))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space12))
        OutlinedButton(onClick = onRetry) {
            Text(text = retryLabel)
        }
    }
}

@Composable
private fun PermissionMissingContent(
    body: String,
    openSettingsLabel: String,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space12))
        OutlinedButton(onClick = onOpenSettings) {
            Text(text = openSettingsLabel)
        }
    }
}

@Composable
private fun EmptyContactsContent(
    title: String,
    body: String
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space8))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** صلة قرابة سريعة (قائمة مقتصرة قابلة للتوسّع لاحقاً — OPEN/Closed عبر Relation نفسه). */
private val QUICK_RELATIONS = listOf(
    Relation.FATHER,
    Relation.MOTHER,
    Relation.BROTHER,
    Relation.SISTER,
    Relation.SPOUSE,
    Relation.SON,
    Relation.DAUGHTER,
    Relation.IN_LAW,
    Relation.CLOSE_FRIEND,
    Relation.CUSTOM
)

