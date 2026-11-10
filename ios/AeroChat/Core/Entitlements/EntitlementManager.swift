import Foundation
import StoreKit

/// Stato Pro dell'utente locale.
struct EntitlementState: Sendable, Equatable {
    enum Source: String, Sendable {
        case none
        case storeKit
        case cachedReceipt
    }

    var tier: ProTier = .free
    var validUntilMs: Int64?
    var source: Source = .none

    static let free = EntitlementState()

    func isPro(now: Date = Date()) -> Bool {
        switch tier {
        case .lifetime: return true
        case .pass24h, .pass72h: return (validUntilMs ?? 0) > now.unixMilliseconds
        case .free: return false
        }
    }
}

extension ProTier {
    /// Durata del pass a tempo (nil per free e lifetime).
    var passDuration: TimeInterval? {
        switch self {
        case .pass24h: return MeshProtocol.singleFlightPassDuration
        case .pass72h: return MeshProtocol.proPassDuration
        case .free, .lifetime: return nil
        }
    }

    init?(productID: String) {
        switch productID {
        case MeshProtocol.ProductID.pass24h: self = .pass24h
        case MeshProtocol.ProductID.pass72h: self = .pass72h
        case MeshProtocol.ProductID.lifetime: self = .lifetime
        default: return nil
        }
    }

    var productID: String? {
        switch self {
        case .pass24h: return MeshProtocol.ProductID.pass24h
        case .pass72h: return MeshProtocol.ProductID.pass72h
        case .lifetime: return MeshProtocol.ProductID.lifetime
        case .free: return nil
        }
    }
}

/// StoreKit 2: prodotti, acquisti, ascolto delle transazioni e verifica **offline** tramite
/// `Transaction.currentEntitlements` (cache locale firmata) + ricevuta JWS salvata in `identity`.
actor EntitlementManager {

    enum Failure: Error, Equatable {
        case productNotFound(String)
        case cancelled
        case pending
        case unverified
    }

    static let productIDs = [MeshProtocol.ProductID.pass24h, MeshProtocol.ProductID.pass72h, MeshProtocol.ProductID.lifetime]

    private let database: AppDatabase
    private let verifier: JWSTransactionVerifier
    private(set) var products: [Product] = []
    private(set) var state = EntitlementState.free
    private var updatesTask: Task<Void, Never>?
    private var subscribers: [UUID: AsyncStream<EntitlementState>.Continuation] = [:]

    init(database: AppDatabase, verifier: JWSTransactionVerifier = JWSTransactionVerifier()) {
        self.database = database
        self.verifier = verifier
    }

    // MARK: - Ciclo di vita

    func start() async {
        await refresh()
        listenForTransactions()
        try? await loadProducts()
    }

    func stop() {
        updatesTask?.cancel()
        updatesTask = nil
    }

    func events() -> AsyncStream<EntitlementState> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(8)) { continuation in
            subscribers[id] = continuation
            continuation.yield(state)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.removeSubscriber(id) }
            }
        }
    }

    func loadProducts() async throws {
        products = try await Product.products(for: Self.productIDs).sorted { $0.price < $1.price }
    }

    func product(for id: String) -> Product? {
        products.first { $0.id == id }
    }

    // MARK: - Verifica

    /// Ricalcola lo stato: prima la cache firmata di StoreKit (lifetime), poi la ricevuta JWS del pass 72h.
    func refresh(now: Date = Date()) async {
        var next = EntitlementState.free

        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result, transaction.revocationDate == nil else { continue }
            if transaction.productID == MeshProtocol.ProductID.lifetime {
                next = EntitlementState(tier: .lifetime, validUntilMs: nil, source: .storeKit)
            }
        }

        if next.tier == .free, let record = try? await database.identities.fetch(), let jws = record.proReceiptJWS {
            if let payload = try? verifier.verify(jws) {
                next = Self.state(from: payload, now: now, source: .cachedReceipt)
            }
        }

        await update(to: next)
    }

    static func state(from payload: JWSTransactionPayload, now: Date, source: EntitlementState.Source) -> EntitlementState {
        guard let tier = ProTier(productID: payload.productId) else { return .free }
        if tier == .lifetime {
            return EntitlementState(tier: .lifetime, validUntilMs: nil, source: source)
        }
        guard let duration = tier.passDuration else { return .free }
        let validUntil = payload.purchaseDate + Int64(duration * 1_000)
        guard validUntil > now.unixMilliseconds else { return .free }
        return EntitlementState(tier: tier, validUntilMs: validUntil, source: source)
    }

    // MARK: - Acquisti

    @discardableResult
    func purchase(_ productID: String) async throws -> EntitlementState {
        if products.isEmpty { try await loadProducts() }
        guard let product = product(for: productID) else { throw Failure.productNotFound(productID) }

        let result = try await product.purchase()
        switch result {
        case .success(let verification):
            guard case .verified(let transaction) = verification else { throw Failure.unverified }
            await apply(transaction, jws: verification.jwsRepresentation)
            await transaction.finish()
            return state
        case .userCancelled:
            throw Failure.cancelled
        case .pending:
            throw Failure.pending
        @unknown default:
            throw Failure.unverified
        }
    }

    func restorePurchases() async {
        try? await AppStore.sync()
        await refresh()
    }

    private func listenForTransactions() {
        guard updatesTask == nil else { return }
        updatesTask = Task { [weak self] in
            for await result in Transaction.updates {
                guard let self else { break }
                guard case .verified(let transaction) = result else { continue }
                await self.apply(transaction, jws: result.jwsRepresentation)
                await transaction.finish()
            }
        }
    }

    private func apply(_ transaction: Transaction, jws: String, now: Date = Date()) async {
        guard transaction.revocationDate == nil else {
            await refresh(now: now)
            return
        }
        var next = state
        guard let tier = ProTier(productID: transaction.productID) else { return }
        if tier == .lifetime {
            next = EntitlementState(tier: .lifetime, validUntilMs: nil, source: .storeKit)
        } else if let duration = tier.passDuration {
            let validUntil = transaction.purchaseDate.addingTimeInterval(duration).unixMilliseconds
            // Un pass a tempo non declassa un lifetime e non accorcia un pass gia' attivo piu' a lungo.
            if next.tier != .lifetime, validUntil > now.unixMilliseconds, validUntil > (next.validUntilMs ?? 0) {
                next = EntitlementState(tier: tier, validUntilMs: validUntil, source: .storeKit)
            } else if next.tier != .lifetime, validUntil <= (next.validUntilMs ?? 0) {
                return
            }
        }
        try? await database.identities.updateProStatus(tier: next.tier, validUntilMs: next.validUntilMs, receiptJWS: jws)
        await update(to: next)
    }

    /// Iniezione diretta di uno stato (test e ripristini): persiste e notifica.
    func applyVerifiedState(_ next: EntitlementState, receiptJWS: String?) async {
        try? await database.identities.updateProStatus(tier: next.tier, validUntilMs: next.validUntilMs, receiptJWS: receiptJWS)
        await update(to: next)
    }

    private func update(to next: EntitlementState) async {
        guard next != state else { return }
        state = next
        for continuation in subscribers.values {
            continuation.yield(next)
        }
    }

    private func removeSubscriber(_ id: UUID) {
        subscribers.removeValue(forKey: id)
    }
}
