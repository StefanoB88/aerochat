import SwiftUI

/// Segmenti di "Persone vicine": lista, mappa dei posti, bacheca della cabina.
enum NearbySegment: String, CaseIterable, Identifiable {
    case people
    case map
    case board

    var id: String { rawValue }

    var title: String {
        switch self {
        case .people: return String(localized: "Persone")
        case .map: return String(localized: "Mappa")
        case .board: return String(localized: "Bacheca")
        }
    }
}

extension BoardKind {
    /// Tipi che si possono pubblicare, nell'ordine del selettore.
    static let publishable: [BoardKind] = [.lookingFor, .offering, .found, .seatSwap]

    var title: String {
        switch self {
        case .lookingFor: return String(localized: "Cerco")
        case .offering: return String(localized: "Offro")
        case .found: return String(localized: "Trovato")
        case .seatSwap: return String(localized: "Scambio posto")
        case .unspecified, .UNRECOGNIZED: return ""
        }
    }

    var systemImage: String {
        switch self {
        case .lookingFor: return "magnifyingglass"
        case .offering: return "gift.fill"
        case .found: return "tag.fill"
        case .seatSwap: return "arrow.left.arrow.right"
        case .unspecified, .UNRECOGNIZED: return "pin"
        }
    }

    var tint: Color {
        switch self {
        case .lookingFor: return AeroTheme.accentCyan
        case .offering: return AeroTheme.accentEmerald
        case .found: return .orange
        case .seatSwap: return .purple
        case .unspecified, .UNRECOGNIZED: return AeroTheme.textSecondary
        }
    }
}

// MARK: - Bacheca

/// Righe della bacheca dentro la lista di "Persone vicine": nuovo annuncio, scambio posto rapido, annunci.
struct CabinBoardSection: View {

    let onInfo: (String) -> Void
    /// "Saluta" verso un contatto gia' noto ha aperto la chat: il foglio si chiude.
    let onOpenedChat: () -> Void

    @Environment(AppEnvironment.self) private var environment
    @State private var showComposer = false

    private var mineCount: Int { environment.boardPosts.filter(\.isMine).count }

    var body: some View {
        Section {
            Button {
                Haptics.tap()
                if mineCount >= NearbyProtocol.maxOwnBoardPosts {
                    onInfo(String(localized: "Hai già 3 annunci aperti: chiudine uno per pubblicarne un altro."))
                } else {
                    showComposer = true
                }
            } label: {
                Label("Nuovo annuncio", systemImage: "square.and.pencil")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AeroTheme.accentCyan)
            }
            if let seat = environment.mySeat {
                Button {
                    Haptics.tap()
                    publish(kind: .seatSwap, text: String(localized: "Cerco uno scambio di posto: sono al \(seat)."))
                } label: {
                    Label("Scambio posto", systemImage: BoardKind.seatSwap.systemImage)
                        .foregroundStyle(AeroTheme.textPrimary)
                }
            }
        } footer: {
            Text("La bacheca della cabina: annunci brevi visibili a chi è vicino, per 6 ore al massimo.")
        }
        .listRowBackground(AeroTheme.surface)
        .sheet(isPresented: $showComposer) {
            BoardComposerSheet { kind, text in publish(kind: kind, text: text) }
                .presentationDetents([.medium])
        }

