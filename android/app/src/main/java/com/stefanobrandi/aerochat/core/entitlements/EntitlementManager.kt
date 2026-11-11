package com.stefanobrandi.aerochat.core.entitlements

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.data.AppDatabase
import com.stefanobrandi.aerochat.data.records.ProTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stato Pro dell'utente locale. */
data class EntitlementState(
    val tier: ProTier = ProTier.free,
    /** Epoch ms (solo pass a tempo: `pass24h`, `pass72h`). */
    val validUntilMs: Long? = null,
    val source: Source = Source.none,
) {
    /** `storeKit` = verificato dallo store di piattaforma (su Android: Google Play Billing). */
    enum class Source(val rawValue: String) {
        none("none"),
        storeKit("storeKit"),
        cachedReceipt("cachedReceipt"),
    }

    /** `now` in epoch ms. */
    fun isPro(now: Long = System.currentTimeMillis()): Boolean = when (tier) {
        ProTier.lifetime -> true
        ProTier.pass24h, ProTier.pass72h -> (validUntilMs ?: 0L) > now
        ProTier.free -> false
    }

    companion object {
        val free = EntitlementState()
    }
}

/**
 * Google Play Billing (port di StoreKit 2): prodotti, acquisti, ascolto degli aggiornamenti e verifica
 * **offline** tramite gli acquisti in cache del Play Store (lifetime) + ricevuta salvata in `identity`
 * (`pro_receipt_jws`: ricevuta Play [PlayReceipt] oppure JWS StoreKit di origine iOS).
 *
 * - `com.aeromesh.lifetime`: prodotto non consumabile, viene **acknowledged** e mai consumato.
 * - `com.aeromesh.pass24h` / `com.aeromesh.pass72h`: prodotti consumabili, vengono **consumati** dopo la
 *   concessione; la validita' (24 h / 72 h, [ProTier.passDuration]) si calcola da `purchaseTime`.
 */
