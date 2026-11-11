import SwiftUI

/// Tavolo a schermo intero delle partite a carte: panno verde, avversari in alto (e ai lati nello Scopone),
/// carte in tavola al centro, la propria mano in basso. Le mosse passano da `onAction` come nella bolla.
struct CardTableView: View {

    let state: GameSessionState
    let onAction: (GameBubbleAction) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selectedCard: Int?
    @State private var selectedOption = 0
    @State private var confirmResign = false
    @State private var scoreSheet: ScoreSheetItem?
    @State private var scopaFlash = false

    private var bottomSeat: Int { state.mySeat ?? 0 }
    private var playerCount: Int { state.playerCount }
    private var canPlay: Bool { state.status == .active && state.myTurn && state.mySeat != nil }

    /// Mani completate (per aprire il riepilogo a fine mano).
    private var completedHands: Int {
        if let scopa { return scopa.handNumber - (scopa.phase == .playing ? 1 : 0) }
        if let briscola = state.briscola { return briscola.isFinished ? 1 : 0 }
        return 0
    }

    private var scopa: ScopaGame? { state.scopa }

    private var captureOptions: [[Int]] {
        guard let selectedCard, let scopa else { return [] }
        return scopa.captureOptions(for: selectedCard)
    }

    private var highlightedTableCards: Set<Int> {
        let options = captureOptions
        guard !options.isEmpty else { return [] }
        return Set(options[min(selectedOption, options.count - 1)])
    }


    // Altezze fisse delle zone del tavolo: nessuna dipende dal turno o dallo stato, cosi' le zone
    // restano ancorate e cambia solo il contenuto (niente "saltelli" a ogni mossa).
    private enum Slot {
        static let header: CGFloat = 44
        static let seatName: CGFloat = 20
        static let seatCards: CGFloat = 52
        static let compactSeatCards: CGFloat = 70
        static let pileLabel: CGFloat = 14
        static let topSeatWidth: CGFloat = 170
        static let compactSeatWidth: CGFloat = 72
        static let infoRow: CGFloat = 18
        static let statusRow: CGFloat = 16
        static let captureChooser: CGFloat = 54
        static let hand: CGFloat = 136
        static let actionRow: CGFloat = 40
    }

    /// Lo spazio per la scelta della presa e' riservato per tutta la partita (Scopa/Scopone giocata da noi).
    private var reservesCaptureChooser: Bool { scopa != nil && state.mySeat != nil }

