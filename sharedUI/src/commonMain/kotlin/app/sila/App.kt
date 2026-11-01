package app.sila

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sila.core.designsystem.ActionSheetItem
import app.sila.core.designsystem.SilaActionSheet
import app.sila.core.designsystem.SilaColors
import app.sila.core.designsystem.SilaDimens
import app.sila.core.designsystem.SilaTheme
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.DefaultTab
import app.sila.di.ensureKoinStarted
import app.sila.feature.arham.presentation.ArhamScreen
import app.sila.feature.arham.presentation.ArhamViewModel
import app.sila.feature.contacts.presentation.ContactsScreen
import app.sila.feature.contacts.presentation.ContactsViewModel
import app.sila.feature.contacts.presentation.onboarding.OnboardingScreen
import app.sila.feature.contacts.presentation.onboarding.OnboardingViewModel
import app.sila.feature.main.MainIntent
import app.sila.feature.main.MainUiState
import app.sila.feature.main.MainViewModel
import app.sila.feature.services.presentation.ServicesScreen
import app.sila.feature.services.presentation.ServicesViewModel
import app.sila.feature.settings.presentation.SettingsScreen
import app.sila.feature.settings.presentation.SettingsViewModel

/**
 * نقطة الدخول للواجهة المشتركة — تحصل على التبعيات من حاوية Koin المشتركة (ensureKoinStarted)
 * وتفوّض كل شاشة Stateful إلى الـ ViewModel المستقل الخاص بميزتها طبقاً لمبادئ SOLID (SRP & DIP).
 *
 * بوابة التهيئة الأولى (Issue #26 — T5): ما دام `onboardingCompleted` غير مُعاد
 * من DataStore (Single Source of Truth) تعرض شاشة التهيئة (ضيف فقط) بدل الصفحة الرئيسة،
 * ولا تُعرض التهيئة إطلاقاً بعد اكتمالها.
 */
@Composable
@Preview
fun App(
    storageDirPath: String? = null
) {
    val koin = remember(storageDirPath) { ensureKoinStarted(storageDirPath) }
    val mainViewModel = remember(koin) { koin.get<MainViewModel>() }
    val contactsViewModel = remember(koin) { koin.get<ContactsViewModel>() }
    val servicesViewModel = remember(koin) { koin.get<ServicesViewModel>() }
    val arhamViewModel = remember(koin) { koin.get<ArhamViewModel>() }
    val settingsViewModel = remember(koin) { koin.get<SettingsViewModel>() }
    val onboardingViewModel = remember(koin) { koin.get<OnboardingViewModel>() }

    val state by mainViewModel.state.collectAsState()
    val isDark = state.preferences.themeMode == AppThemeMode.DARK
    val isRtl = state.preferences.language.isRtl
    val isAr = state.preferences.language == AppLanguage.ARABIC
    val onboardingStrings = rememberOnboardingStrings()
    val contactsStrings = rememberContactsStrings()

    SilaTheme(darkTheme = isDark, isRtl = isRtl) {
        if (!state.preferences.onboardingCompleted) {
            OnboardingScreen(
                viewModel = onboardingViewModel,
                strings = onboardingStrings
            )
        } else {
            SilaMainShell(
                state = state,
                onIntent = mainViewModel::onIntent,
                contactsContent = {
                    ContactsScreen(
                        viewModel = contactsViewModel,
                        strings = contactsStrings
                    )
                },
                servicesContent = {
                    ServicesScreen(
                        viewModel = servicesViewModel,
                        isArabic = isAr
                    )
                },
                arhamContent = {
                    ArhamScreen(
                        viewModel = arhamViewModel,
                        isArabic = isAr
                    )
                },
                settingsContent = {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onDismiss = { mainViewModel.onIntent(MainIntent.DismissSettingsSheet) }
                    )
                }
            )
        }
    }
}

