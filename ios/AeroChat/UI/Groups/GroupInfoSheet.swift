import SwiftUI

/// Stato della scheda "Info gruppo": membri osservati dal database e stato radio di ognuno.
@MainActor
@Observable
final class GroupInfoViewModel {

    private(set) var members: [GroupMemberRecord] = []
    /// Profili dei membri (foto, colore, nickname aggiornato, ultimo avvistamento) per `node_id`.
    private(set) var peers: [Data: PeerRecord] = [:]
    private(set) var reachability: [Data: Reachability] = [:]

    private let environment: AppEnvironment
    private let conversationID: String
    private var tasks: [Task<Void, Never>] = []

    init(conversationID: String, environment: AppEnvironment) {
        self.conversationID = conversationID
        self.environment = environment
    }

    /// Membri ordinati per nickname (ordine stabile: non salta a ogni aggiornamento radio).
    var sortedMembers: [GroupMemberRecord] {
        members.sorted { nickname(of: $0).localizedCaseInsensitiveCompare(nickname(of: $1)) == .orderedAscending }
    }

    /// Partecipanti totali, noi compresi.
    var participantCount: Int { members.count + 1 }

    func nickname(of member: GroupMemberRecord) -> String {
        peers[member.nodeID]?.nickname ?? member.nickname
    }

    /// "Scrivi" solo verso compagni noti e non bloccati.
    func canMessage(_ member: GroupMemberRecord) -> Bool {
        guard let peer = peers[member.nodeID] else { return false }
        return !peer.isBlocked
    }

    func start() {
        guard tasks.isEmpty, let database = environment.database else { return }
        let id = conversationID
        tasks.append(Task { [weak self] in
            for await list in database.conversations.observeGroupMembers(of: id) {
                guard let self else { break }
                if list != self.members {
                    self.members = list
                    await self.refreshReachability()
                }
            }
        })
        tasks.append(Task { [weak self] in
            for await list in database.peers.observeAll() {
                guard let self else { break }
                let map = Dictionary(list.map { ($0.nodeID, $0) }, uniquingKeysWith: { first, _ in first })
                if map != self.peers { self.peers = map }
            }
        })
        // Stesso ritmo dell'header della chat (3 s).
        tasks.append(Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { break }
                // Solo con l'app in primo piano (in background la scheda non e' visibile).
                if UIApplication.shared.applicationState == .active {
                    await self.refreshReachability()
                }
                try? await Task.sleep(for: .seconds(3))
            }
        })
    }

    func stop() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
    }

    private func refreshReachability() async {
        guard let router = environment.router else { return }
        var map: [Data: Reachability] = [:]
        for member in members {
            map[member.nodeID] = await router.reachability(of: member.nodeID)
        }
        if map != reachability { reachability = map }
    }

    /// Chat 1-a-1 con il membro (creata se non esiste ancora).
    func directConversation(with member: GroupMemberRecord) async -> ConversationRecord? {
        guard let peer = peers[member.nodeID], let database = environment.database else { return nil }
        return try? await database.conversations.ensureDirectConversation(with: peer)
    }
}

