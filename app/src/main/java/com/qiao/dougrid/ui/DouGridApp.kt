package com.qiao.dougrid.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qiao.dougrid.DouGridUiState
import com.qiao.dougrid.DouGridViewModel
import com.qiao.dougrid.data.MainDestination
import com.qiao.dougrid.ui.screens.CraftScreen
import com.qiao.dougrid.ui.screens.EditorScreen
import com.qiao.dougrid.ui.screens.ImageImportScreen
import com.qiao.dougrid.ui.screens.InventoryScreen
import com.qiao.dougrid.ui.screens.LibraryScreen
import com.qiao.dougrid.ui.screens.SettingsScreen

private object Routes {
    const val Main = "main"
    const val Import = "import"
    const val Settings = "settings"
    const val EditorPattern = "editor/{projectId}"
    fun editor(projectId: String) = "editor/$projectId"
}

@Composable
fun DouGridApp(viewModel: DouGridViewModel, state: DouGridUiState) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    var pendingImportUri by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.clearMessage()
    }
    LaunchedEffect(state.openProjectRequestId) {
        val projectId = state.openProjectRequestId ?: return@LaunchedEffect
        navController.navigate(Routes.editor(projectId)) {
            launchSingleTop = true
            popUpTo(Routes.Main)
        }
        viewModel.consumeOpenProjectRequest()
    }

    Box(Modifier.fillMaxSize()) {
        if (state.isLoading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text("豆格", style = MaterialTheme.typography.titleLarge)
            }
        } else {
            NavHost(navController = navController, startDestination = Routes.Main) {
                composable(Routes.Main) {
                    MainWorkspace(
                        state = state,
                        viewModel = viewModel,
                        onImportUri = { uri ->
                            pendingImportUri = uri.toString()
                            navController.navigate(Routes.Import)
                        },
                        onOpenSettings = { navController.navigate(Routes.Settings) },
                    )
                }
                composable(Routes.Import) {
                    val uri = pendingImportUri?.let(Uri::parse)
                    if (uri == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        ImageImportScreen(
                            uri = uri,
                            state = state,
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(
                    route = Routes.EditorPattern,
                    arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
                ) { entry ->
                    val id = entry.arguments?.getString("projectId")
                    val project = state.projects.firstOrNull { it.id == id }
                    if (project == null) {
                        LaunchedEffect(id) { navController.popBackStack() }
                    } else {
                        EditorScreen(
                            project = project,
                            state = state,
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onOpenInventory = { projectId ->
                                viewModel.selectCraftProject(projectId)
                                viewModel.selectMainDestination(MainDestination.INVENTORY)
                                navController.popBackStack(Routes.Main, inclusive = false)
                            },
                        )
                    }
                }
                composable(Routes.Settings) {
                    SettingsScreen(
                        state = state,
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun MainWorkspace(
    state: DouGridUiState,
    viewModel: DouGridViewModel,
    onImportUri: (Uri) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val selected = state.mainDestination
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 760.dp
        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                MainNavigationRail(selected = selected, onSelected = viewModel::selectMainDestination)
                MainContent(
                    selected = selected,
                    state = state,
                    viewModel = viewModel,
                    onImportUri = onImportUri,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Scaffold(
                bottomBar = { MainNavigationBar(selected = selected, onSelected = viewModel::selectMainDestination) },
            ) { padding ->
                MainContent(
                    selected = selected,
                    state = state,
                    viewModel = viewModel,
                    onImportUri = onImportUri,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun MainContent(
    selected: MainDestination,
    state: DouGridUiState,
    viewModel: DouGridViewModel,
    onImportUri: (Uri) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when (selected) {
            MainDestination.LIBRARY -> LibraryScreen(
                state = state,
                viewModel = viewModel,
                onImportUri = onImportUri,
                onOpenSettings = onOpenSettings,
            )
            MainDestination.CRAFT -> CraftScreen(state = state, viewModel = viewModel)
            MainDestination.INVENTORY -> InventoryScreen(state = state, viewModel = viewModel)
        }
    }
}

private data class NavItem(val destination: MainDestination, val label: String)

private val navItems = listOf(
    NavItem(MainDestination.LIBRARY, "作品"),
    NavItem(MainDestination.CRAFT, "开拼"),
    NavItem(MainDestination.INVENTORY, "豆仓"),
)

@Composable
private fun MainNavigationBar(selected: MainDestination, onSelected: (MainDestination) -> Unit) {
    NavigationBar {
        navItems.forEach { item ->
            NavigationBarItem(
                selected = selected == item.destination,
                onClick = { onSelected(item.destination) },
                icon = { DestinationIcon(item.destination) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun MainNavigationRail(selected: MainDestination, onSelected: (MainDestination) -> Unit) {
    NavigationRail {
        navItems.forEach { item ->
            NavigationRailItem(
                selected = selected == item.destination,
                onClick = { onSelected(item.destination) },
                icon = { DestinationIcon(item.destination) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun DestinationIcon(destination: MainDestination) {
    val icon = when (destination) {
        MainDestination.LIBRARY -> Icons.Default.GridView
        MainDestination.CRAFT -> Icons.Default.Build
        MainDestination.INVENTORY -> Icons.Default.Inventory2
    }
    Icon(icon, contentDescription = null)
}
