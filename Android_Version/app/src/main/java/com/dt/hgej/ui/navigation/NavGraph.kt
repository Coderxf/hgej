package com.dt.hgej.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.dt.hgej.ui.login.LoginScreen
import com.dt.hgej.ui.main.MainScreen
import com.dt.hgej.ui.qrcode.QrScreen

object Routes {
    const val MAIN = "main"
    const val LOGIN = "login"
    const val QR_CODE = "qrcode"
}

@Composable
fun NavGraph(navController: NavHostController) {
    val singleTopOptions = NavOptions.Builder()
        .setLaunchSingleTop(true)
        .build()

    NavHost(navController = navController, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                onNavigateToLogin = { navController.navigate(Routes.LOGIN, singleTopOptions) },
                onNavigateToQr = { navController.navigate(Routes.QR_CODE, singleTopOptions) }
            )
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = { navController.popBackStack() }
            )
        }
        composable(Routes.QR_CODE) {
            QrScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
