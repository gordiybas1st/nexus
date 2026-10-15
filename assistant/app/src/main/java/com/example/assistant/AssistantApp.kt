package com.example.assistant

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Application class; bootstraps the Hilt dependency graph. */
@HiltAndroidApp
class AssistantApp : Application()
