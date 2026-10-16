package com.example.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.data.prefs.UserSettings
import com.example.assistant.ui.navigation.AppNavigation
import com.example.assistant.ui.screens.onboarding.OnboardingRoute
import com.example.assistant.ui.theme.AssistantTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single activity hosting the Compose UI: onboarding on first launch, then the app.
 * Deep links (`assistant://…` from notifications, the widget and app shortcuts) are handled by navigation.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by userPreferences.settings.collectAsStateWithLifecycle(initialValue = UserSettings())
            // null until DataStore is read, so the onboarding doesn't flash for returning users.
            val onboardingDone by produceState<Boolean?>(initialValue = null) {
                userPreferences.dataSettings.collect { value = it.onboardingDone }
            }

            AssistantTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, preset = settings.themePreset) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (onboardingDone) {
                        null -> Unit
                        false -> OnboardingRoute()
                        true -> AppNavigation()
                    }
                }
            }
        }
    }
}