    var body: some View {
        ZStack {
            RadialGradient(colors: [Color(hex: 0x0B5D3B), Color(hex: 0x03241A)], center: .center, startRadius: 40, endRadius: 520)
                .ignoresSafeArea()
            VStack(spacing: 8) {
                header
                    .frame(height: Slot.header)
                seatBadge(seat: (bottomSeat + (playerCount == 4 ? 2 : 1)) % playerCount)
                HStack(alignment: .center, spacing: 6) {
                    if playerCount == 4 {
                        seatBadge(seat: (bottomSeat + 3) % 4, compact: true)
                    }
                    // Il centro prende lo spazio rimasto: le carte stanno dentro senza cambiarne la misura.
                    Color.clear
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .overlay { center }
                        .clipped()
                    if playerCount == 4 {
                        seatBadge(seat: (bottomSeat + 1) % 4, compact: true)
                    }
                }
                .frame(maxHeight: .infinity)
                infoLine
                if reservesCaptureChooser {
                    captureChooser
                        .frame(height: Slot.captureChooser)
                }
                bottomArea
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
        .overlay {
            // Annuncio "Scopa!" in sovrapposizione: non sposta nessuna zona.
            ZStack {
                if scopaFlash {
                    Text("Scopa!")
                        .font(AeroTheme.title(56, weight: .heavy))
                        .foregroundStyle(Color(hex: 0xFFD54F))
                        .shadow(color: .black.opacity(0.7), radius: 8)
                        .transition(.scale(scale: 0.4).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.35, dampingFraction: 0.6), value: scopaFlash)
            .allowsHitTesting(false)
        }
        .foregroundStyle(AeroTheme.textPrimary)
        .preferredColorScheme(.dark)
        .confirmationDialog("Abbandonare la partita?", isPresented: $confirmResign, titleVisibility: .visible) {
            Button("Abbandona", role: .destructive) { onAction(.resign) }
            Button("Annulla", role: .cancel) {}
        } message: {
            Text(playerCount == 4 ? LocalizedStringKey("La tua squadra perderà la partita.") : LocalizedStringKey("Perderai la partita."))
        }
        .sheet(item: $scoreSheet) { _ in
            CardScoreSheet(state: state)
                .presentationDetents([.medium, .large])
        }
        .onChange(of: completedHands) { old, new in
            if new > old { scoreSheet = ScoreSheetItem(id: new) }
        }
        .onChange(of: state.scopa?.lastPlay) { _, play in
            guard let play else { return }
            if play.scopa {
                Haptics.success()
                scopaFlash = true
            } else {
                Haptics.impact(play.captured.isEmpty ? .light : .medium)
            }
        }
        .onChange(of: state.briscola?.lastTrick) { _, trick in
            if trick != nil { Haptics.impact(.light) }
        }
        .onChange(of: state.myTurn) { _, myTurn in
            if !myTurn { selectedCard = nil }
        }
        .task(id: scopaFlash) {
            guard scopaFlash else { return }
            try? await Task.sleep(for: .seconds(1.4))
            scopaFlash = false
        }
    }

    // MARK: Intestazione

    private var header: some View {
        // I pulsanti occupano sempre il loro posto (nascosti quando non servono): il titolo resta centrato.
        let hasHandScore = state.scopa?.lastHandScore != nil
        let canResign = state.mySeat != nil && state.status != .finished
        return HStack(spacing: 8) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.headline)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.haptic)
            .tint(AeroTheme.textPrimary)
            .accessibilityLabel("Chiudi tavolo")
            // Bilancia la larghezza dei due pulsanti a destra.
            Color.clear.frame(width: 28, height: 28)
            VStack(spacing: 2) {
                Text(GameService.title(for: state.gameKind))
                    .font(AeroTheme.title(17, weight: .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Group {
                    if let scores = state.cardScores, scores.count == 2 {
                        Text("\(state.sideName(0)) \(scores[0]) – \(scores[1]) \(state.sideName(1))")
                    } else {
                        Text(verbatim: " ")
                    }
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(AeroTheme.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity)
            Button {
                scoreSheet = ScoreSheetItem(id: completedHands)
            } label: {
                Image(systemName: "list.number")
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.haptic)
            .tint(AeroTheme.textPrimary)
            .accessibilityLabel("Punteggio ultima mano")
            .opacity(hasHandScore ? 1 : 0)
            .disabled(!hasHandScore)
            .accessibilityHidden(!hasHandScore)
            Button {
                confirmResign = true
            } label: {
                Image(systemName: "flag.fill")
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.haptic)
            .tint(.red)
            .accessibilityLabel("Abbandona")
            .opacity(canResign ? 1 : 0)
            .disabled(!canResign)
            .accessibilityHidden(!canResign)
        }
    }

    // MARK: Posti

    private func handCount(_ seat: Int) -> Int {
        if let scopa, scopa.hands.indices.contains(seat) { return scopa.hands[seat].count }
        if let briscola = state.briscola, briscola.hands.indices.contains(seat) { return briscola.hands[seat].count }
        return 0
    }

    private func pileCount(_ seat: Int) -> Int {
        if let scopa { return scopa.captures[ScopaGame.side(of: seat)].count }
        if let briscola = state.briscola { return briscola.won[seat].count }
        return 0
    }

    private func isDealer(_ seat: Int) -> Bool {
        (scopa?.dealer ?? state.briscola?.dealer) == seat
    }

    /// Posto di un giocatore: misura fissa (il turno cambia solo colori e bordo, mai la geometria).
    private func seatBadge(seat: Int, compact: Bool = false) -> some View {
        let isTurn = state.turnSeat == seat
        let count = handCount(seat)
        return VStack(spacing: 4) {
            HStack(spacing: 4) {
                if isDealer(seat) {
                    Text("M")
                        .font(.caption2.weight(.heavy))
                        .padding(3)
                        .background(Color.white.opacity(0.18), in: Circle())
                        .accessibilityLabel("Mazziere")
                }
                Text(state.name(ofSeat: seat))
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .frame(height: Slot.seatName)
            .foregroundStyle(isTurn ? AeroTheme.accentCyan : AeroTheme.textPrimary)
            if compact {
                VStack(spacing: -34) {
                    ForEach(0..<min(count, 5), id: \.self) { _ in
                        NeapolitanCardView(card: nil, width: 28)
                    }
                }
                .rotationEffect(.degrees(90))
                .frame(width: 50, height: Slot.compactSeatCards)
            } else {
                HStack(spacing: -22) {
                    ForEach(0..<count, id: \.self) { _ in
                        NeapolitanCardView(card: nil, width: 32)
                    }
                }
                .frame(height: Slot.seatCards)
            }
            pileLabel(seat: seat)
                .frame(height: Slot.pileLabel)
        }
        .padding(6)
        .frame(width: compact ? Slot.compactSeatWidth : Slot.topSeatWidth)
        .background(Color.black.opacity(isTurn ? 0.35 : 0.18), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
            .strokeBorder(isTurn ? AeroTheme.accentCyan.opacity(0.8) : .clear, lineWidth: 1.5))
        .accessibilityElement(children: .combine)
        .accessibilityValue(Text("\(count) carte in mano"))
    }

    private func pileLabel(seat: Int) -> some View {
        let scope = scopa?.scope[ScopaGame.side(of: seat)] ?? 0
        return HStack(spacing: 4) {
            Image(systemName: "square.stack.fill")
            Text("\(pileCount(seat))")
                .monospacedDigit()
            if scope > 0 {
                Text(String(repeating: "★", count: min(scope, 6)))
                    .foregroundStyle(Color(hex: 0xFFD54F))
                    .accessibilityLabel(Text("\(scope) scope"))
            }
        }
        .font(.caption2)
        .foregroundStyle(AeroTheme.textSecondary)
        .lineLimit(1)
        .minimumScaleFactor(0.7)
    }

    // MARK: Centro

    @ViewBuilder
    private var center: some View {
        if let scopa {
            VStack(spacing: 6) {
                if scopa.table.isEmpty {
                    if scopa.phase == .playing {
                        Text("Tavolo vuoto")
                            .font(.footnote)
                            .foregroundStyle(AeroTheme.textSecondary)
                    }
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 50, maximum: 60), spacing: 6)], spacing: 6) {
                        ForEach(scopa.table, id: \.self) { card in
                            NeapolitanCardView(card: card, width: 48, highlighted: highlightedTableCards.contains(card),
                                               dimmed: selectedCard != nil && !highlightedTableCards.isEmpty
                                                   && !highlightedTableCards.contains(card))
                                .onTapGesture { selectOption(containing: card) }
                                .transition(.scale(scale: 0.6).combined(with: .opacity))
                        }
                    }
                    // Animazioni locali alle carte in tavola (comparsa, evidenziazione della presa).
                    .animation(.easeOut(duration: 0.2), value: scopa.table)
                    .animation(.easeOut(duration: 0.2), value: highlightedTableCards)
                }
                if !scopa.isScopone, scopa.phase == .playing {
                    Text("Mazzo: \(scopa.remainingInDeck)")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(AeroTheme.textSecondary)
                }
            }
        } else if let briscola = state.briscola {
            HStack(spacing: 18) {
                VStack(spacing: 4) {
                    ZStack(alignment: .leading) {
                        if !briscola.stock.isEmpty {
                            NeapolitanCardView(card: briscola.briscolaCard, width: 48)
                                .rotationEffect(.degrees(90))
                                .offset(x: 22)
                        }
                        if briscola.stock.count > 1 {
                            NeapolitanCardView(card: nil, width: 48)
                        }
                    }
                    .frame(width: 100, height: 80)
                    Text("Tallone: \(briscola.stock.count)")
                        .font(.caption2.monospacedDigit())
                    Text("Briscola: \(NeapolitanCards.suit(briscola.briscolaCard).name)")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Color(hex: 0xFFD54F))
                }
                HStack(spacing: 8) {
                    ForEach(briscola.trick, id: \.self) { card in
                        NeapolitanCardView(card: card, width: 56)
                    }
                    if briscola.trick.isEmpty {
                        Text("Nessuna carta in gioco")
                            .font(.footnote)
                            .foregroundStyle(AeroTheme.textSecondary)
                    }
                }
            }
        } else {
            Text(state.cardStatusText)
                .font(.footnote)
                .foregroundStyle(AeroTheme.textSecondary)
        }
    }

