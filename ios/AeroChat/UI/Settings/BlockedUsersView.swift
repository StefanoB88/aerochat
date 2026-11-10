import SwiftUI

/// Utenti bloccati (chat e Cabina Pubblica) con sblocco a scorrimento.
struct BlockedUsersView: View {

    @Environment(AppEnvironment.self) private var environment
    @State private var blocked: [PeerRecord] = []
    @State private var task: Task<Void, Never>?

    var body: some View {
        ZStack {
            AeroTheme.background.ignoresSafeArea()
            if blocked.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "hand.raised.slash")
                        .font(.system(size: 40))
                        .foregroundStyle(AeroTheme.accentCyan)
                    Text("Nessun utente bloccato")
                        .font(AeroTheme.title(20, weight: .semibold))
                        .foregroundStyle(AeroTheme.textPrimary)
                    Text("Tieni premuto un messaggio per bloccare o segnalare chi lo ha scritto.")
                        .font(.footnote)
                        .foregroundStyle(AeroTheme.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
            } else {
                List {
                    ForEach(blocked, id: \.nodeID) { peer in
                        HStack(spacing: 12) {
                            AvatarView(imageData: peer.avatarImage, colorHex: peer.avatarColorHex, size: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(peer.nickname)
                                    .font(.body.weight(.medium))
                                    .foregroundStyle(AeroTheme.textPrimary)
                                Text(peer.nodeID.hexString)
                                    .font(AeroTheme.monospaced(11))
                                    .foregroundStyle(AeroTheme.textSecondary)
                            }
                            Spacer()
                            Button("Sblocca") { unblock(peer) }
                                .font(.footnote.weight(.semibold))
                                .buttonStyle(.bordered)
                                .tint(AeroTheme.accentCyan)
                        }
                        .listRowBackground(AeroTheme.surface)
                        .swipeActions(edge: .trailing) {
                            Button { unblock(peer) } label: { Label("Sblocca", systemImage: "hand.thumbsup") }
                                .tint(AeroTheme.accentEmerald)
                        }
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
        }
        .navigationTitle("Utenti bloccati")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: start)
        .onDisappear { task?.cancel(); task = nil }
    }

    private func start() {
        guard task == nil, let database = environment.database else { return }
        task = Task {
            for await list in database.peers.observeAll() {
                let filtered = list.filter(\.isBlocked)
                if filtered != blocked { blocked = filtered }
            }
        }
    }

    private func unblock(_ peer: PeerRecord) {
        Haptics.tap()
        Task { await environment.unblockUser(nodeID: peer.nodeID) }
    }
}

/// Segnalazioni salvate su questo iPhone, con possibilita' di inoltrarle via email o eliminarle.
struct ReportsListView: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.openURL) private var openURL
    @State private var reports: [ReportRecord] = []
    @State private var task: Task<Void, Never>?

    var body: some View {
        ZStack {
            AeroTheme.background.ignoresSafeArea()
            if reports.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "checkmark.shield")
                        .font(.system(size: 40))
                        .foregroundStyle(AeroTheme.accentEmerald)
                    Text("Nessuna segnalazione")
                        .font(AeroTheme.title(20, weight: .semibold))
                        .foregroundStyle(AeroTheme.textPrimary)
                }
            } else {
                List {
                    ForEach(reports, id: \.id) { report in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(report.reason.title)
                                    .font(.body.weight(.semibold))
                                    .foregroundStyle(AeroTheme.textPrimary)
                                Spacer()
                                Text(Date(unixMilliseconds: report.createdAtMs), style: .date)
                                    .font(.caption)
                                    .foregroundStyle(AeroTheme.textSecondary)
                            }
                            Text(report.reportedNickname ?? report.reportedNodeID.hexString)
                                .font(.footnote)
                                .foregroundStyle(AeroTheme.accentCyan)
                            if let text = report.textSnapshot {
                                Text(text).font(.footnote).foregroundStyle(AeroTheme.textSecondary).lineLimit(3)
                            }
                        }
                        .listRowBackground(AeroTheme.surface)
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                if let id = report.id { Task { try? await environment.database?.reports.delete(id: id) } }
                            } label: { Label("Elimina", systemImage: "trash") }
                            Button { email(report) } label: { Label("Email", systemImage: "envelope") }
                                .tint(AeroTheme.accentCyan)
                        }
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
        }
        .navigationTitle("Segnalazioni")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard task == nil, let database = environment.database else { return }
            task = Task {
                for await list in database.reports.observeAll() {
                    if list != reports { reports = list }
                }
            }
        }
        .onDisappear { task?.cancel(); task = nil }
    }

    private func email(_ report: ReportRecord) {
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = AppLinks.safetyEmail
        components.queryItems = [
            URLQueryItem(name: "subject", value: String(localized: "Segnalazione AeroChat – \(report.reason.title)")),
            URLQueryItem(name: "body", value: report.emailBody),
        ]
        if let url = components.url { openURL(url) }
    }
}
