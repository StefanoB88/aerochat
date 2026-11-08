import SwiftUI

@MainActor
@Observable
final class ChatListViewModel {

    var conversations: [ConversationRecord] = []
    var reachability: [String: Reachability] = [:]
    var peers: [Data: PeerRecord] = [:]
    private var tasks: [Task<Void, Never>] = []

    func start(environment: AppEnvironment) {
        guard tasks.isEmpty, let database = environment.database else { return }
        tasks.append(Task { [weak self] in
            for await list in database.conversations.observePermanent() {
                guard let self else { break }
                self.conversations = list
                await self.refreshReachability(environment)
            }
        })
        tasks.append(Task { [weak self] in
            for await list in database.peers.observeAll() {
                guard let self else { break }
                self.peers = Dictionary(list.map { ($0.nodeID, $0) }, uniquingKeysWith: { first, _ in first })
            }
        })
        tasks.append(Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(4))
                guard let self else { break }
                await self.refreshReachability(environment)
            }
        })
    }

    func stop() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
    }

    private func refreshReachability(_ environment: AppEnvironment) async {
        guard let router = environment.router else { return }
        var map: [String: Reachability] = [:]
        for conversation in conversations {
            map[conversation.id] = await router.reachability(of: conversation)
        }
        reachability = map
    }
}

/// Tab 1 – Chat & Gruppi permanenti (stile WhatsApp) con le 2 CTA giganti quando la lista e' vuota.
struct ChatListView: View {

    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel = ChatListViewModel()
    @State private var showPairing = false
    @State private var showStoreQR = false
    @State private var showNewGroup = false
    @State private var showPaywall = false

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                if viewModel.conversations.isEmpty {
                    emptyState
                } else {
                    list
                }
            }
            .navigationTitle("AeroChat")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showPaywall = true
                    } label: {
                        Label(environment.isPro ? String(localized: "Pro") : String(localized: "Pass"), systemImage: environment.isPro ? "ticket.fill" : "ticket")
                            .foregroundStyle(environment.isPro ? AeroTheme.accentEmerald : AeroTheme.accentCyan)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { showPairing = true } label: { Label("Aggiungi compagno (QR)", systemImage: "qrcode.viewfinder") }
                        Button { showNewGroup = true } label: { Label("Nuovo gruppo di viaggio", systemImage: "person.3") }
                        Button { showStoreQR = true } label: { Label("Fai scaricare l'app al gate", systemImage: "arrow.down.app") }
                    } label: {
                        Image(systemName: "plus.circle.fill")
                            .foregroundStyle(AeroTheme.accentCyan)
                    }
                }
            }
            .navigationDestination(for: ConversationRecord.self) { conversation in
                ConversationView(conversation: conversation)
            }
        }
        .sheet(isPresented: $showPairing) { QRPairingSheet() }
        .sheet(isPresented: $showStoreQR) { StoreQRSheet() }
        .sheet(isPresented: $showNewGroup) { NewGroupSheet() }
        .sheet(isPresented: $showPaywall) { PaywallView() }
        .onAppear { viewModel.start(environment: environment) }
        .onDisappear { viewModel.stop() }
    }

    private var list: some View {
        List {
            ForEach(viewModel.conversations, id: \.id) { conversation in
                NavigationLink(value: conversation) {
                    row(conversation)
                }
                .listRowBackground(AeroTheme.background)
                .listRowSeparatorTint(AeroTheme.surface)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func row(_ conversation: ConversationRecord) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(avatarColor(conversation))
                    .frame(width: 46, height: 46)
                Image(systemName: conversation.type == .group ? "person.3.fill" : "person.fill")
                    .foregroundStyle(.black.opacity(0.8))
            }
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(conversation.title)
                        .font(AeroTheme.title(17, weight: .semibold))
                        .foregroundStyle(AeroTheme.textPrimary)
                    if conversation.isProUnlocked() {
                        Image(systemName: "ticket.fill").font(.caption2).foregroundStyle(AeroTheme.accentEmerald)
                    }
                    Spacer()
                    if let last = conversation.lastMessageAtMs {
                        Text(Date(unixMilliseconds: last), style: .time)
                            .font(.caption)
                            .foregroundStyle(AeroTheme.textSecondary)
                    }
                }
                HStack {
                    RadioStatusView(reachability: viewModel.reachability[conversation.id] ?? .outOfRange, compact: true)
                    Spacer()
                    if conversation.unreadCount > 0 {
                        Text("\(conversation.unreadCount)")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(AeroTheme.accentCyan, in: Capsule())
                            .foregroundStyle(.black)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func avatarColor(_ conversation: ConversationRecord) -> Color {
        if conversation.type == .group { return AeroTheme.accentEmerald }
        let seed = conversation.recipientID.first ?? 0
        return Color(hex: UInt32(OnboardingView.palette[Int(seed) % OnboardingView.palette.count], radix: 16) ?? 0)
    }

    private var emptyState: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "airplane.circle")
                .font(.system(size: 64))
                .foregroundStyle(AeroTheme.accentCyan)
            Text("Pronti al decollo, \(environment.nickname)")
                .font(AeroTheme.title(26))
                .foregroundStyle(AeroTheme.textPrimary)
            Text("Aggiungi i compagni di volo al gate: a bordo vi troverete da soli via Bluetooth.")
                .font(.footnote)
                .foregroundStyle(AeroTheme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
            ctaButton("Aggiungi compagni di volo (QR)", systemImage: "qrcode.viewfinder", tint: AeroTheme.accentCyan) { showPairing = true }
            ctaButton("Fai scaricare l'app al Gate", systemImage: "arrow.down.app.fill", tint: AeroTheme.accentEmerald) { showStoreQR = true }
            Spacer(minLength: 24)
        }
        .padding(.horizontal, 20)
    }

    private func ctaButton(_ title: String, systemImage: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(AeroTheme.title(18, weight: .bold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 20)
        }
        .buttonStyle(.borderedProminent)
        .tint(tint)
        .foregroundStyle(.black)
    }
}