/// Scheda aperta toccando il nome del gruppo: partecipanti con il loro stato radio.
struct GroupInfoSheet: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: GroupInfoViewModel?
    @State private var showPaywall = false
    @State private var isGifting = false
    @State private var giftMessage: String?
    let group: ConversationRecord
    /// Apre la chat 1-a-1 scelta con "Scrivi" (la scheda si chiude da sola).
    var onOpenDirectChat: (ConversationRecord) -> Void = { _ in }

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                if let viewModel {
                    content(viewModel)
                }
            }
            .navigationTitle("Info gruppo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() } }
            }
        }
        .task {
            if viewModel == nil {
                viewModel = GroupInfoViewModel(conversationID: group.id, environment: environment)
            }
            viewModel?.start()
        }
        .onDisappear { viewModel?.stop() }
        .preferredColorScheme(.dark)
    }

    private func content(_ viewModel: GroupInfoViewModel) -> some View {
        List {
            Section {
                VStack(spacing: 8) {
                    AvatarView(imageData: nil, colorHex: "10B981", systemImage: "person.3.fill", size: 72)
                    Text(group.title)
                        .font(AeroTheme.title(22, weight: .semibold))
                        .foregroundStyle(AeroTheme.textPrimary)
                        .multilineTextAlignment(.center)
                    Text("\(viewModel.participantCount) partecipanti")
                        .font(.subheadline)
                        .foregroundStyle(AeroTheme.textSecondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .accessibilityElement(children: .combine)
            }
            .listRowBackground(Color.clear)

            passSection

            Section("Partecipanti") {
                ownRow
                ForEach(viewModel.sortedMembers, id: \.nodeID) { member in
                    memberRow(member, viewModel: viewModel)
                }
            }
            .listRowBackground(AeroTheme.surface)
        }
        .scrollContentBackground(.hidden)
        .sheet(isPresented: $showPaywall) { PaywallView() }
    }

    /// "1 paga, tutti usano": regala il Pass al gruppo (o compra il tuo per farlo).
    private var passSection: some View {
        Section {
            if group.isProUnlocked() {
                Label("Pass attivo per tutto il gruppo", systemImage: "ticket.fill")
                    .foregroundStyle(AeroTheme.accentEmerald)
                if let until = group.proUnlockedUntilMs {
                    Text("Foto, vocali e giochi sbloccati fino a \(Date(unixMilliseconds: until).formatted(date: .abbreviated, time: .shortened)).")
                        .font(.footnote)
                        .foregroundStyle(AeroTheme.textSecondary)
                }
            } else {
                Button {
                    Haptics.tap()
                    Task { await giftPass() }
                } label: {
                    HStack {
                        Label(environment.isPro ? "Regala il Pass a tutto il gruppo" : "Sblocca il gruppo con un Pass", systemImage: "gift.fill")
                            .foregroundStyle(AeroTheme.textPrimary)
                        Spacer()
                        if isGifting { ProgressView().tint(AeroTheme.accentCyan) }
                    }
                }
                .disabled(isGifting)
            }
            if let giftMessage {
                Text(giftMessage).font(.footnote).foregroundStyle(AeroTheme.accentEmerald)
            }
        } footer: {
            Text("Basta un Pass nel gruppo: chi lo possiede lo condivide con tutti i membri, anche Android e con app gratuita.")
        }
        .listRowBackground(AeroTheme.surface)
    }

    private func giftPass() async {
        guard environment.isPro, let proPass = environment.proPass else {
            showPaywall = true
            return
        }
        isGifting = true
        defer { isGifting = false }
        if (try? await proPass.broadcastGrantIfEntitled(for: group)) == true {
            Haptics.success()
            giftMessage = String(localized: "Pass inviato ai membri a portata; gli altri lo ricevono appena si collegano.")
        }
    }

    private var ownRow: some View {
        HStack(spacing: 12) {
            AvatarView(imageData: environment.avatarImage, colorHex: environment.avatarColorHex, size: 40)
            VStack(alignment: .leading, spacing: 3) {
                Text(environment.nickname)
                    .font(.body.weight(.medium))
                    .foregroundStyle(AeroTheme.textPrimary)
                    .lineLimit(1)
                Text("Tu")
                    .font(.caption)
                    .foregroundStyle(AeroTheme.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }

    private func memberRow(_ member: GroupMemberRecord, viewModel: GroupInfoViewModel) -> some View {
        let peer = viewModel.peers[member.nodeID]
        let reachability = viewModel.reachability[member.nodeID] ?? .outOfRange
        return HStack(spacing: 12) {
            AvatarView(imageData: peer?.avatarImage, colorHex: peer?.avatarColorHex, size: 40)
            VStack(alignment: .leading, spacing: 3) {
                Text(viewModel.nickname(of: member))
                    .font(.body.weight(.medium))
                    .foregroundStyle(AeroTheme.textPrimary)
                    .lineLimit(1)
                HStack(spacing: 4) {
                    RadioStatusView(reachability: reachability, compact: true)
                    if reachability == .outOfRange, let seenAt = peer?.lastSeenAtMs {
                        LastSeenLabel(date: Date(unixMilliseconds: seenAt))
                    }
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
            if viewModel.canMessage(member) {
                Button {
                    Haptics.tap()
                    Task {
                        guard let direct = await viewModel.directConversation(with: member) else { return }
                        onOpenDirectChat(direct)
                        dismiss()
                    }
                } label: {
                    Image(systemName: "bubble.left.fill")
                        .foregroundStyle(AeroTheme.accentCyan)
                        .frame(width: 36, height: 36)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("Scrivi a \(viewModel.nickname(of: member))"))
            }
        }
    }
}

/// "· visto 5 min fa", aggiornato ogni minuto.
private struct LastSeenLabel: View {

    let date: Date

    var body: some View {
        TimelineView(.everyMinute) { _ in
            let relative = date.formatted(.relative(presentation: .named, unitsStyle: .abbreviated))
            Text("· visto \(relative)")
                .font(.caption)
                .foregroundStyle(AeroTheme.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
    }
}
