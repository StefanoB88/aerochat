package com.stefanobrandi.aerochat.core.entitlements

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.stefanobrandi.aerochat.core.protocol.base64URLDecoded
import com.stefanobrandi.aerochat.core.protocol.base64URLEncodedString
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Prodotto in vendita per il paywall (equivalente di `StoreKit.Product`: `id`, `displayName`,
 * `description`, `displayPrice`, `price`).
 */
data class StoreProduct(
    val id: String,
    val displayName: String,
    val description: String,
    /** Prezzo formattato dallo store nella valuta dell'utente (es. "4,99 €"). */
    val displayPrice: String,
    val priceAmountMicros: Long,
    val currencyCode: String,
) {
    /** Prezzo numerico (unita' di valuta). */
    val price: Double get() = priceAmountMicros / 1_000_000.0
}

/** Acquisto Play neutrale rispetto alla libreria (testabile senza `com.android.billingclient`). */
data class BillingPurchase(
    val productIDs: List<String>,
    val purchaseToken: String,
    /** Epoch ms. */
    val purchaseTime: Long,
    val state: State,
    val isAcknowledged: Boolean,
    val orderID: String?,
    /** JSON firmato da Google Play (`Purchase.getOriginalJson()`). */
    val originalJson: String,
    /** Firma RSA/SHA1 Base64 di [originalJson] (`Purchase.getSignature()`). */
    val signature: String,
) {
    enum class State { purchased, pending, unspecified }
}

/** Esito di un flusso d'acquisto. */
sealed interface BillingPurchaseOutcome {
    data class Success(val purchases: List<BillingPurchase>) : BillingPurchaseOutcome
    data object Cancelled : BillingPurchaseOutcome
    data object ProductNotFound : BillingPurchaseOutcome
    /** Codice `BillingClient.BillingResponseCode`. */
    data class Failed(val responseCode: Int, val debugMessage: String) : BillingPurchaseOutcome
}

/** Facciata minima su Google Play Billing: permette di testare [EntitlementManager] con un fake. */
interface BillingGateway {
    /** Connette (o riconnette) il servizio. `false` se Play non e' disponibile (offline, niente Play Store). */
    suspend fun connect(): Boolean
    fun disconnect()
    /** Acquisti arrivati fuori da [launchPurchase] (pending completati, acquisti dal Play Store, promo). */
    fun purchaseUpdates(): Flow<List<BillingPurchase>>
    /** Prodotti in-app per gli id indicati (lista vuota se non disponibili). */
    @Throws(EntitlementManager.Failure::class)
    suspend fun queryProducts(productIDs: List<String>): List<StoreProduct>
    /** Acquisti in-app posseduti e non consumati (cache locale del Play Store, funziona offline). */
    @Throws(EntitlementManager.Failure::class)
    suspend fun queryPurchases(): List<BillingPurchase>
    /** Avvia il flusso d'acquisto e attende l'esito. */
    suspend fun launchPurchase(productID: String, activity: Activity): BillingPurchaseOutcome
    suspend fun acknowledge(purchaseToken: String): Boolean
    suspend fun consume(purchaseToken: String): Boolean
}

/**
 * Ricevuta Play salvata in `identity.pro_receipt_jws`: `gp1.<base64url(originalJson)>.<base64url(signature)>`.
 * Il prefisso la distingue da un JWS StoreKit (3 parti separate da punto, header JSON).
 */
object PlayReceipt {
    const val prefix = "gp1"

    fun encode(originalJson: String, signature: String): String =
        "$prefix.${originalJson.toByteArray(Charsets.UTF_8).base64URLEncodedString()}." +
            signature.toByteArray(Charsets.UTF_8).base64URLEncodedString()

    fun encode(purchase: BillingPurchase): String = encode(purchase.originalJson, purchase.signature)

    /** `(originalJson, signature)` o `null` se la stringa non e' una ricevuta Play. */
    fun decode(receipt: String): Pair<String, String>? {
        val parts = receipt.split(".")
        if (parts.size != 3 || parts[0] != prefix) return null
        val json = parts[1].base64URLDecoded() ?: return null
        val signature = parts[2].base64URLDecoded() ?: return null
        return String(json, Charsets.UTF_8) to String(signature, Charsets.UTF_8)
    }

    /** Campi utili del JSON Play (`productId`/`productIds`, `purchaseTime`, `purchaseToken`). */
    data class Payload(val productIDs: List<String>, val purchaseTime: Long, val purchaseToken: String?)

    fun parse(originalJson: String): Payload? = try {
        val json = JSONObject(originalJson)
        val ids = json.optJSONArray("productIds")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            ?: listOfNotNull(json.optStringOrNull("productId"))
        if (ids.isEmpty() || !json.has("purchaseTime")) null
        else Payload(ids, json.getLong("purchaseTime"), json.optStringOrNull("purchaseToken"))
    } catch (_: Exception) {
        null
    }
}

/**
 * Verifica locale della firma Play (`SHA1withRSA` con la chiave di licenza Base64 della Play Console).
 * Senza chiave configurata ([base64PublicKey] `null`) la firma non e' verificabile e l'acquisto viene accettato
 * (la libreria Billing riceve gli acquisti direttamente dal Play Store via IPC).
 */
