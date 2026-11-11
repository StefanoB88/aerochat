import SwiftUI

/// "Persone vicine": chi ha AeroChat a portata di Bluetooth (PRESENCE), con "Saluta" e "Sfida", e le
/// richieste ricevute da accettare o rifiutare.
struct NearbySheet: View {

    var initialFilter: NearbyFilter = .all

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @AppStorage(AppSettings.Key.nearbyVisible) private var isVisible = true
    @State private var filter: NearbyFilter = .all
    @State private var peers: [Data: PeerRecord] = [:]
    @State private var challengeTarget: NearbyPerson?
    @State private var info: String?
    @State private var showPairing = false

    private var myFlightCode: String? { environment.activeFlightLounge?.flightCode }

    private var visiblePeople: [NearbyPerson] {
        let people = environment.presence.people
        switch filter {
        case .all:
            return people
        case .games:
            return people.filter(\.openToGames)
        case .flight:
            // Prima chi e' sul nostro volo, poi gli altri.
            let onFlight = people.filter { myFlightCode != nil && $0.flightCode == myFlightCode }
            return onFlight + people.filter { !onFlight.contains($0) }
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if !environment.contactRequests.incoming.isEmpty {
                    Section("Richieste") {
                        ForEach(environment.contactRequests.incoming) { request in
                            requestRow(request)
                        }
                    }
                    .listRowBackground(AeroTheme.surface)
                }
                Section {
                    Picker("Mostra", selection: $filter) {
                        ForEach(NearbyFilter.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                }
                Section {
                    if visiblePeople.isEmpty {
                        emptyState
                    } else {
                        ForEach(visiblePeople) { person in
                            personRow(person)
                        }
                    }
                } footer: {
                    if !isVisible {
                        Text("Sei invisibile: vedi gli altri, ma nessuno vede te. Puoi cambiarlo in Impostazioni › Privacy.")
                    } else {
                        Text("Vedi solo chi è a portata di Bluetooth e ha AeroChat aperta. Nessuno diventa tuo contatto finché non accetta.")
                    }
                }
                .listRowBackground(AeroTheme.surface)
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(AeroTheme.background)
            .navigationTitle("Persone vicine")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() }.toolbarLabel() }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Haptics.tap()
                        showPairing = true
                    } label: {
                        Image(systemName: "qrcode")
                    }
                    .accessibilityLabel("Invita con il QR")
                }
            }
            .confirmationDialog("A cosa vuoi giocare?", isPresented: Binding(get: { challengeTarget != nil },
                                                                           set: { if !$0 { challengeTarget = nil } }),
                                titleVisibility: .visible, presenting: challengeTarget) { person in
                ForEach(NearbyProtocol.challengeKinds, id: \.rawValue) { kind in
                    Button(GameService.title(for: kind)) { greet(person, game: kind) }
                }
                Button("Annulla", role: .cancel) {}
            } message: { person in
                Text("\(person.nickname) riceverà la sfida: se accetta, la partita parte subito.")
            }
            .overlay(alignment: .bottom) {
                if let info {
                    Text(info)
                        .font(.footnote)
                        .foregroundStyle(AeroTheme.textPrimary)
                        .padding(12)
                        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .padding()
                        .task(id: info) {
                            try? await Task.sleep(for: .seconds(3))
                            self.info = nil
                        }
                }
            }
            .sheet(isPresented: $showPairing) { QRPairingSheet() }
        }
        .onAppear { filter = initialFilter }
        .task {
            guard let database = environment.database else { return }
            for await list in database.peers.observeAll() {
                peers = Dictionary(list.map { ($0.nodeID, $0) }, uniquingKeysWith: { first, _ in first })
            }
        }
    }

    // MARK: Righe

    private func requestRow(_ request: IncomingContactRequest) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                AvatarView(imageData: nil, colorHex: request.avatarColorHex, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(ContactRequestStore.title(for: request))
                        .font(AeroTheme.title(16, weight: .semibold))
                        .foregroundStyle(AeroTheme.textPrimary)
                    if !request.greeting.isEmpty {
                        Text(request.greeting)
                            .font(.subheadline)
                            .foregroundStyle(AeroTheme.textSecondary)
                    }
                }
            }
            HStack(spacing: 10) {
                Button {
                    Task {
                        // La nuova chat si apre nella lista: il foglio si chiude.
                        if await environment.respond(to: request, accept: true) != nil { dismiss() }
                    }
                } label: {
                    Label(request.isChallenge ? "Accetta e gioca" : "Accetta", systemImage: "checkmark")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AeroTheme.accentEmerald)
                Button(role: .destructive) {
                    Task { await environment.respond(to: request, accept: false) }
                } label: {
                    Text("Rifiuta").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(.vertical, 4)
    }

    private func personRow(_ person: NearbyPerson) -> some View {
        let isContact = environment.isContact(person.nodeID, peers: peers)
        let outgoing = environment.contactRequests.outgoing[person.nodeID]
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                AvatarView(imageData: peers[person.nodeID]?.avatarImage, colorHex: person.avatarColorHex, size: 44)
                VStack(alignment: .leading, spacing: 3) {
                    Text(person.nickname)
                        .font(AeroTheme.title(17, weight: .semibold))
                        .foregroundStyle(AeroTheme.textPrimary)
                    HStack(spacing: 6) {
                        if myFlightCode != nil, person.flightCode == myFlightCode {
                            badge("Sul tuo volo", systemImage: "airplane", color: AeroTheme.accentCyan)
                        }
                        if isContact {
                            badge("Già nei contatti", systemImage: "person.crop.circle.badge.checkmark", color: AeroTheme.accentEmerald)
                        }
                        if person.openToGames {
                            badge("Gioca", systemImage: "suit.club.fill", color: .orange)
                        }
                    }
                }
                Spacer(minLength: 0)
                signal(person.rssi)
            }
            HStack(spacing: 10) {
                Button {
                    greet(person, game: nil)
                } label: {
                    Label(isContact ? "Scrivi" : "Saluta", systemImage: "hand.wave.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AeroTheme.accentCyan)
                .foregroundStyle(.black)
                .disabled(isPending(outgoing))
                if person.openToGames {
                    Button {
                        Haptics.tap()
                        challengeTarget = person
                    } label: {
                        Label("Sfida", systemImage: "suit.spade.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(AeroTheme.accentEmerald)
                    .disabled(isPending(outgoing))
                }
            }
            if let outgoing, !isContact {
                switch outgoing {
                case .pending:
                    Text("Richiesta inviata: aspetta che accetti.")
                        .font(.caption)
                        .foregroundStyle(AeroTheme.textSecondary)
                case .declined:
                    Text("Richiesta rifiutata")
                        .font(.caption)
                        .foregroundStyle(.red)
                case .accepted:
                    EmptyView()
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func isPending(_ state: ContactRequestStore.OutgoingState?) -> Bool {
        if case .pending = state { return true }
        return false
    }

    private func badge(_ title: LocalizedStringKey, systemImage: String, color: Color) -> some View {
        // Icona e testo vicini e su una riga sola: con tre etichette il testo si rimpicciolisce invece
        // di andare a capo (la Label di sistema lasciava molto spazio tra icona e testo).
        HStack(spacing: 3) {
            Image(systemName: systemImage)
            Text(title)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .font(.caption2.weight(.semibold))
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .background(color.opacity(0.15), in: Capsule())
        .foregroundStyle(color)
        .accessibilityElement(children: .combine)
    }

    private func signal(_ rssi: Int?) -> some View {
        let value = rssi ?? -100
        let bars = value >= -60 ? 3 : (value >= -75 ? 2 : 1)
        return Image(systemName: "cellularbars", variableValue: Double(bars) / 3)
            .foregroundStyle(AeroTheme.accentCyan)
            .accessibilityLabel(bars == 3 ? Text("Molto vicino") : bars == 2 ? Text("Vicino") : Text("Un po' più lontano"))
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "person.wave.2")
                .font(.system(size: 36))
                .foregroundStyle(AeroTheme.accentCyan)
            Text(filter == .games ? "Nessuno pronto a giocare, per ora" : "Nessuno nei paraggi, per ora")
                .font(AeroTheme.title(17, weight: .semibold))
                .foregroundStyle(AeroTheme.textPrimary)
            Text("Chi ha AeroChat aperta qui vicino comparirà da solo. Fai scaricare l'app a chi è con te: basta inquadrare il tuo QR.")
                .font(.footnote)
                .foregroundStyle(AeroTheme.textSecondary)
                .multilineTextAlignment(.center)
            Button {
                Haptics.tap()
                showPairing = true
            } label: {
                Label("Invita con il QR", systemImage: "qrcode")
            }
            .buttonStyle(.bordered)
            .tint(AeroTheme.accentCyan)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
    }

    // MARK: Azioni

    private func greet(_ person: NearbyPerson, game: GameKind?) {
        Haptics.tap()
        Task {
            do {
                let result = try await environment.greet(person, game: game)
                if result == .openedChat {
                    dismiss()
                } else {
                    Haptics.success()
                    info = game == nil
                        ? String(localized: "Saluto inviato a \(person.nickname)")
                        : String(localized: "Sfida inviata a \(person.nickname)")
                }
            } catch {
                Haptics.error()
                info = String(localized: "Non raggiungibile in questo momento: riprova tra poco.")
            }
        }
    }
}

/// Banner in cima allo schermo per le richieste ricevute e le risposte alle nostre.
struct ContactRequestBannerView: View {

    let banner: ContactRequestStore.Banner
    let onOpen: () -> Void
    let onDismiss: () -> Void

    private var icon: String {
        switch banner.kind {
        case .incoming(let request): return request.isChallenge ? "suit.spade.fill" : "hand.wave.fill"
        case .accepted: return "checkmark.circle.fill"
        case .declined: return "xmark.circle"
        }
    }

    private var title: String {
        switch banner.kind {
        case .incoming(let request): return ContactRequestStore.title(for: request)
        case .accepted(let nickname, _): return String(localized: "\(nickname) ha accettato!")
        case .declined(let nickname): return String(localized: "\(nickname): richiesta rifiutata")
        }
    }

    private var subtitle: String {
        switch banner.kind {
        case .incoming: return String(localized: "Tocca per rispondere")
        case .accepted: return String(localized: "Tocca per aprire la chat")
        case .declined: return String(localized: "Nessun problema: ci saranno altri compagni di viaggio.")
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(AeroTheme.accentCyan)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(AeroTheme.title(15, weight: .semibold))
                    .foregroundStyle(AeroTheme.textPrimary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AeroTheme.textSecondary)
            }
            Spacer(minLength: 0)
            Button {
                onDismiss()
            } label: {
                Image(systemName: "xmark").font(.caption.weight(.bold)).foregroundStyle(AeroTheme.textSecondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Chiudi")
        }
        .padding(12)
        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(AeroTheme.accentCyan.opacity(0.35)))
        .padding(.horizontal, 16)
        .padding(.top, 4)
        .contentShape(Rectangle())
        .onTapGesture { onOpen() }
        .task(id: banner.id) {
            try? await Task.sleep(for: .seconds(6))
            onDismiss()
        }
    }
}
