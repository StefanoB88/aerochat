package com.example.aerochat

import android.app.Application
import com.example.aerochat.app.AppEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application: ospita il composition root [AppEnvironment] (una sola istanza per processo, come lo
 * `@State environment` di `MyApp`). Il `bootstrap()` parte da `MainActivity` (come il `.task` di iOS),
 * cosi' i test Robolectric che istanziano l'Application non aprono Keystore/DB.
 */
class AeroChatApplication : Application() {

    /** Scope dell'intero processo, sul main thread (come `@MainActor`). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val environment: AppEnvironment by lazy { AppEnvironment(this, applicationScope) }
}