    /// Due righe ad altezza fissa (ultima giocata, stato del turno): il testo si rimpicciolisce, non va a capo.
    private var infoLine: some View {
        VStack(spacing: 4) {
            Text(state.lastMoveText ?? " ")
                .font(.footnote.weight(.medium))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .frame(height: Slot.infoRow)
            Group {
                if let resign = state.resignText {
                    Text(verbatim: "\(state.cardStatusText) · \(resign)")
                } else {
                    Text(state.cardStatusText)
                }
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(state.myTurn ? AeroTheme.accentCyan : AeroTheme.textSecondary)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .frame(height: Slot.statusRow)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: Scelta della presa

    /// Spazio sempre riservato: il contenuto compare solo quando la carta scelta ha piu' prese possibili.
    private var captureChooser: some View {
        let visible = captureOptions.count > 1
        return VStack(spacing: 4) {
            Text("Scegli la presa")
                .font(.caption2)
                .foregroundStyle(AeroTheme.textSecondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(captureOptions.enumerated()), id: \.offset) { index, option in
                        Button {
                            selectedOption = index
                        } label: {
                            Text(option.map(NeapolitanCards.shortName).joined(separator: " + "))
                                .font(.callout.weight(.semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(index == selectedOption ? AeroTheme.accentCyan.opacity(0.3) : Color.black.opacity(0.3),
                                            in: Capsule())
                                .overlay(Capsule().strokeBorder(index == selectedOption ? AeroTheme.accentCyan : .clear))
                        }
                        .buttonStyle(.haptic)
                        .tint(AeroTheme.textPrimary)
                        .accessibilityLabel(option.map(NeapolitanCards.fullName).joined(separator: ", "))
                        .accessibilityAddTraits(index == selectedOption ? .isSelected : [])
                    }
                }
            }
        }
        .opacity(visible ? 1 : 0)
        .allowsHitTesting(visible)
        .accessibilityHidden(!visible)
        .animation(.easeOut(duration: 0.2), value: visible)
    }

    private func selectOption(containing card: Int) {
        guard let index = captureOptions.firstIndex(where: { $0.contains(card) }) else { return }
        Haptics.tap()
        selectedOption = index
    }

    // MARK: Mano

    @ViewBuilder
    private var bottomArea: some View {
        if let mySeat = state.mySeat {
            let hand = myHand(mySeat)
            GeometryReader { proxy in
                let count = max(hand.count, 1)
                let visible: CGFloat = count > 5 ? 0.62 : 1.08
                let width = min(72, max(36, proxy.size.width / (1 + CGFloat(count - 1) * visible)))
                HStack(spacing: width * (visible - 1)) {
                    ForEach(hand, id: \.self) { card in
                        // Il sollevamento della carta scelta e' solo grafico (offset): non tocca il layout.
                        NeapolitanCardView(card: card, width: width, highlighted: card == selectedCard)
                            .offset(y: card == selectedCard ? -16 : 0)
                            .onTapGesture { tap(card) }
                            .accessibilityAddTraits(.isButton)
                            .accessibilityAddTraits(card == selectedCard ? .isSelected : [])
                    }
                }
                .animation(.easeOut(duration: 0.2), value: selectedCard)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            }
            .frame(height: Slot.hand)
            HStack {
                seatSummary(mySeat)
                Spacer(minLength: 8)
                // Sempre presente nello stesso punto: attenuato e disattivo quando non si puo' giocare.
                Button {
                    playSelected()
                } label: {
                    Text("Gioca")
                        .font(.body.weight(.semibold))
                        .frame(minWidth: 96, minHeight: 28)
                }
                .buttonStyle(.borderedProminent)
                .tint(AeroTheme.accentEmerald)
                .disabled(!canPlay || selectedCard == nil)
                .opacity(canPlay ? 1 : 0.45)
                .hapticOnTap()
            }
            .frame(height: Slot.actionRow)
        } else {
            VStack(spacing: 4) {
                Label("Stai guardando la partita", systemImage: "eye")
                    .font(.caption)
                    .foregroundStyle(AeroTheme.textSecondary)
                seatBadge(seat: 0)
            }
        }
    }

    private func seatSummary(_ seat: Int) -> some View {
        HStack(spacing: 6) {
            Text(state.name(ofSeat: seat))
                .font(.caption.weight(.semibold))
                .foregroundStyle(state.turnSeat == seat ? AeroTheme.accentCyan : AeroTheme.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            pileLabel(seat: seat)
            if isDealer(seat) {
                Text("Mazziere")
                    .font(.caption2)
                    .foregroundStyle(AeroTheme.textSecondary)
                    .lineLimit(1)
            }
        }
    }

    /// Mano ordinata per seme e valore (l'ordine interno resta quello di distribuzione).
    private func myHand(_ seat: Int) -> [Int] {
        let hand: [Int]
        if let scopa, scopa.hands.indices.contains(seat) {
            hand = scopa.hands[seat]
        } else if let briscola = state.briscola, briscola.hands.indices.contains(seat) {
            hand = briscola.hands[seat]
        } else {
            hand = []
        }
        return hand.sorted()
    }

    private func tap(_ card: Int) {
        guard canPlay else {
            Haptics.warning()
            return
        }
        if selectedCard == card {
            playSelected()
        } else {
            Haptics.tap()
            selectedCard = card
            selectedOption = 0
        }
    }

    private func playSelected() {
        guard canPlay, let card = selectedCard else { return }
        let options = captureOptions
        let captured = options.isEmpty ? [] : options[min(selectedOption, options.count - 1)]
        selectedCard = nil
        selectedOption = 0
        onAction(.playCard(card: card, captured: captured))
    }
}

private struct ScoreSheetItem: Identifiable {
    let id: Int
}

/// Riepilogo di fine mano: carte, denari, settebello, primiera, scope e totali (Briscola: punti carta).
struct CardScoreSheet: View {

    let state: GameSessionState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let scopa = state.scopa, let score = scopa.lastHandScore {
                        scopaGrid(score: score, totals: scopa.scores)
                    } else if let briscola = state.briscola {
                        briscolaGrid(briscola)
                    }
                    Text(state.cardStatusText)
                        .font(.headline)
                        .foregroundStyle(state.outcome == .iWon ? AeroTheme.accentEmerald
                                         : state.outcome == .iLost ? .red : AeroTheme.textPrimary)
                }
                .padding()
            }
            .background(AeroTheme.background)
            .navigationTitle(state.gameKind == .briscola ? LocalizedStringKey("Punteggio") : LocalizedStringKey("Punteggio della mano"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Chiudi") { dismiss() }.toolbarLabel() }
            }
        }
    }

    private func scopaGrid(score: ScopaGame.HandScore, totals: [Int]) -> some View {
        Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 10) {
            GridRow {
                Text("")
                Text(state.sideName(0)).font(.caption.weight(.semibold)).lineLimit(2)
                Text(state.sideName(1)).font(.caption.weight(.semibold)).lineLimit(2)
            }
            Divider()
            row("Carte", values: score.cards.map(String.init), points: score.cardsPoint)
            row("Denari", values: score.denari.map(String.init), points: score.denariPoint)
            row("Settebello", values: score.settebello.map { $0 > 0 ? "7♦" : "–" }, points: score.settebello)
            row("Primiera", values: score.primieraValue.map { $0 > 0 ? String($0) : "–" }, points: score.primiera)
            row("Scope", values: score.scope.map(String.init), points: score.scope)
            Divider()
            GridRow {
                Text("Totale mano").font(.body.weight(.semibold))
                Text("\(score.total[0])").monospacedDigit()
                Text("\(score.total[1])").monospacedDigit()
            }
            GridRow {
                Text("Partita").font(.body.weight(.semibold))
                Text("\(totals[0])").monospacedDigit().foregroundStyle(AeroTheme.accentCyan)
                Text("\(totals[1])").monospacedDigit().foregroundStyle(AeroTheme.accentCyan)
            }
        }
    }

