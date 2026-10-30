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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.sila.core.designsystem.SilaDimens

/**
 * خطوة "تسجيل الدخول/تخطي الآن" — المرحلة 1 ضيف فقط:
 * لا واجهة OTP ولا حساب؛ زر "تخطي الآن" يكمل كضيف (isGuest = true وصفر كتابات سحابية)،
 * وسجل الدخول متاح في مرحلة لاحقة.
 */
@Composable
internal fun GuestStepScreen(
    strings: OnboardingStrings,
    onIntent: (OnboardingIntent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(SilaDimens.Space24),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = strings.guestTitle,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space12))
        Text(
            text = strings.guestBody,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space8))
        Text(
            text = strings.guestLoginNote,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(SilaDimens.Space24))
        Button(
            onClick = { onIntent(OnboardingIntent.ContinueAsGuest) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = strings.guestSkipButton)
        }
    }
}