class EntitlementManager(
    context: Context,
    private val database: AppDatabase,
    private val verifier: JWSTransactionVerifier = JWSTransactionVerifier(),
    private val billing: BillingGateway = PlayBillingGateway(context),
    private val playVerifier: PlayPurchaseVerifier = PlayPurchaseVerifier(),
) {

    sealed class Failure(message: String) : Exception(message) {
        data class ProductNotFound(val productID: String) : Failure("product not found: $productID")
        data object Cancelled : Failure("cancelled") {
            private fun readResolve(): Any = Cancelled
        }
        data object Pending : Failure("pending") {
            private fun readResolve(): Any = Pending
        }
        data object Unverified : Failure("unverified") {
            private fun readResolve(): Any = Unverified
        }
        /** Solo Android: errore di Google Play Billing (`BillingResponseCode`). */
        data class BillingError(val responseCode: Int, val debugMessage: String) :
            Failure("billing error $responseCode: $debugMessage")
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updatesJob: Job? = null

    private val _products = MutableStateFlow<List<StoreProduct>>(emptyList())
    /** Prodotti per il paywall, ordinati per prezzo crescente (vuoti finche' non caricati / offline). */
    val products: StateFlow<List<StoreProduct>> = _products.asStateFlow()

    private val _state = MutableStateFlow(EntitlementState.free)
    /** Stato corrente. */
    val state: EntitlementState get() = _state.value

    // MARK: - Ciclo di vita

    suspend fun start() {
        billing.connect()
        refresh()
        listenForTransactions()
        try {
            loadProducts()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        updatesJob?.cancel()
        updatesJob = null
        billing.disconnect()
    }

    /** Stato corrente subito, poi ogni cambiamento (come l'`AsyncStream` iOS; i valori uguali non si ripetono). */
    fun events(): Flow<EntitlementState> = _state.asStateFlow()

    @Throws(Failure::class)
    suspend fun loadProducts() {
        _products.value = billing.queryProducts(productIDs).sortedBy { it.priceAmountMicros }
    }

    /** `product(for:)` di Swift (`for` e' una parola riservata in Kotlin). */
    fun product(id: String): StoreProduct? = _products.value.firstOrNull { it.id == id }

    // MARK: - Verifica

    /**
     * Ricalcola lo stato: prima gli acquisti in cache del Play Store (lifetime, pass non ancora consumato),
     * poi la ricevuta salvata del pass 72h. Gli acquisti non ancora finalizzati vengono applicati
     * (acknowledge / consume), come le transazioni non finite di StoreKit. `now` in epoch ms.
     */
    suspend fun refresh(now: Long = System.currentTimeMillis()) {
        val purchases = try {
            billing.queryPurchases()
        } catch (_: Exception) {
            emptyList()
        }.filter { it.state == BillingPurchase.State.purchased && playVerifier.verify(it) }

        val unfinished = mutex.withLock {
            var next = EntitlementState.free
            for (purchase in purchases) {
                if (MeshProtocol.ProductID.lifetime in purchase.productIDs) {
                    next = EntitlementState(tier = ProTier.lifetime, validUntilMs = null, source = EntitlementState.Source.storeKit)
                }
            }

            if (next.tier == ProTier.free) {
                val receipt = try {
                    database.identities.fetch()?.proReceiptJWS
                } catch (_: Exception) {
                    null
                }
                if (receipt != null) {
                    cachedState(receipt, now)?.let { next = it }
                }
            }

            update(next)
            purchases.filter { needsFinishing(it) }
        }

        for (purchase in unfinished) apply(purchase, now)
    }

    /** Stato da una ricevuta salvata: Play (`gp1.`) o JWS StoreKit. `null` se non valida. */
    private fun cachedState(receipt: String, now: Long): EntitlementState? {
        val play = PlayReceipt.decode(receipt)
        if (play != null) {
            val (json, signature) = play
            if (!playVerifier.verify(json, signature)) return null
            val payload = PlayReceipt.parse(json) ?: return null
            return state(payload, now, EntitlementState.Source.cachedReceipt)
        }
        return try {
            state(from = verifier.verify(receipt), now = now, source = EntitlementState.Source.cachedReceipt)
        } catch (_: Exception) {
            null
        }
    }

    // MARK: - Acquisti

    /**
     * Avvia l'acquisto (serve l'`Activity` che ospita il foglio di Google Play). Ritorna il nuovo stato.
     * @throws Failure `ProductNotFound`, `Cancelled`, `Pending`, `Unverified`, `BillingError`.
     */
    @Throws(Failure::class)
    suspend fun purchase(productID: String, activity: Activity): EntitlementState {
        if (_products.value.isEmpty()) loadProducts()
        product(productID) ?: throw Failure.ProductNotFound(productID)

        when (val outcome = billing.launchPurchase(productID, activity)) {
            is BillingPurchaseOutcome.Success -> {
                val purchase = outcome.purchases.firstOrNull { productID in it.productIDs } ?: throw Failure.Unverified
                when (purchase.state) {
                    BillingPurchase.State.pending -> throw Failure.Pending
                    BillingPurchase.State.unspecified -> throw Failure.Unverified
                    BillingPurchase.State.purchased -> Unit
                }
                if (!playVerifier.verify(purchase)) throw Failure.Unverified
                apply(purchase)
                return state
            }
            BillingPurchaseOutcome.Cancelled -> throw Failure.Cancelled
            BillingPurchaseOutcome.ProductNotFound -> throw Failure.ProductNotFound(productID)
            is BillingPurchaseOutcome.Failed -> {
                if (outcome.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                    // Gia' posseduto (lifetime, o pass non ancora consumato): ripristina.
                    refresh()
                    return state
                }
                if (outcome.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) throw Failure.Cancelled
                throw Failure.BillingError(outcome.responseCode, outcome.debugMessage)
            }
        }
    }

    /** "Ripristina acquisti": rilegge gli acquisti posseduti dal Play Store. */
    suspend fun restorePurchases() {
        billing.connect()
        refresh()
    }

    private fun listenForTransactions() {
        if (updatesJob != null) return
        updatesJob = scope.launch {
            billing.purchaseUpdates().collect { purchases ->
                for (purchase in purchases) {
                    if (purchase.state != BillingPurchase.State.purchased || !playVerifier.verify(purchase)) continue
                    apply(purchase)
                }
            }
        }
    }

    private fun needsFinishing(purchase: BillingPurchase): Boolean =
        (MeshProtocol.ProductID.pass24h in purchase.productIDs) ||
            (MeshProtocol.ProductID.pass72h in purchase.productIDs) ||
            (MeshProtocol.ProductID.lifetime in purchase.productIDs && !purchase.isAcknowledged)

    /** Concede l'acquisto, lo persiste e lo finalizza (acknowledge lifetime / consume pass a tempo). */
    private suspend fun apply(purchase: BillingPurchase, now: Long = System.currentTimeMillis()) {
        val receipt = PlayReceipt.encode(purchase)
        val tier = tier(purchase.productIDs)
        val granted = mutex.withLock {
            var next = state
            val duration = tier?.passDuration
            when {
                tier == null -> return@withLock false
                tier == ProTier.lifetime ->
                    next = EntitlementState(tier = ProTier.lifetime, validUntilMs = null, source = EntitlementState.Source.storeKit)
                duration != null -> {
                    val validUntil = purchase.purchaseTime + duration
                    // Un pass a tempo non declassa un lifetime e non accorcia un pass gia' attivo piu' a lungo.
                    if (next.tier != ProTier.lifetime && validUntil > now && validUntil > (next.validUntilMs ?: 0L)) {
                        next = EntitlementState(tier = tier, validUntilMs = validUntil, source = EntitlementState.Source.storeKit)
                    }
                }
                else -> return@withLock false
            }
            // Un pass piu' corto di quello attivo viene solo finalizzato: la ricevuta salvata resta quella migliore.
            if (tier == ProTier.lifetime || next != state) {
                try {
                    database.identities.updateProStatus(tier = next.tier, validUntilMs = next.validUntilMs, receiptJWS = receipt)
                } catch (_: Exception) {
                }
                update(next)
            }
            true
        }
        if (!granted) return
        // Equivalente di `transaction.finish()`.
        if (MeshProtocol.ProductID.lifetime in purchase.productIDs) {
            if (!purchase.isAcknowledged) billing.acknowledge(purchase.purchaseToken)
        } else {
            billing.consume(purchase.purchaseToken)
        }
    }

    /** Iniezione diretta di uno stato (test e ripristini): persiste e notifica. */
    suspend fun applyVerifiedState(next: EntitlementState, receiptJWS: String?) {
        mutex.withLock {
            try {
                database.identities.updateProStatus(tier = next.tier, validUntilMs = next.validUntilMs, receiptJWS = receiptJWS)
            } catch (_: Exception) {
            }
            update(next)
        }
    }

    private fun update(next: EntitlementState) {
        if (next == _state.value) return
        _state.value = next
    }

    /** Rilascia lo scope interno (fine vita dell'oggetto). */
    fun close() {
        stop()
        scope.cancel()
    }

    companion object {
        val productIDs: List<String> =
            listOf(MeshProtocol.ProductID.pass24h, MeshProtocol.ProductID.pass72h, MeshProtocol.ProductID.lifetime)

        /** Livello piu' alto tra i prodotti di un acquisto (lifetime > 72h > 24h). */
        internal fun tier(productIDs: List<String>): ProTier? = when {
            MeshProtocol.ProductID.lifetime in productIDs -> ProTier.lifetime
            MeshProtocol.ProductID.pass72h in productIDs -> ProTier.pass72h
            MeshProtocol.ProductID.pass24h in productIDs -> ProTier.pass24h
            else -> null
        }

        /** Stato da un payload JWS StoreKit verificato. `now` in epoch ms. */
        fun state(from: JWSTransactionPayload, now: Long, source: EntitlementState.Source): EntitlementState =
            state(productID = from.productId, purchaseTime = from.purchaseDate, now = now, source = source)

        /** Stato da una ricevuta Play verificata. `now` in epoch ms. */
        fun state(from: PlayReceipt.Payload, now: Long, source: EntitlementState.Source): EntitlementState {
            val productID = tier(from.productIDs)?.productID ?: return EntitlementState.free
            return state(productID = productID, purchaseTime = from.purchaseTime, now = now, source = source)
        }

        private fun state(productID: String, purchaseTime: Long, now: Long, source: EntitlementState.Source): EntitlementState {
            val tier = ProTier.fromProductID(productID) ?: return EntitlementState.free
            if (tier == ProTier.lifetime) return EntitlementState(tier = ProTier.lifetime, validUntilMs = null, source = source)
            val duration = tier.passDuration ?: return EntitlementState.free
            val validUntil = purchaseTime + duration
            return if (validUntil > now) EntitlementState(tier = tier, validUntilMs = validUntil, source = source)
            else EntitlementState.free
        }
    }
}
