package com.valt.offlinensdshare.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.valt.offlinensdshare.ui.screens.ChatScreen
import com.valt.offlinensdshare.ui.screens.HubListScreen
import com.valt.offlinensdshare.ui.screens.HubScreen
import com.valt.offlinensdshare.ui.screens.PeersScreen
import com.valt.offlinensdshare.ui.screens.RoleSelectScreen

object Routes {
    const val ROLE = "role"
    const val HUB = "hub"
    const val HUB_LIST = "hubList"
    const val PEERS = "peers"
    const val CHAT = "chat/{peerId}"
    fun chat(peerId: String) = "chat/$peerId"
}

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    viewModel: AppViewModel = viewModel(),
) {
    NavHost(navController, startDestination = Routes.ROLE, modifier = modifier) {
        composable(Routes.ROLE) {
            RoleSelectScreen(
                viewModel = viewModel,
                onHub = { name ->
                    viewModel.startHub(name)
                    navController.navigate(Routes.HUB)
                },
                onClient = { name ->
                    viewModel.startClient(name)
                    navController.navigate(Routes.HUB_LIST)
                },
            )
        }
        composable(Routes.HUB) {
            HubScreen(
                viewModel = viewModel,
                onOpenChat = { navController.navigate(Routes.chat(it)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.HUB_LIST) {
            HubListScreen(
                viewModel = viewModel,
                onConnected = { navController.navigate(Routes.PEERS) { launchSingleTop = true } },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PEERS) {
            PeersScreen(
                viewModel = viewModel,
                onOpenChat = { navController.navigate(Routes.chat(it)) },
                onLeave = { navController.popBackStack(Routes.HUB_LIST, inclusive = false) },
            )
        }
        composable(
            Routes.CHAT,
            arguments = listOf(navArgument("peerId") { type = NavType.StringType }),
        ) { entry ->
            ChatScreen(
                viewModel = viewModel,
                peerId = entry.arguments?.getString("peerId").orEmpty(),
                onBack = { navController.popBackStack() },
                onHubLost = { navController.popBackStack(Routes.HUB_LIST, inclusive = false) },
            )
        }
    }
}