    private func row(_ title: LocalizedStringKey, values: [String], points: [Int]) -> some View {
        GridRow {
            Text(title)
            ForEach(0..<2, id: \.self) { side in
                HStack(spacing: 4) {
                    Text(values[side]).monospacedDigit()
                    if points[side] > 0 {
                        Text("+\(points[side])")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(AeroTheme.accentEmerald)
                    }
                }
            }
        }
    }

    private func briscolaGrid(_ briscola: BriscolaGame) -> some View {
        Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 10) {
            GridRow {
                Text("")
                Text(state.sideName(0)).font(.caption.weight(.semibold))
                Text(state.sideName(1)).font(.caption.weight(.semibold))
            }
            Divider()
            GridRow {
                Text("Punti")
                Text("\(briscola.points[0])").monospacedDigit()
                Text("\(briscola.points[1])").monospacedDigit()
            }
            GridRow {
                Text("Carte prese")
                Text("\(briscola.won[0].count)").monospacedDigit()
                Text("\(briscola.won[1].count)").monospacedDigit()
            }
        }
    }
}

#if DEBUG
/// Tavolo di Scopa d'esempio: `myTurn` sceglie se tocca a noi (dopo una giocata dell'avversario) o all'avversario.
private func previewScopaState(myTurn: Bool) -> GameSessionState {
    var state = GameSessionState(gameID: "00", kind: GameKind.scopa.rawValue, conversationID: "c",
                                 opponentNodeID: Data(repeating: 1, count: 8), iAmInviter: true)
    state.status = .active
    state.seats = [Data(repeating: 0, count: 8), Data(repeating: 1, count: 8)]
    state.seatNames = ["Anna", "Bruno"]
    // Mazziere 0: apre il posto 1.
    var game = try! ScopaGame(playerCount: 2, dealer: 0, deck: Array(0..<40))
    state.mySeat = 0
    if myTurn, let card = game.hands[1].first {
        // Bruno cala una carta: tocca ad Anna, con l'ultima giocata in vista.
        _ = try? game.play(seat: 1, card: card, captured: game.captureOptions(for: card).first ?? [])
    }
    state.scopa = game
    state.myTurn = game.turn == state.mySeat
    return state
}

#Preview("Scopa · tocca a te") {
    CardTableView(state: previewScopaState(myTurn: true)) { _ in }
}

#Preview("Scopa · tocca all'avversario") {
    CardTableView(state: previewScopaState(myTurn: false)) { _ in }
}
#endif