@Composable
fun SilaMainShell(
    state: MainUiState,
    onIntent: (MainIntent) -> Unit,
    contactsContent: @Composable () -> Unit,
    servicesContent: @Composable () -> Unit,
    arhamContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAr = state.preferences.language == AppLanguage.ARABIC

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = SilaDimens.Space16, vertical = SilaDimens.Space12),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isAr) "صِلَة" else "Sila",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8)
                    ) {
                        OutlinedButton(
                            onClick = { onIntent(MainIntent.ToggleLanguage) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isAr) "EN" else "عربي",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .clickable { onIntent(MainIntent.ToggleSettingsSheet) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("⚙️", fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (state.activeTab == DefaultTab.CONTACTS) {
                FloatingActionButton(
                    onClick = { onIntent(MainIntent.ToggleSmartAddSheet) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Text("＋", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                NavigationBarItem(
                    selected = state.activeTab == DefaultTab.CONTACTS,
                    onClick = { onIntent(MainIntent.SelectTab(DefaultTab.CONTACTS)) },
                    label = { Text(if (isAr) "جهات الاتصال" else "Contacts") },
                    icon = { Text("👥", fontSize = 18.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    )
                )
                NavigationBarItem(
                    selected = state.activeTab == DefaultTab.SERVICES,
                    onClick = { onIntent(MainIntent.SelectTab(DefaultTab.SERVICES)) },
                    label = { Text(if (isAr) "دليل الخدمات" else "Directory") },
                    icon = { Text("🔧", fontSize = 18.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    )
                )
                NavigationBarItem(
                    selected = state.activeTab == DefaultTab.ARHAM,
                    onClick = { onIntent(MainIntent.SelectTab(DefaultTab.ARHAM)) },
                    label = { Text(if (isAr) "أرحام" else "Arham") },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (state.overdueArhamCount > 0) {
                                    Badge(containerColor = SilaColors.StatusOverdue) {
                                        Text(
                                            text = state.overdueArhamCount.toString(),
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        ) {
                            Text("🌿", fontSize = 18.sp)
                        }
                    },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
        ) {
            when (state.activeTab) {
                DefaultTab.CONTACTS -> contactsContent()
                DefaultTab.SERVICES -> servicesContent()
                DefaultTab.ARHAM -> arhamContent()
            }
        }
    }

    if (state.isSmartAddSheetVisible) {
        SilaActionSheet(
            title = if (isAr) "إضافة ذكية" else "Smart Add",
            cancelLabel = if (isAr) "إلغاء" else "Cancel",
            onDismiss = { onIntent(MainIntent.DismissSmartAddSheet) },
            items = listOf(
                ActionSheetItem(
                    iconEmoji = "👤",
                    title = if (isAr) "جهة اتصال جديدة" else "New Contact",
                    subtitle = if (isAr) "صديق، زميل، أو أي شخص" else "Friend, colleague, or personal",
                    onClick = {}
                ),
                ActionSheetItem(
                    iconEmoji = "👨‍👩‍👧",
                    title = if (isAr) "فرد من العائلة (أرحام)" else "Family Member (Arham)",
                    subtitle = if (isAr) "مع ضبط صلة القرابة ووتيرة الوصال" else "With kinship and reminder frequency",
                    onClick = { onIntent(MainIntent.SelectTab(DefaultTab.ARHAM)) }
                ),
                ActionSheetItem(
                    iconEmoji = "🔧",
                    title = if (isAr) "رقم خدمي" else "Service Provider",
                    subtitle = if (isAr) "سباك، صيدلية، أو نشاط تجاري" else "Plumber, pharmacy, or local business",
                    onClick = { onIntent(MainIntent.SelectTab(DefaultTab.SERVICES)) }
                ),
                ActionSheetItem(
                    iconEmoji = "📷",
                    title = if (isAr) "مسح بطاقة QR" else "Scan QR Business Card",
                    subtitle = if (isAr) "حفظ فوري بالتصنيف الصحيح" else "Instant save with auto-category",
                    onClick = {}
                )
            )
        )
    }

    if (state.isSettingsSheetVisible) {
        settingsContent()
    }
}
