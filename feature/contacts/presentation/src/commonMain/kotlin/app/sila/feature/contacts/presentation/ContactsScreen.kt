package app.sila.feature.contacts.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sila.core.designsystem.SilaCategoryChip
import app.sila.core.designsystem.SilaColors
import app.sila.core.designsystem.SilaContactRow
import app.sila.core.designsystem.SilaDimens
import app.sila.core.model.Category
import app.sila.core.model.Contact

/**
 * شاشة جهات الاتصال Stateful (CODING_GUIDELINES.md §2-3: XScreen stateful + XContent stateless).
 */
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    isArabic: Boolean,
    onCallContact: (phone: String, contactId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    ContactsContent(
        contacts = state.contacts,
        favoriteContacts = state.favorites,
        categoryCounts = state.categoryCounts,
        totalContactsCount = state.totalContactsCount,
        selectedCategory = state.selectedCategory,
        searchQuery = state.searchQuery,
        isArabic = isArabic,
        onSelectCategory = { viewModel.onIntent(ContactsIntent.SelectCategory(it)) },
        onSearchQueryChange = { viewModel.onIntent(ContactsIntent.UpdateSearchQuery(it)) },
        onCallContact = onCallContact,
        modifier = modifier
    )
}

/**
 * واجهة ميزة جهات الاتصال Stateless (:feature:contacts:presentation).
 */
@Composable
fun ContactsContent(
    contacts: List<Contact>,
    favoriteContacts: List<Contact>,
    categoryCounts: Map<Category, Int>,
    totalContactsCount: Int,
    selectedCategory: Category?,
    searchQuery: String,
    isArabic: Boolean,
    onSelectCategory: (Category?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCallContact: (phone: String, contactId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(SilaDimens.Space16),
        verticalArrangement = Arrangement.spacedBy(SilaDimens.Space12)
    ) {
        if (favoriteContacts.isNotEmpty()) {
            item(key = "favorites_row") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space16),
                    modifier = Modifier.fillMaxWidth().padding(bottom = SilaDimens.Space4)
                ) {
                    items(favoriteContacts, key = { it.id }) { fav ->
                        val ringColor = SilaColors.forCategory(fav.category)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                val phone = fav.phones.firstOrNull()?.e164 ?: return@clickable
                                onCallContact(phone, fav.id)
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                                    .background(ringColor.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = fav.name.displayName.take(1),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = ringColor
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = fav.name.displayName.split(" ").firstOrNull() ?: fav.name.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        item(key = "search_bar") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = {
                    Text(if (isArabic) "بحث بالاسم أو الرقم…" else "Search name or phone…")
                },
                singleLine = true,
                shape = RoundedCornerShape(SilaDimens.CardCornerRadius),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item(key = "category_chips") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
                modifier = Modifier.fillMaxWidth()
            ) {
                item(key = "chip_all") {
                    SilaCategoryChip(
                        label = if (isArabic) "الكل" else "All",
                        count = totalContactsCount,
                        selected = selectedCategory == null,
                        accentColor = MaterialTheme.colorScheme.primary,
                        onClick = { onSelectCategory(null) }
                    )
                }
                items(Category.entries, key = { it.name }) { cat ->
                    val count = categoryCounts[cat] ?: 0
                    SilaCategoryChip(
                        label = categoryTitle(cat, isArabic),
                        count = count,
                        selected = selectedCategory == cat,
                        accentColor = SilaColors.forCategory(cat),
                        onClick = { onSelectCategory(cat) }
                    )
                }
            }
        }

        items(
            items = contacts,
            key = { it.id },
            contentType = { "contact_row" }
        ) { contact ->
            SilaContactRow(
                contact = contact,
                categoryLabel = categoryTitle(contact.category, isArabic),
                callLabel = if (isArabic) "اتصال" else "Call",
                onCallClick = { phone -> onCallContact(phone, contact.id) }
            )
        }
    }
}

fun categoryTitle(category: Category, isArabic: Boolean): String = when (category) {
    Category.FAMILY -> if (isArabic) "عائلة" else "Family"
    Category.FRIENDS -> if (isArabic) "أصدقاء" else "Friends"
    Category.WORK -> if (isArabic) "عمل" else "Work"
    Category.SERVICES -> if (isArabic) "خدمات" else "Services"
    Category.EMERGENCY -> if (isArabic) "طوارئ" else "Emergency"
    Category.UNCATEGORIZED -> if (isArabic) "غير مصنف" else "Uncategorized"
}
