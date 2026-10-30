package app.sila.feature.contacts.presentation.onboarding

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.sila.core.designsystem.SilaDimens
import kotlinx.coroutines.launch

/**
 * شاشة الترحيب — ثلاث شرائح: نظّم · خدمات حية · أرحام (+ "ابدأ").
 * التصميم مبنية على مكونات نظام صِلَة (DESIGN_SYSTEM) وتحترم RTL/الوضع الداكن/الخطوط الكبيرة.
 */
@Composable
internal fun WelcomePagerScreen(
    strings: OnboardingStrings,
    onIntent: (OnboardingIntent) -> Unit
) {
    val pages = listOf(
        WelcomePage(title = strings.welcomeTitleOrganize, body = strings.welcomeBodyOrganize),
        WelcomePage(title = strings.welcomeTitleLive, body = strings.welcomeBodyLive),
        WelcomePage(title = strings.welcomeTitleArham, body = strings.welcomeBodyArham)
    )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(SilaDimens.Space24),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            WelcomePageContent(
                page = pages[page],
                isActive = pagerState.currentPage == page
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SilaDimens.Space16),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            pages.indices.forEach { index ->
                val isActive = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = SilaDimens.Space4)
                        .size(width = if (isActive) 20.dp else 8.dp, height = 8.dp)
                        .clip(CircleShape)
                        .background(
                            color = MaterialTheme.colorScheme.primary
                                .copy(alpha = if (isActive) 1f else 0.3f)
                        )
                )
            }
        }

        Button(
            onClick = {
                if (pagerState.currentPage == pages.lastIndex) {
                    onIntent(OnboardingIntent.StartOnboarding)
                } else {
                    scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = SilaDimens.Space16)
        ) {
            Text(
                text = if (pagerState.currentPage == pages.lastIndex) {
                    strings.actionStart
                } else {
                    strings.actionContinue
                }
            )
        }
    }
}

private data class WelcomePage(
    val title: String,
    val body: String
)

@Composable
private fun WelcomePageContent(
    page: WelcomePage,
    isActive: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .alpha(if (isActive) 1f else 0.7f)
            .padding(SilaDimens.Space16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = page.title,
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space16))
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
