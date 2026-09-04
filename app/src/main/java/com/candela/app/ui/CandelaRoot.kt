package com.candela.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun CandelaRoot() {
    CandelaTheme {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    onSend = { nav.navigate("send") },
                    onReceive = { nav.navigate("receive") },
                )
            }
            composable("send") { SendScreen(onBack = { nav.popBackStack() }) }
            composable("receive") { ReceiveScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
