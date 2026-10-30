package app.sila.feature.contacts.presentation.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * شاشة التهيئة الأولى (المرحلة 1 — ضيف فقط) — مضيف يوجّه بين الخطوات حسب الحالة:
 * ترحيب ← شرح الصلاحية ← تخطي كضيف ← أهم 5 أشخاص ← جارٍ التنظيم.
 * يعترض App.kt الحالة ويعرض شاشة الصفحة الرئيسة (SilaMainShell) عند اكتمال التهيئة.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    strings: OnboardingStrings,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    AnimatedContent(
        targetState = state.step,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "onboarding_step_router",
        modifier = modifier
    ) { step ->
        when (step) {
            OnboardingStep.WELCOME ->
                WelcomePagerScreen(
                    strings = strings,
                    onIntent = viewModel::onIntent
                )

            OnboardingStep.PERMISSION ->
                PermissionExplainerScreen(
                    state = state,
                    strings = strings,
                    onIntent = viewModel::onIntent
                )

            OnboardingStep.GUEST_LOGIN ->
                GuestStepScreen(
                    strings = strings,
                    onIntent = viewModel::onIntent
                )

            OnboardingStep.TOP_FIVE ->
                TopFiveScreen(
                    state = state,
                    strings = strings,
                    onIntent = viewModel::onIntent
                )

            OnboardingStep.ORGANIZING ->
                OrganizingScreen(
                    strings = strings,
                    onIntent = viewModel::onIntent
                )
        }
    }
}
