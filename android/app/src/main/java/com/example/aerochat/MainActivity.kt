package com.example.aerochat

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import com.example.aerochat.app.AppEnvironment
import com.example.aerochat.app.LocalAppEnvironment
import com.example.aerochat.core.mesh.LocalNotificationCenter
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.ui.AeroChatTheme
import com.example.aerochat.ui.AppNavigator
import com.example.aerochat.ui.components.Haptics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Unica Activity: ospita la UI Compose ([ContentView]) e instrada gli Intent esterni:
 * - link `aeromesh://...` (QR inquadrato con la fotocamera di sistema) → [AppEnvironment.pair];
 * - tocco su una notifica (extra [LocalNotificationCenter.extraConversationID]) → apre la chat.
 * Il ciclo di vita mappa lo `scenePhase` di iOS: `onResume` → `sceneDidBecomeActive()`.
 */
class MainActivity : ComponentActivity() {

    private val environment: AppEnvironment get() = (application as AeroChatApplication).environment

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Haptics.install(this)
        lifecycleScope.launch { environment.bootstrap() }
        setContent {
            CompositionLocalProvider(LocalAppEnvironment provides environment) {
                AeroChatTheme { ContentView() }
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            environment.setForeground(true)
            environment.sceneDidBecomeActive()
        }
    }

    override fun onPause() {
        super.onPause()
        // Lo scope dell'Application sopravvive all'Activity: la chiamata arriva anche dopo onPause.
        (application as AeroChatApplication).applicationScope.launch { environment.setForeground(false) }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(LocalNotificationCenter.extraConversationID)?.let { conversationID ->
            AppNavigator.openConversation(conversationID)
            intent.removeExtra(LocalNotificationCenter.extraConversationID)
        }
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data?.scheme == MeshProtocol.qrScheme) {
            val uriString = data.toString()
            intent.data = null
            lifecycleScope.launch {
                // Il pairing richiede la mesh accesa (dopo bootstrap e onboarding).
                environment.router.first { it != null }
                val conversation = runCatching { environment.pair(uriString) }.getOrNull()
                if (conversation != null) {
                    Haptics.success()
                    AppNavigator.openConversation(conversation.id)
                } else {
                    Haptics.error()
                }
            }
        }
    }
}