        Section {
            if environment.boardPosts.isEmpty {
                Text("Nessun annuncio per ora. Cerchi un caricatore o vuoi offrire uno snack? Scrivilo qui: lo vede chi è vicino.")
                    .font(.footnote)
                    .foregroundStyle(AeroTheme.textSecondary)
                    .padding(.vertical, 8)
            } else {
                ForEach(environment.boardPosts) { entry in
                    postRow(entry)
                }
            }
        }
        .listRowBackground(AeroTheme.surface)
    }

    private func postRow(_ entry: BoardPostEntry) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Label(entry.kind.title, systemImage: entry.kind.systemImage)
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(entry.kind.tint.opacity(0.15), in: Capsule())
                    .foregroundStyle(entry.kind.tint)
                if let seat = entry.seat {
                    Text("posto \(seat)")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AeroTheme.textSecondary)
                }
                Spacer()
                Text(entry.createdAt, style: .time)
                    .font(.caption)
                    .foregroundStyle(AeroTheme.textSecondary)
            }
            Text(entry.text)
                .font(.body)
                .foregroundStyle(AeroTheme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                if entry.isMine {
                    Text("Il tuo annuncio")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AeroTheme.accentEmerald)
                    Spacer()
                    Button(role: .destructive) {
                        Haptics.tap()
                        Task { await environment.closeBoardPost(entry) }
                    } label: {
                        Text("Chiudi annuncio").font(.footnote.weight(.semibold))
                    }
                    .buttonStyle(.bordered)
                } else {
                    AvatarView(imageData: nil, colorHex: entry.avatarColorHex, size: 22)
                    Text(entry.nickname)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AeroTheme.textSecondary)
                    Spacer()
                    Button {
                        reply(to: entry)
                    } label: {
                        Label("Saluta", systemImage: "hand.wave.fill").font(.footnote.weight(.semibold))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AeroTheme.accentCyan)
                    .foregroundStyle(.black)
                    .disabled(isPending(entry.authorNodeID))
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func isPending(_ nodeID: Data) -> Bool {
        if case .pending = environment.contactRequests.outgoing[nodeID] { return true }
        return false
    }

    private func publish(kind: BoardKind, text: String) {
        Task {
            do {
                try await environment.postToBoard(kind: kind, text: text)
                Haptics.success()
                onInfo(String(localized: "Annuncio pubblicato"))
            } catch RouterError.boardLimitReached {
                Haptics.error()
                onInfo(String(localized: "Hai già 3 annunci aperti: chiudine uno per pubblicarne un altro."))
            } catch {
                Haptics.error()
                onInfo(String(localized: "Annuncio non valido: da 1 a 140 caratteri, senza link."))
            }
        }
    }

    private func reply(to entry: BoardPostEntry) {
        Haptics.tap()
        Task {
            do {
                if try await environment.reply(to: entry) == .openedChat {
                    onOpenedChat()
                } else {
                    Haptics.success()
                    onInfo(String(localized: "Saluto inviato a \(entry.nickname)"))
                }
            } catch {
                Haptics.error()
                onInfo(String(localized: "Non raggiungibile in questo momento: riprova tra poco."))
            }
        }
    }
}

/// Nuovo annuncio: tipo, testo (1...140 caratteri, niente link), pubblica.
struct BoardComposerSheet: View {

    let onPublish: (BoardKind, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var kind: BoardKind = .lookingFor
    @State private var text = ""
    @FocusState private var focused: Bool

    private var isValid: Bool { BoardPostEntry.validText(text) != nil }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Tipo", selection: $kind) {
                        ForEach(BoardKind.publishable, id: \.rawValue) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }
                Section {
                    TextField("Es. un caricatore USB-C, snack da condividere…", text: $text, axis: .vertical)
                        .lineLimit(2...5)
                        .focused($focused)
                        .onChange(of: text) { _, value in
                            if value.count > NearbyProtocol.maxBoardTextLength { text = String(value.prefix(NearbyProtocol.maxBoardTextLength)) }
                        }
                } footer: {
                    Text("\(text.trimmingCharacters(in: .whitespacesAndNewlines).count)/\(NearbyProtocol.maxBoardTextLength) · niente link, scade dopo 6 ore")
                }
                .listRowBackground(AeroTheme.surface)
            }
            .scrollContentBackground(.hidden)
            .background(AeroTheme.background)
            .navigationTitle("Nuovo annuncio")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annulla") { dismiss() }.toolbarLabel() }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Pubblica") {
                        onPublish(kind, text)
                        dismiss()
                    }
                    .toolbarLabel()
                    .disabled(!isValid)
                }
            }
            .onAppear { focused = true }
        }
    }
}

