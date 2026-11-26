package uk.hamdigital.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.MainViewModel
import uk.hamdigital.Screen

@Composable
fun AppScreens(vm: MainViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = Pal.Bg) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 14 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(180)) { -it / 18 })
                },
                label = "arch-screen-transition",
            ) { current ->
                when (current) {
                    Screen.Connect -> ConnectScreen(vm)
                    Screen.Control -> ControlScreen(vm)
                    Screen.SettingsPage -> SettingsScreen(vm)
                    Screen.Guide -> ChineseGuideScreen { vm.back() }
                    Screen.Log -> LogbookScreen(vm)
                }
            }
        }
    }
}
