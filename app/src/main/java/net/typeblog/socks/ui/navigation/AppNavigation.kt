package net.typeblog.socks.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
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
import net.typeblog.socks.ui.screens.NetShieldScreen
import net.typeblog.socks.ui.screens.DebugLogsScreen
import net.typeblog.socks.ui.viewmodel.VpnViewModel

sealed class Screen(val route: String) {
    data object Profiles : Screen("profiles")
    data object Connect : Screen("connect")
    data object Countries : Screen("countries")
    data object Settings : Screen("settings")
    data object SplitTunneling : Screen("split_tunneling")
    data object BubbleSettings : Screen("bubble_settings")
    data object NetShield : Screen("netshield")
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
    val showBottomBar = currentDestination?.route in bottomNavRoutes
    val vpnViewModel: VpnViewModel = viewModel()

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
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.screen.route
                        } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    painter = if (selected) item.selectedIcon else item.icon,
                                    contentDescription = item.label
                                )
                            },
                            label = {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
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
                ProxiesScreen(viewModel = vpnViewModel)
            }
            composable(Screen.Connect.route) {
                StatusScreen(viewModel = vpnViewModel)
            }
            composable(Screen.Countries.route) {
                CountriesScreen(
                    viewModel = vpnViewModel,
                    onConnected = {
                        navController.navigate(Screen.Connect.route) {
                            popUpTo(Screen.Countries.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToSplitTunneling = {
                        navController.navigate(Screen.SplitTunneling.route)
                    },
                    onNavigateToBubbleSettings = {
                        navController.navigate(Screen.BubbleSettings.route)
                    },
                    onNavigateToNetShield = {
                        navController.navigate(Screen.NetShield.route)
                    },
                    onNavigateToDebugLogs = {
                        navController.navigate(Screen.DebugLogs.route)
                    }
                )
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
            composable(Screen.NetShield.route) {
                NetShieldScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
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
