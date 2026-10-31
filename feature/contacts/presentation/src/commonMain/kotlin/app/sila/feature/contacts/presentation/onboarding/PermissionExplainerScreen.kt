package app.sila.feature.contacts.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.sila.core.designsystem.SilaDimens
import app.sila.feature.contacts.domain.onboarding.OnboardingPermissionState

/**
 * خطوة شرح صلاحية جهات الاتصال ثم النتيجة (مصفوفة الأذونات في Issue #20):
 * - قبل الطلب أولاً: شرح الخصوصية ثم طلب واحد فقط (بواسطة المنصة عبر Effect).
 * - رفض لمرة واحدة: شرح + إعادة محاولة صريحة (داخل حد ≤ 2 طلب).
 * - رفض نهائي: شرح + "فتح الإعدادات" (لا يُعاد الطلب تلقائياً أبداً).
 * - يمكن التخطي دائماً والمتابعة كضيف بسجل فارغ.
 */
@Composable
internal fun PermissionExplainerScreen(
    state: OnboardingUiState,
    strings: OnboardingStrings,
    onIntent: (OnboardingIntent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(SilaDimens.Space24),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = strings.permissionTitle,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space12))
        Text(
            text = strings.permissionBody,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(SilaDimens.Space16))
        when (state.contactsPermission) {
            OnboardingPermissionState.DENIED ->
                PermissionNoteCard(text = strings.permissionDeniedOnceBody)

            OnboardingPermissionState.DENIED_FOREVER ->
                PermissionNoteCard(text = strings.permissionDeniedForeverBody)

            else -> Unit
        }
        Spacer(modifier = Modifier.height(SilaDimens.Space16))

        when (state.contactsPermission) {
            OnboardingPermissionState.DENIED_FOREVER -> {
                Button(
                    onClick = { onIntent(OnboardingIntent.OpenAppSettings) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = strings.permissionOpenSettingsButton)
                }
            }

            OnboardingPermissionState.DENIED -> {
                Button(
                    onClick = { onIntent(OnboardingIntent.RequestContactsPermission) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = strings.errorRetryButton)
                }
            }

            else -> {
                Button(
                    onClick = { onIntent(OnboardingIntent.RequestContactsPermission) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = strings.permissionGrantButton)
                }
            }
        }

        Spacer(modifier = Modifier.height(SilaDimens.Space12))
        OutlinedButton(
            onClick = { onIntent(OnboardingIntent.SkipPermission) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = strings.permissionSkipButton)
        }
    }
}

@Composable
private fun PermissionNoteCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Start,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(SilaDimens.Space16)
        )
    }
}