class PlayPurchaseVerifier(val base64PublicKey: String? = null) {

    fun verify(originalJson: String, signature: String): Boolean {
        val keyString = base64PublicKey ?: return true
        if (signature.isEmpty()) return false
        return try {
            val keyBytes = java.util.Base64.getDecoder().decode(keyString)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance("SHA1withRSA")
            verifier.initVerify(publicKey)
            verifier.update(originalJson.toByteArray(Charsets.UTF_8))
            verifier.verify(java.util.Base64.getDecoder().decode(signature))
        } catch (_: Exception) {
            false
        }
    }

    fun verify(purchase: BillingPurchase): Boolean = verify(purchase.originalJson, purchase.signature)
}

/** Implementazione reale su `BillingClient` (billing-ktx 7.x). */
class PlayBillingGateway(context: Context) : BillingGateway {

    private val updates = MutableSharedFlow<List<BillingPurchase>>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val connectMutex = Mutex()
    private val purchaseMutex = Mutex()
    private val productDetails = java.util.concurrent.ConcurrentHashMap<String, ProductDetails>()

    @Volatile
    private var pendingFlow: CompletableDeferred<BillingPurchaseOutcome>? = null

    private val listener = PurchasesUpdatedListener { result, purchases ->
        val mapped = purchases.orEmpty().map { it.toBillingPurchase() }
        val waiting = pendingFlow
        if (waiting != null && !waiting.isCompleted) {
            pendingFlow = null
            waiting.complete(
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> BillingPurchaseOutcome.Success(mapped)
                    BillingClient.BillingResponseCode.USER_CANCELED -> BillingPurchaseOutcome.Cancelled
                    else -> BillingPurchaseOutcome.Failed(result.responseCode, result.debugMessage)
                },
            )
        } else if (result.responseCode == BillingClient.BillingResponseCode.OK && mapped.isNotEmpty()) {
            updates.tryEmit(mapped)
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    override suspend fun connect(): Boolean = connectMutex.withLock {
        if (client.isReady) return@withLock true
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { continuation ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (continuation.isActive) {
                            continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
            }
        } ?: false
    }

    override fun disconnect() {
        client.endConnection()
    }

    override fun purchaseUpdates(): Flow<List<BillingPurchase>> = updates.asSharedFlow()

    override suspend fun queryProducts(productIDs: List<String>): List<StoreProduct> {
        if (!connect()) throw EntitlementManager.Failure.BillingError(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "billing non disponibile",
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIDs.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                },
            )
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            throw EntitlementManager.Failure.BillingError(result.billingResult.responseCode, result.billingResult.debugMessage)
        }
        val details = result.productDetailsList.orEmpty()
        details.forEach { productDetails[it.productId] = it }
        return details.mapNotNull { detail ->
            val offer = detail.oneTimePurchaseOfferDetails ?: return@mapNotNull null
            StoreProduct(
                id = detail.productId,
                displayName = detail.name,
                description = detail.description,
                displayPrice = offer.formattedPrice,
                priceAmountMicros = offer.priceAmountMicros,
                currencyCode = offer.priceCurrencyCode,
            )
        }
    }

    override suspend fun queryPurchases(): List<BillingPurchase> {
        if (!connect()) throw EntitlementManager.Failure.BillingError(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "billing non disponibile",
        )
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        )
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            throw EntitlementManager.Failure.BillingError(result.billingResult.responseCode, result.billingResult.debugMessage)
        }
        return result.purchasesList.map { it.toBillingPurchase() }
    }

    override suspend fun launchPurchase(productID: String, activity: Activity): BillingPurchaseOutcome =
        purchaseMutex.withLock {
            if (!connect()) {
                return@withLock BillingPurchaseOutcome.Failed(
                    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "billing non disponibile",
                )
            }
            val details = productDetails[productID]
                ?: runCatching { queryProducts(listOf(productID)) }.let { productDetails[productID] }
                ?: return@withLock BillingPurchaseOutcome.ProductNotFound
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()),
                )
                .build()
            val deferred = CompletableDeferred<BillingPurchaseOutcome>()
            pendingFlow = deferred
            // `launchBillingFlow` va chiamato sul main thread.
            val launch = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
            if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
                pendingFlow = null
                return@withLock if (launch.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                    BillingPurchaseOutcome.Cancelled
                } else {
                    BillingPurchaseOutcome.Failed(launch.responseCode, launch.debugMessage)
                }
            }
            try {
                deferred.await()
            } finally {
                if (pendingFlow === deferred) pendingFlow = null
            }
        }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        if (!connect()) return false
        val result = client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build(),
        )
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    override suspend fun consume(purchaseToken: String): Boolean {
        if (!connect()) return false
        val result = client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build())
        return result.billingResult.responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun Purchase.toBillingPurchase(): BillingPurchase = BillingPurchase(
        productIDs = products,
        purchaseToken = purchaseToken,
        purchaseTime = purchaseTime,
        state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> BillingPurchase.State.purchased
            Purchase.PurchaseState.PENDING -> BillingPurchase.State.pending
            else -> BillingPurchase.State.unspecified
        },
        isAcknowledged = isAcknowledged,
        orderID = orderId,
        originalJson = originalJson,
        signature = signature,
    )
}

