package app.sila.feature.services.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.sila.core.designsystem.SilaDimens
import app.sila.core.designsystem.SilaServiceCard
import app.sila.core.model.Service

/**
 * شاشة دليل الخدمات Stateful (CODING_GUIDELINES.md §2-3: XScreen stateful + XContent stateless).
 */
@Composable
fun ServicesScreen(
    viewModel: ServicesViewModel,
    isArabic: Boolean,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    ServicesContent(
        services = state.services,
        searchQuery = state.searchQuery,
        isArabic = isArabic,
        onSearchQueryChange = { viewModel.onIntent(ServicesIntent.UpdateSearchQuery(it)) },
        onCallService = { viewModel.onIntent(ServicesIntent.CallService(it)) },
        modifier = modifier
    )
}

/**
 * واجهة ميزة دليل الخدمات الحي Stateless (:feature:services:presentation).
 */
@Composable
fun ServicesContent(
    services: List<Service>,
    searchQuery: String,
    isArabic: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onCallService: (phoneE164: String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(SilaDimens.Space16),
        verticalArrangement = Arrangement.spacedBy(SilaDimens.Space12)
    ) {
        item(key = "services_search") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = {
                    Text(
                        if (isArabic) "ابحث عن خدمة (سباك، صيدلية، كهربائي…)"
                        else "Search service (plumber, pharmacy…)"
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(SilaDimens.CardCornerRadius),
                modifier = Modifier.fillMaxWidth()
            )
        }

        items(
            items = services,
            key = { it.cloudId },
            contentType = { "service_card" }
        ) { service ->
            SilaServiceCard(
                service = service,
                verifiedLabel = if (isArabic) "موثق ✔" else "Verified ✔",
                communityLabel = if (isArabic) "مجتمعي" else "Community",
                callLabel = if (isArabic) "اتصال" else "Call",
                onCallClick = onCallService
            )
        }
    }
}
