package io.github.sjtrotter.strengthlog

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt's application root. The object graph is defined in [io.github.sjtrotter.strengthlog.di]. */
@HiltAndroidApp
class StrengthLogApp : Application()
