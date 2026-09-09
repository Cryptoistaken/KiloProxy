package net.typeblog.socks.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import net.typeblog.socks.R
import net.typeblog.socks.ui.screens.ProxiesScreen
import net.typeblog.socks.ui.screens.CountriesScreen
import net.typeblog.socks.ui.screens.StatusScreen
import net.typeblog.socks.ui.screens.BubbleSettingsScreen
import net.typeblog.socks.ui.screens.SettingsScreen
import net.typeblog.socks.ui.screens.SplitTunnelingScreen
import net.typeblog.socks.ui.screens.ThemeScreen
import net.typeblog.socks.ui.screens.DebugLogsScreen
import net.typeblog.socks.ui.viewmodel.VpnViewModel

sealed class Screen(val route: String) {
    data object Profiles : Screen("profiles")
    data object Connect : Screen("connect")
    data object Countries : Screen("countries")
    data object Settings : Screen("settings")
    data object SplitTunneling : Screen("split_tunneling")
    data object Theme : Screen("theme")
    data object BubbleSettings : Screen("bubble_settings")
    data object DebugLogs : Screen("debug_logs")
}

private data class BottomNavItem(
    val screen: Screen,
    val icon: Painter,
    val selectedIcon: Painter,
    val label: String
)

private val bottomNavRoutes = listOf(
    Screen.Profiles.route,
    Screen.Connect.route,
    Screen.Countries.route,
    Screen.Settings.route
).toSet()

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val vpnViewModel: VpnViewModel = viewModel()
    var profilePickMode by rememberSaveable { mutableStateOf(false) }
    var countryPickMode by rememberSaveable { mutableStateOf(false) }
    // Pick flows (profile from Home, country from the add-proxy sheet)
    // hide the bottom bar on the pick screen so it feels like a
    // separate page whose only action is selecting.
    val inPickFlow = (profilePickMode && currentDestination?.route == Screen.Profiles.route) ||
        (countryPickMode && currentDestination?.route == Screen.Countries.route)
    val showBottomBar = currentDestination?.route in bottomNavRoutes && !inPickFlow

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val bottomNavItems = listOf(
        BottomNavItem(Screen.Connect, painterResource(R.drawable.ic_proton_house), painterResource(R.drawable.ic_proton_house_filled), "Home"),
        BottomNavItem(Screen.Countries, painterResource(R.drawable.ic_proton_earth), painterResource(R.drawable.ic_proton_earth_filled), "Countries"),
        BottomNavItem(Screen.Profiles, painterResource(R.drawable.ic_proton_window_terminal), painterResource(R.drawable.ic_proton_window_terminal_filled), "Profiles"),
        BottomNavItem(Screen.Settings, painterResource(R.drawable.ic_proton_cog_wheel), painterResource(R.drawable.ic_proton_cog_wheel_filled), "Settings")
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp
                ) {
                    // Custom items instead of NavigationBarItem: same look
                    // (filled icon + label, no pill), and clickable with
                    // indication = null so taps have no ripple flash.
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.screen.route
                        } == true
                        val contentColor = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .semantics { selected = selected }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Tab,
                                    onClickLabel = item.label,
                                    onClick = {
                                        profilePickMode = false
                                        countryPickMode = false
                                        navigateToTab(item.screen.route)
                                    }
                                )
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = if (selected) item.selectedIcon else item.icon,
                                contentDescription = item.label,
                                tint = contentColor
                            )
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Connect.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Profiles.route) {
                ProxiesScreen(
                    viewModel = vpnViewModel,
                    pickMode = profilePickMode,
                    onPickProfile = { name ->
                        vpnViewModel.pickProfile(name)
                        profilePickMode = false
                        navigateToTab(Screen.Connect.route)
                    },
                    onPickCountryClick = {
                        countryPickMode = true
                        navigateToTab(Screen.Countries.route)
                    }
                )
            }
            composable(Screen.Connect.route) {
                StatusScreen(
                    viewModel = vpnViewModel,
                    onPickProfileClick = {
                        profilePickMode = true
                        navigateToTab(Screen.Profiles.route)
                    }
                )
            }
            composable(Screen.Countries.route) {
                CountriesScreen(
                    viewModel = vpnViewModel,
                    onConnected = {
                        navController.navigate(Screen.Connect.route) {
                            popUpTo(Screen.Countries.route) { inclusive = true }
                        }
                    },
                    pickMode = countryPickMode,
                    onPickCountry = { code ->
                        vpnViewModel.pickCountry(code)
                        countryPickMode = false
                        navigateToTab(Screen.Profiles.route)
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToSplitTunneling = {
                        navController.navigate(Screen.SplitTunneling.route)
                    },
                    onNavigateToTheme = {
                        navController.navigate(Screen.Theme.route)
                    },
                    onNavigateToBubbleSettings = {
                        navController.navigate(Screen.BubbleSettings.route)
                    },
                    onNavigateToDebugLogs = {
                        navController.navigate(Screen.DebugLogs.route)
                    }
                )
            }
            composable(Screen.Theme.route) {
                ThemeScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.BubbleSettings.route) {
                BubbleSettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.SplitTunneling.route) {
                SplitTunnelingScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    viewModel = vpnViewModel
                )
            }
            composable(Screen.DebugLogs.route) {
                DebugLogsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
