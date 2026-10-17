package com.enchat.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.enchat.app.ui.screens.ChatScreen
import com.enchat.app.ui.screens.ContactsScreen
import com.enchat.app.ui.screens.ConversationsScreen
import com.enchat.app.ui.screens.LoginScreen
import com.enchat.app.ui.screens.RegisterScreen
import com.enchat.app.ui.screens.SettingsScreen
import com.enchat.app.repo.SessionState
import com.enchat.app.ui.vm.AppViewModelFactory
import com.enchat.app.ui.vm.AuthViewModel
import com.enchat.app.ui.vm.ChatViewModel
import com.enchat.app.ui.vm.ContactsViewModel
import com.enchat.app.ui.vm.ConversationsViewModel
import com.enchat.app.ui.vm.SettingsViewModel
import com.enchat.clientcore.ConnectionStateMachine
import com.enchat.protocol.Protocol

private object Route {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val CONVERSATIONS = "conversations"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"
    /** 参数：localId / peerUid / mode / title */
    const val CHAT = "chat/{localId}/{peerUid}/{mode}/{title}"
    fun chat(localId: Long, peerUid: Long, mode: Int, title: String) =
        "chat/$localId/$peerUid/$mode/${java.net.URLEncoder.encode(title, "UTF-8")}"
}

@Composable
fun AppNav(
    factory: AppViewModelFactory,
    banner: ConnectionStateMachine.Display,
    pendingOutbox: Int,
    /** 启动时本地会话仍有效 → 直接进主界面，不再走登录页。 */
    startLoggedIn: Boolean = false,
    /** 当前登录态。运行中被踢下线（令牌失效）时据此跳回登录页。 */
    sessionState: SessionState = SessionState.LOGGED_IN
) {
    val navController = rememberNavController()

    // 运行中被踢下线：令牌过期/被吊销时服务端回 401，这里强制回登录页。
    // 判断当前目的地，避免与"主动退出登录"的 navigate 重复执行。
    LaunchedEffect(sessionState) {
        if (sessionState == SessionState.NOT_LOGGED_IN &&
            navController.currentDestination?.route != Route.LOGIN
        ) {
            navController.navigate(Route.LOGIN) { popUpTo(0) { inclusive = true } }
        }
    }

    androidx.navigation.compose.NavHost(
        navController = navController,
        startDestination = if (startLoggedIn) Route.CONVERSATIONS else Route.LOGIN
    ) {

        composable(Route.LOGIN) {
            val vm: AuthViewModel = viewModel(factory = factory)
            LoginScreen(
                vm = vm,
                onLoggedIn = {
                    navController.navigate(Route.CONVERSATIONS) {
                        popUpTo(Route.LOGIN) { inclusive = true }
                    }
                },
                onGoRegister = { navController.navigate(Route.REGISTER) }
            )
        }

        composable(Route.REGISTER) {
            val vm: AuthViewModel = viewModel(factory = factory)
            RegisterScreen(
                vm = vm,
                onRegistered = {
                    navController.navigate(Route.CONVERSATIONS) {
                        popUpTo(Route.LOGIN) { inclusive = true }
                    }
                },
                onGoLogin = { navController.popBackStack() }
            )
        }

        composable(Route.CONVERSATIONS) {
            val vm: ConversationsViewModel = viewModel(factory = factory)
            ConversationsScreen(
                vm = vm,
                banner = banner,
                pendingOutbox = pendingOutbox,
                onOpenChat = { localId, peerUid, mode, title ->
                    navController.navigate(Route.chat(localId, peerUid, mode, title))
                },
                onOpenContacts = { navController.navigate(Route.CONTACTS) },
                onOpenSettings = { navController.navigate(Route.SETTINGS) }
            )
        }

        composable(Route.CONTACTS) {
            val vm: ContactsViewModel = viewModel(factory = factory)
            ContactsScreen(
                vm = vm,
                banner = banner,
                onOpenChat = { peerUid, title, avatarUrl ->
                    // 从联系人进入：先按标准模式打开会话（端到端需在聊天页内开启）
                    navController.navigate(Route.chat(0L, peerUid, Protocol.ENCRYPTION_MODE_STANDARD, title))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Route.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Route.LOGIN) { popUpTo(0) { inclusive = true } }
                }
            )
        }

        composable(
            route = Route.CHAT,
            arguments = listOf(
                navArgument("localId") { type = NavType.LongType },
                navArgument("peerUid") { type = NavType.LongType },
                navArgument("mode") { type = NavType.IntType },
                navArgument("title") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val vm: ChatViewModel = viewModel(factory = factory)
            val localId = backStackEntry.arguments?.getLong("localId") ?: 0L
            val peerUid = backStackEntry.arguments?.getLong("peerUid") ?: 0L
            val mode = backStackEntry.arguments?.getInt("mode") ?: Protocol.ENCRYPTION_MODE_STANDARD
            val title = remember {
                java.net.URLDecoder.decode(backStackEntry.arguments?.getString("title").orEmpty(), "UTF-8")
            }
            ChatScreen(
                vm = vm,
                title = title,
                mode = mode,
                localId = localId,
                peerUid = peerUid,
                banner = banner,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
