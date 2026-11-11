package com.stefanobrandi.aerochat.screenshots

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import com.stefanobrandi.aerochat.app.AppEnvironment
import kotlinx.coroutines.CoroutineScope

/**
 * Build release: nessuna modalita' screenshot (i dati finti esistono solo in `src/debug`). Stessa API
 * della versione debug, tutta inerte.
 */
object StoreScreenshots {

    val isActive: Boolean get() = false

    @Suppress("UNUSED_PARAMETER")
    fun configure(intent: Intent?) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun makeEnvironment(context: Context, scope: CoroutineScope): AppEnvironment? = null

    @Composable
    fun Overlay() = Unit
}
