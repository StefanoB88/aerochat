package com.stefanobrandi.aerochat

import android.app.Application
import com.stefanobrandi.aerochat.app.AppEnvironment
import com.stefanobrandi.aerochat.screenshots.StoreScreenshots
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

    /** Con la modalita' screenshot (solo build debug) l'ambiente usa dati finti e radio simulata. */
    val environment: AppEnvironment by lazy {
        StoreScreenshots.makeEnvironment(this, applicationScope) ?: AppEnvironment(this, applicationScope)
    }
}
