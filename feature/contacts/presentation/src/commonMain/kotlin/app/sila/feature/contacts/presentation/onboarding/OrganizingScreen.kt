package app.sila.feature.contacts.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.sila.core.designsystem.SilaDimens
import kotlinx.coroutines.delay

/**
 * شاشة "جارٍ التنظيم…" — انتقال قصير (الفلترة الذكية تبدأ في الخلفية) ثم الرئيسية.
 * التبسيط في المرحلة 1: مؤشر تعليق قصير ثم إتمام onboardingCompleted (يُدوَّم؛ يبقى
 * على قيد الحياة بعد مقتل العملية ولا تكرر التهيئة أبداً).
 */
private const val ORGANIZING_MIN_DISPLAY_MS = 1_500L

@Composable
internal fun OrganizingScreen(
    strings: OnboardingStrings,
    onIntent: (OnboardingIntent) -> Unit
) {
    LaunchedEffect(Unit) {
        delay(ORGANIZING_MIN_DISPLAY_MS)
        onIntent(OnboardingIntent.FinishOrganizing)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(SilaDimens.Space24),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(SilaDimens.Space16))
        Text(
            text = strings.organizingTitle,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space8))
        Text(
            text = strings.organizingBody,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