// MARK: - Mappa dei posti

/// Mappa dei posti (segmento "Mappa"): righe per fila crescente, colonne per lettera; i vicini del nostro volo
/// con il posto condiviso e noi evidenziati. Tocco su un passeggero: "Saluta" o "Proponi scambio posto".
struct SeatMapSection: View {

    let onInfo: (String) -> Void
    let onOpenedChat: () -> Void

    @Environment(AppEnvironment.self) private var environment
    @AppStorage(AppSettings.Key.shareSeat) private var shareSeat = false
    @State private var selected: NearbyPerson?
    @State private var showSeatEditor = false

    private var flightCode: String? { environment.activeFlightLounge?.flightCode }

    /// Posto -> passeggero (vicini del nostro volo con il posto noto).
    private var seated: [String: NearbyPerson] {
        var map: [String: NearbyPerson] = [:]
        for person in environment.presence.people {
            if let seat = person.seatOnFlight(flightCode), map[seat] == nil { map[seat] = person }
        }
        return map
    }

    var body: some View {
        Section {
            Button {
                Haptics.tap()
                if flightCode == nil {
                    onInfo(String(localized: "Il posto vale nella Cabina del volo: aggiungi la carta d'imbarco per usarlo."))
                } else {
                    showSeatEditor = true
                }
            } label: {
                HStack {
                    Label("Il mio posto", systemImage: "carseat.right.fill")
                        .foregroundStyle(AeroTheme.textPrimary)
                    Spacer()
                    Text(flightCode == nil ? String(localized: "Nessun volo") : (environment.mySeat ?? String(localized: "Non impostato")))
                        .foregroundStyle(AeroTheme.textSecondary)
                    Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(AeroTheme.textSecondary)
                }
            }
            Toggle(isOn: $shareSeat) {
                Label("Mostra il mio posto ai vicini", systemImage: "eye")
            }
            .tint(AeroTheme.accentCyan)
            .onChange(of: shareSeat) { _, _ in environment.nearbySettingsChanged() }
        } footer: {
            Text("Solo i passeggeri del tuo stesso volo vedono il posto. Spento di default.")
        }
        .foregroundStyle(AeroTheme.textPrimary)
        .listRowBackground(AeroTheme.surface)
        .sheet(isPresented: $showSeatEditor) {
            SeatEditorSheet(current: environment.mySeat) { environment.setMySeat($0) }
                .presentationDetents([.medium])
        }

        Section {
            if flightCode == nil {
                Text("Entra nella Cabina del volo con la carta d'imbarco per vedere la mappa dei posti.")
                    .font(.footnote)
                    .foregroundStyle(AeroTheme.textSecondary)
                    .padding(.vertical, 8)
            } else if seated.isEmpty {
                if let mySeat = environment.mySeat { grid(seated: [:], mySeat: mySeat) }
                Text("Nessun vicino del tuo volo ha condiviso il posto, per ora. Condividi il tuo per comparire sulla mappa.")
                    .font(.footnote)
                    .foregroundStyle(AeroTheme.textSecondary)
                    .padding(.vertical, 8)
            } else {
                grid(seated: seated, mySeat: environment.mySeat)
            }
        }
        .listRowBackground(AeroTheme.surface)
        .confirmationDialog(selected?.nickname ?? "", isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } }),
                            titleVisibility: .visible, presenting: selected) { person in
            Button("Saluta") { act(person, swap: false) }
            Button("Proponi scambio posto") { act(person, swap: true) }
            Button("Annulla", role: .cancel) {}
        }
    }

    private func grid(seated: [String: NearbyPerson], mySeat: String?) -> some View {
        let seats = Array(seated.keys) + (mySeat.map { [$0] } ?? [])
        let rows = Array(Set(seats.compactMap(SeatCode.row))).sorted()
        let letters = Array(Set(seats.compactMap(SeatCode.letter))).sorted()
        return ScrollView(.horizontal, showsIndicators: false) {
            Grid(horizontalSpacing: 6, verticalSpacing: 6) {
                GridRow {
                    Text(verbatim: "").frame(width: 32)
                    ForEach(letters, id: \.self) { letter in
                        Text(String(letter))
                            .font(.caption.weight(.bold))
                            .foregroundStyle(AeroTheme.textSecondary)
                            .frame(width: 52)
                    }
                }
                ForEach(rows, id: \.self) { row in
                    GridRow {
                        Text("\(row)")
                            .font(.caption.monospacedDigit().weight(.semibold))
                            .foregroundStyle(AeroTheme.textSecondary)
                            .frame(width: 32)
                        ForEach(letters, id: \.self) { letter in
                            seatCell("\(row)\(letter)", person: seated["\(row)\(letter)"], isMine: mySeat == "\(row)\(letter)")
                        }
                    }
                }
            }
            .padding(.vertical, 8)
        }
    }

    @ViewBuilder
    private func seatCell(_ seat: String, person: NearbyPerson?, isMine: Bool) -> some View {
        if isMine {
            VStack(spacing: 2) {
                Image(systemName: "person.fill")
                Text("Tu").font(.caption2.weight(.bold)).lineLimit(1)
            }
            .frame(width: 52, height: 48)
            .background(AeroTheme.accentCyan, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .foregroundStyle(.black)
            .accessibilityLabel(Text("Tu, posto \(seat)"))
        } else if let person {
            Button {
                Haptics.tap()
                selected = person
            } label: {
                VStack(spacing: 2) {
                    AvatarView(imageData: nil, colorHex: person.avatarColorHex, size: 22)
                    Text(person.nickname).font(.caption2.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
                }
                .frame(width: 52, height: 48)
                .background(AeroTheme.accentEmerald.opacity(0.18), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .foregroundStyle(AeroTheme.textPrimary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("\(person.nickname), posto \(seat)"))
        } else {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(AeroTheme.textSecondary.opacity(0.25))
                .frame(width: 52, height: 48)
                .accessibilityHidden(true)
        }
    }

    private func act(_ person: NearbyPerson, swap: Bool) {
        Task {
            do {
                let result = swap ? try await environment.proposeSeatSwap(to: person) : try await environment.greet(person)
                if result == .openedChat {
                    onOpenedChat()
                } else {
                    Haptics.success()
                    onInfo(String(localized: "Saluto inviato a \(person.nickname)"))
                }
            } catch {
                Haptics.error()
                onInfo(String(localized: "Non raggiungibile in questo momento: riprova tra poco."))
            }
        }
    }
}

/// "Il mio posto": fila e lettera come sulla carta d'imbarco; vuoto lo cancella.
struct SeatEditorSheet: View {

    let current: String?
    let onSave: (String?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var isInvalid = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("es. 14C", text: $text)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .font(AeroTheme.title(22, weight: .semibold))
                } footer: {
                    if isInvalid {
                        Text("Posto non valido: numero di fila (1–999) e una lettera da A a K, es. 14C.").foregroundStyle(.red)
                    } else {
                        Text("Fila e lettera, come sulla carta d'imbarco (es. 14C). Lascia vuoto per cancellarlo.")
                    }
                }
                .listRowBackground(AeroTheme.surface)
            }
            .scrollContentBackground(.hidden)
            .background(AeroTheme.background)
            .navigationTitle("Modifica il posto")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annulla") { dismiss() }.toolbarLabel() }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Salva") { save() }.toolbarLabel()
                }
            }
            .onAppear { text = current ?? "" }
        }
    }

    private func save() {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            onSave(nil)
            dismiss()
            return
        }
        guard let seat = SeatCode.normalized(trimmed) else {
            isInvalid = true
            Haptics.error()
            return
        }
        Haptics.success()
        onSave(seat)
        dismiss()
    }
}
