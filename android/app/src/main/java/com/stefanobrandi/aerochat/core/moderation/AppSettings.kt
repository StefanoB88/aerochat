package com.stefanobrandi.aerochat.core.moderation

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferenze locali dell'utente (SharedPreferences, `UserDefaults` su iOS). Le chiavi sono centralizzate
 * qui cosi' le schermate e i servizi fuori dal main thread (router, filtro linguaggio) leggono lo stesso valore.
 *
 * [initialize] va chiamato una volta all'avvio (lo fa `AppEnvironment`); prima di allora, e nei test JVM
 * senza Android, i valori vivono solo in memoria con gli stessi default di iOS (`registerDefaults`).
 * Le schermate osservano [state] (`collectAsStateWithLifecycle()`).
 */
object AppSettings {

    object Key {
        /** Versione delle regole della Cabina Pubblica accettate dall'utente (0 = mai). */
        const val loungeRulesAcceptedVersion = "lounge.rulesAcceptedVersion"
        /** Maschera le parolacce nei messaggi ricevuti in Cabina Pubblica. */
        const val languageFilter = "moderation.languageFilter"
        /** Le foto inviate da contatti non verificati (mai scansionati di persona) restano sfocate finche' non vengono toccate. */
        const val blurStrangerMedia = "moderation.blurStrangerMedia"
        /** Compagno simulato per la revisione dello store (un solo dispositivo a disposizione). */
        const val demoMode = "review.demoMode"
        /** Risposte rapide suggerite dal modello on-device (dove disponibile). */
        const val smartReplies = "assistant.smartReplies"
        /** "Visibile alle persone vicine": invio del `PRESENCE` (default: attivo). */
        const val nearbyVisible = "nearby.visible"
        /** "Accetto sfide a carte": `Presence.open_to_games`. */
        const val openToGames = "nearby.openToGames"
    }

    /** Da incrementare quando le regole della Cabina cambiano in modo sostanziale: l'utente le rivedra'. */
    const val loungeRulesVersion = 1

    /** Istantanea osservabile delle impostazioni. */
    data class Snapshot(
        val loungeRulesAcceptedVersion: Int = 0,
        val isLanguageFilterEnabled: Boolean = true,
        val blursStrangerMedia: Boolean = true,
        val isDemoModeEnabled: Boolean = false,
        val smartRepliesEnabled: Boolean = true,
        val isNearbyVisible: Boolean = true,
        val isOpenToGames: Boolean = true,
    ) {
        val hasAcceptedLoungeRules: Boolean get() = loungeRulesAcceptedVersion >= loungeRulesVersion
    }

    @Volatile
    private var preferences: SharedPreferences? = null

    private val _state = MutableStateFlow(Snapshot())
    /** Valori correnti; ogni modifica fatta tramite questo oggetto produce una nuova emissione. */
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Nome del file SharedPreferences. */
    const val preferencesName = "aeromesh.settings"

    /** Apre le preferenze (idempotente) e carica i valori salvati, con i default di iOS per quelli mai toccati. */
    fun initialize(context: Context) {
        if (preferences != null) return
        synchronized(this) {
            if (preferences != null) return
            val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            preferences = prefs
            _state.value = Snapshot(
                loungeRulesAcceptedVersion = prefs.getInt(Key.loungeRulesAcceptedVersion, 0),
                isLanguageFilterEnabled = prefs.getBoolean(Key.languageFilter, true),
                blursStrangerMedia = prefs.getBoolean(Key.blurStrangerMedia, true),
                isDemoModeEnabled = prefs.getBoolean(Key.demoMode, false),
                smartRepliesEnabled = prefs.getBoolean(Key.smartReplies, true),
                isNearbyVisible = prefs.getBoolean(Key.nearbyVisible, true),
                isOpenToGames = prefs.getBoolean(Key.openToGames, true),
            )
        }
    }

    /** Solo test: torna ai default in memoria, staccando le preferenze. */
    internal fun resetForTesting() {
        synchronized(this) {
            preferences = null
            _state.value = Snapshot()
        }
    }

    var smartRepliesEnabled: Boolean
        get() = _state.value.smartRepliesEnabled
        set(value) = update(Key.smartReplies, value) { it.copy(smartRepliesEnabled = value) }

    /** Solo il flag salvato: il router lo riceve da `AppEnvironment.setNearbyVisible`. */
    var isNearbyVisible: Boolean
        get() = _state.value.isNearbyVisible
        set(value) = update(Key.nearbyVisible, value) { it.copy(isNearbyVisible = value) }

    var isOpenToGames: Boolean
        get() = _state.value.isOpenToGames
        set(value) = update(Key.openToGames, value) { it.copy(isOpenToGames = value) }

    var isLanguageFilterEnabled: Boolean
        get() = _state.value.isLanguageFilterEnabled
        set(value) = update(Key.languageFilter, value) { it.copy(isLanguageFilterEnabled = value) }

    var blursStrangerMedia: Boolean
        get() = _state.value.blursStrangerMedia
        set(value) = update(Key.blurStrangerMedia, value) { it.copy(blursStrangerMedia = value) }

    /** Solo il flag salvato: per accendere/spegnere davvero il compagno usare `AppEnvironment.setDemoMode`. */
    var isDemoModeEnabled: Boolean
        get() = _state.value.isDemoModeEnabled
        set(value) = update(Key.demoMode, value) { it.copy(isDemoModeEnabled = value) }

    var loungeRulesAcceptedVersion: Int
        get() = _state.value.loungeRulesAcceptedVersion
        set(value) {
            synchronized(this) {
                preferences?.edit()?.putInt(Key.loungeRulesAcceptedVersion, value)?.apply()
                _state.value = _state.value.copy(loungeRulesAcceptedVersion = value)
            }
        }

    val hasAcceptedLoungeRules: Boolean get() = _state.value.hasAcceptedLoungeRules

    /** L'utente ha accettato le regole correnti della Cabina Pubblica. */
    fun acceptLoungeRules() {
        loungeRulesAcceptedVersion = loungeRulesVersion
    }

    private fun update(key: String, value: Boolean, transform: (Snapshot) -> Snapshot) {
        synchronized(this) {
            preferences?.edit()?.putBoolean(key, value)?.apply()
            _state.value = transform(_state.value)
        }
    }
}

/** Riferimenti esterni mostrati nell'app (listino, legale, supporto). */
object AppLinks {
    const val website = "https://aeromesh.app"
    const val privacyPolicy = "https://aeromesh.app/privacy"
    const val termsOfUse = "https://aeromesh.app/terms"
    /** EULA standard di Apple, citata dalle linee guida iOS (stesso testo legale dell'app iOS). */
    const val appleEULA = "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/"
    const val supportEmail = "support@aeromesh.app"
    const val safetyEmail = "safety@aeromesh.app"
}
