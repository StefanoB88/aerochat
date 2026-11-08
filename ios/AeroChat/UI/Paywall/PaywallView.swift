import SwiftUI
import StoreKit

/// Flight Group Pass: Pass Viaggio 72 ore (0,49 €) e Frequent Flyer Lifetime (2,49 €). Zero abbonamenti.
struct PaywallView: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var products: [Product] = []
    @State private var purchasing: String?
    @State private var message: String?

    private struct Offer: Identifiable {
        let id: String
        let title: String
        let subtitle: String
        let fallbackPrice: String
    }

    private var offers: [Offer] {
        [
            Offer(id: MeshProtocol.ProductID.pass72h,
                  title: String(localized: "Pass Viaggio 72 Ore"),
                  subtitle: String(localized: "Andata, soggiorno e ritorno. Sblocca il tuo gruppo di viaggio: 1 paga, tutti usano."),
                  fallbackPrice: "0,49 €"),
            Offer(id: MeshProtocol.ProductID.lifetime,
                  title: String(localized: "Frequent Flyer Lifetime"),
                  subtitle: String(localized: "Per sempre, su ogni volo, crociera e festival. Nessun abbonamento."),
                  fallbackPrice: "2,49 €"),
        ]
    }

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        header
                        ForEach(offers) { offer in
                            offerCard(offer)
                        }
                        if let message {
                            Text(message).font(.footnote).foregroundStyle(AeroTheme.textSecondary)
                        }
                        Button("Ripristina acquisti") {
                            Task {
                                await environment.restorePurchases()
                                message = environment.isPro
                                    ? String(localized: "Acquisti ripristinati.")
                                    : String(localized: "Nessun acquisto trovato.")
                            }
                        }
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AeroTheme.accentCyan)
                        .frame(maxWidth: .infinity)
                        Text("Gratis per sempre: chat 1-a-1 di testo, emoji, Cabina Pubblica e consegna differita. Con il Pass: gruppi 3+, note vocali Opus, foto WebP e mini-giochi — anche per i compagni Android con app gratuita.")
                            .font(.caption)
                            .foregroundStyle(AeroTheme.textSecondary)
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Flight Group Pass")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() } }
            }
        }
        .task {
            if let entitlements = environment.entitlements {
                if await entitlements.products.isEmpty { try? await entitlements.loadProducts() }
                products = await entitlements.products
                if products.isEmpty { message = String(localized: "Prezzi non disponibili offline: l'acquisto richiede una connessione.") }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: "ticket.fill")
                .font(.system(size: 36))
                .foregroundStyle(AeroTheme.accentEmerald)
            Group {
                if environment.isPro {
                    Text("Sei a bordo in classe Pro")
                } else {
                    Text("Un biglietto, tutto il gruppo")
                }
            }
            .font(AeroTheme.title(28))
            .foregroundStyle(AeroTheme.textPrimary)
            if environment.isPro, let until = environment.entitlementState.validUntilMs, environment.entitlementState.tier == .pass72h {
                Text("Pass attivo fino a \(Date(unixMilliseconds: until).formatted(date: .abbreviated, time: .shortened))")
                    .font(.footnote).foregroundStyle(AeroTheme.accentEmerald)
            }
        }
    }

    private func offerCard(_ offer: Offer) -> some View {
        let product = products.first { $0.id == offer.id }
        let isCurrent = environment.entitlementState.tier == .lifetime && offer.id == MeshProtocol.ProductID.lifetime
        let displayName = (product?.displayName).flatMap { $0.isEmpty ? nil : $0 } ?? offer.title
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(displayName)
                    .font(AeroTheme.title(18, weight: .semibold))
                Spacer()
                Text(product?.displayPrice ?? offer.fallbackPrice)
                    .font(AeroTheme.title(18, weight: .bold))
                    .foregroundStyle(AeroTheme.accentCyan)
            }
            Text(offer.subtitle)
                .font(.footnote)
                .foregroundStyle(AeroTheme.textSecondary)
            Button {
                Task { await purchase(offer.id) }
            } label: {
                HStack {
                    if purchasing == offer.id { ProgressView().tint(.black) }
                    if isCurrent {
                        Text("Attivo").font(.headline)
                    } else {
                        Text("Acquista").font(.headline)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 28)
                .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .tint(offer.id == MeshProtocol.ProductID.lifetime ? AeroTheme.accentEmerald : AeroTheme.accentCyan)
            .foregroundStyle(.black)
            .disabled(isCurrent || purchasing != nil || product == nil)
            .hapticOnTap()
        }
        .padding(16)
        .foregroundStyle(AeroTheme.textPrimary)
        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: AeroTheme.cornerRadius, style: .continuous))
    }

    private func purchase(_ productID: String) async {
        purchasing = productID
        defer { purchasing = nil }
        do {
            try await environment.purchase(productID: productID)
            Haptics.success()
            message = String(localized: "Grazie! Il pass è attivo e viene propagato ai tuoi gruppi.")
        } catch EntitlementManager.Failure.cancelled {
            message = nil
        } catch {
            Haptics.error()
            message = String(localized: "Acquisto non completato: \(error.localizedDescription)")
        }
    }
}
