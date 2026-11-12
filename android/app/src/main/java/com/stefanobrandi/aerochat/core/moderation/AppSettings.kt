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
        /** Scheda "Porta i tuoi amici a bordo" chiusa dall'utente: non ricompare piu' (quella in volo si'). */
        const val inviteCardDismissed = "home.inviteCardDismissed"
        /** "Mostra il mio posto ai vicini": `Presence.seat` (default: spento). */
        const val shareSeat = "nearby.shareSeat"
        /** Prefisso del posto salvato per volo: `flight.seat.<CARRIER|FLIGHT|yyyy-MM-dd>` -> "14C". */
        const val seatPrefix = "flight.seat."
        /** Modalita' viaggio della scheda principale ([TravelMode.rawValue]). */
        const val travelMode = "home.travelMode"
        /** Ultimo avviso di emergenza inviato in volo (epoch ms): al massimo uno ogni 10 minuti. */
        const val lastEmergencyAt = "emergency.lastSentAt"
        /** Richiesta di recensione: prima apertura, numero di aperture, ultima richiesta (epoch ms). */
        const val firstOpenAt = "review.firstOpenAt"
        const val openCount = "review.openCount"
        const val lastReviewRequestAt = "review.lastRequestAt"
        /** Premio inviti: `node_id` (hex) arrivati con il nostro QR/link e scadenza del Pass locale. */
        const val referralInvited = "referral.invited"
        const val referralRewardGranted = "referral.rewardGranted"
        const val referralPassUntil = "referral.passUntil"
        /** Partite gratis gia' usate (`flight:<code>` / `day:<yyyy-MM-dd>`). */
        const val freeGamesUsed = "games.freeUsed"
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
        val isInviteCardDismissed: Boolean = false,
        val isSeatShared: Boolean = false,
        /** Posto per volo (chiave canonica -> "14C"). */
        val seats: Map<String, String> = emptyMap(),
        val travelMode: TravelMode = TravelMode.flight,
        val referralInvited: Set<String> = emptySet(),
        val referralRewardGranted: Boolean = false,
        /** Scadenza del Pass inviti (epoch ms), 0 = mai sbloccato. */
        val referralPassUntilMs: Long = 0L,
        val freeGamesUsed: Set<String> = emptySet(),
    ) {
        /** Posto salvato per il volo [flightCode]. */
        fun seat(flightCode: String?): String? = flightCode?.let { seats[it] }

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
                isInviteCardDismissed = prefs.getBoolean(Key.inviteCardDismissed, false),
                isSeatShared = prefs.getBoolean(Key.shareSeat, false),
                seats = prefs.all.mapNotNull { (key, value) ->
                    if (key.startsWith(Key.seatPrefix) && value is String) key.removePrefix(Key.seatPrefix) to value else null
                }.toMap(),
                travelMode = TravelMode.fromRawValue(prefs.getString(Key.travelMode, null)),
                referralInvited = prefs.getStringSet(Key.referralInvited, null)?.toSet() ?: emptySet(),
                referralRewardGranted = prefs.getBoolean(Key.referralRewardGranted, false),
                referralPassUntilMs = prefs.getLong(Key.referralPassUntil, 0L),
                freeGamesUsed = prefs.getStringSet(Key.freeGamesUsed, null)?.toSet() ?: emptySet(),
            )
        }
    }

    /** Solo test: torna ai default in memoria, staccando le preferenze. */
    internal fun resetForTesting() {
        synchronized(this) {
            preferences = null
            _state.value = Snapshot()
            memoryLongs.clear()
            memoryInts.clear()
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

    var isInviteCardDismissed: Boolean
        get() = _state.value.isInviteCardDismissed
        set(value) = update(Key.inviteCardDismissed, value) { it.copy(isInviteCardDismissed = value) }

    /** Solo il flag salvato: il router lo legge a ogni `PRESENCE`. */
    var isSeatShared: Boolean
        get() = _state.value.isSeatShared
        set(value) = update(Key.shareSeat, value) { it.copy(isSeatShared = value) }

    /** Posto salvato per il volo (carta d'imbarco o inserito a mano). */
    fun seat(flightCode: String?): String? = _state.value.seat(flightCode)

    /** Salva (o cancella con `null`) il posto per il volo; il valore deve essere gia' validato. */
    fun setSeat(seat: String?, flightCode: String) {
        synchronized(this) {
            val key = Key.seatPrefix + flightCode
            preferences?.edit()?.apply { if (seat == null) remove(key) else putString(key, seat) }?.apply()
            val seats = _state.value.seats.toMutableMap()
            if (seat == null) seats.remove(flightCode) else seats[flightCode] = seat
            _state.value = _state.value.copy(seats = seats)
        }
    }

    var travelMode: TravelMode
        get() = _state.value.travelMode
        set(value) {
            synchronized(this) {
                preferences?.edit()?.putString(Key.travelMode, value.rawValue)?.apply()
                _state.value = _state.value.copy(travelMode = value)
            }
        }

    var lastEmergencyAtMs: Long
        get() = longValue(Key.lastEmergencyAt)
        set(value) = putLong(Key.lastEmergencyAt, value)

    var firstOpenAtMs: Long
        get() = longValue(Key.firstOpenAt)
        set(value) = putLong(Key.firstOpenAt, value)

    var openCount: Int
        get() = synchronized(this) { preferences?.getInt(Key.openCount, 0) ?: memoryInts[Key.openCount] ?: 0 }
        set(value) {
            synchronized(this) {
                preferences?.edit()?.putInt(Key.openCount, value)?.apply()
                memoryInts[Key.openCount] = value
            }
        }

    var lastReviewRequestAtMs: Long
        get() = longValue(Key.lastReviewRequestAt)
        set(value) = putLong(Key.lastReviewRequestAt, value)

    /** Premio inviti: aggiorna insieme contatore, premio dato e scadenza del Pass locale. */
    fun updateReferral(invited: Set<String>, rewardGranted: Boolean, passUntilMs: Long) {
        synchronized(this) {
            preferences?.edit()
                ?.putStringSet(Key.referralInvited, invited)
                ?.putBoolean(Key.referralRewardGranted, rewardGranted)
                ?.putLong(Key.referralPassUntil, passUntilMs)
                ?.apply()
            _state.value = _state.value.copy(
                referralInvited = invited, referralRewardGranted = rewardGranted, referralPassUntilMs = passUntilMs,
            )
        }
    }

    /** Partite gratis gia' usate (chiavi di [com.stefanobrandi.aerochat.core.growth.FreeGameAllowance]). */
    fun setFreeGamesUsed(keys: Set<String>) {
        synchronized(this) {
            preferences?.edit()?.putStringSet(Key.freeGamesUsed, keys)?.apply()
            _state.value = _state.value.copy(freeGamesUsed = keys)
        }
    }

    /** Valori non osservati: in memoria quando le preferenze non ci sono (test JVM). */
    private val memoryLongs = HashMap<String, Long>()
    private val memoryInts = HashMap<String, Int>()

    private fun longValue(key: String): Long = synchronized(this) { preferences?.getLong(key, 0L) ?: memoryLongs[key] ?: 0L }

    private fun putLong(key: String, value: Long) {
        synchronized(this) {
            preferences?.edit()?.putLong(key, value)?.apply()
            memoryLongs[key] = value
        }
    }

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

/**
 * Modalita' viaggio della scheda principale a terra (RELEASE_1_4_SPEC.md §B10): cambia titolo, sottotitolo e
 * le due azioni. Salvata in [AppSettings.travelMode].
 */
enum class TravelMode(val rawValue: String) {
    flight("flight"),
    ship("ship"),
    festival("festival"),
    mountain("mountain"),
    abroad("abroad");

    companion object {
        fun fromRawValue(raw: String?): TravelMode = entries.firstOrNull { it.rawValue == raw } ?: flight
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
    /** Riscatto di un codice promozionale Google Play (`?code=`). */
    const val playRedeem = "https://play.google.com/redeem"
}
