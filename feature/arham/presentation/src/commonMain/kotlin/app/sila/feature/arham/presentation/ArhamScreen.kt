package app.sila.feature.arham.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sila.core.designsystem.SilaArhamRankedRow
import app.sila.core.designsystem.SilaDimens
import app.sila.core.model.ArhamDashboardStats
import app.sila.core.model.ArhamItem
import app.sila.core.model.ArhamStatus

/**
 * شاشة أرحام Stateful (CODING_GUIDELINES.md §2-3: XScreen stateful + XContent stateless).
 */
@Composable
fun ArhamScreen(
    viewModel: ArhamViewModel,
    isArabic: Boolean,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    ArhamContent(
        arhamItems = state.items,
        arhamStats = state.stats,
        isArabic = isArabic,
        onCallRelative = { contactId, phone ->
            viewModel.onIntent(ArhamIntent.RecordCall(contactId = contactId, phone = phone))
        },
        modifier = modifier
    )
}

/**
 * واجهة ميزة أرحام Stateless (:feature:arham:presentation).
 */
@Composable
fun ArhamContent(
    arhamItems: List<ArhamItem>,
    arhamStats: ArhamDashboardStats,
    isArabic: Boolean,
    onCallRelative: (contactId: Long, phone: String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(SilaDimens.Space16),
        verticalArrangement = Arrangement.spacedBy(SilaDimens.Space16)
    ) {
        item(key = "arham_podium") {
            Surface(
                shape = RoundedCornerShape(SilaDimens.PodiumCornerRadius),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(SilaDimens.Space20),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(SilaDimens.Space12)
                ) {
                    Text(
                        text = if (isArabic) "🌿 لوحة صلة الرحم — هذا الأسبوع" else "🌿 Arham Dashboard — This Week",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ArhamStatBadge(
                            label = if (isArabic) "المكالمات" else "Calls",
                            value = arhamStats.totalFamilyCalls.toString()
                        )
                        ArhamStatBadge(
                            label = if (isArabic) "تم التواصل" else "Connected",
                            value = "${arhamStats.contactedRelatives}/${arhamItems.size}"
                        )
                        ArhamStatBadge(
                            label = if (isArabic) "تحقيق الهدف" else "Goal",
                            value = "${(arhamStats.goalCompletion * 100).toInt()}%"
                        )
                    }

                    LinearProgressIndicator(
                        progress = { arhamStats.goalCompletion },
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }

        item(key = "arham_ranked_list") {
            Surface(
                shape = RoundedCornerShape(SilaDimens.PodiumCornerRadius),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = SilaDimens.Space8)) {
                    arhamItems.forEach { item ->
                        val statusText = when (item.status) {
                            ArhamStatus.OVERDUE ->
                                if (isArabic) "🔴 يحتاج اتصال — مر ${item.daysSinceLastContact ?: 0} يوم"
                                else "🔴 Needs call — ${item.daysSinceLastContact ?: 0}d ago"
                            ArhamStatus.SOON ->
                                if (isArabic) "🟡 اقترب موعد الوصال (${item.daysSinceLastContact ?: 0} يوم)"
                                else "🟡 Due soon (${item.daysSinceLastContact ?: 0}d ago)"
                            ArhamStatus.OK ->
                                if (isArabic) "🟢 تم التواصل مؤخراً"
                                else "🟢 Connected recently"
                        }

                        SilaArhamRankedRow(
                            item = item,
                            statusText = statusText,
                            callLabel = if (isArabic) "اتصال" else "Call",
                            onCallClick = onCallRelative
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArhamStatBadge(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f)
        )
    }
}
