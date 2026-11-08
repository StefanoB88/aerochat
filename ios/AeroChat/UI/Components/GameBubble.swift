import SwiftUI

enum GameBubbleAction: Equatable {
    case accept
    case play(cell: Int)
    case resign
}

/// Bolla interattiva di gioco: Tris o Battaglia navale giocabili dentro la chat.
struct GameBubble: View {

    let state: GameSessionState
    let onAction: (GameBubbleAction) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(GameService.title(for: state.gameKind))
                    .font(AeroTheme.title(15, weight: .semibold))
                Spacer()
                if state.status == .active {
                    Button("Abbandona", role: .destructive) { onAction(.resign) }
                        .font(.caption2)
                }
            }
            statusLine
            switch state.gameKind {
            case .ticTacToe:
                ticTacToeBoard
            case .battleship:
                battleshipBoards
            default:
                EmptyView()
            }
            if state.status == .invited && !state.iAmInviter {
                Button {
                    onAction(.accept)
                } label: {
                    Text("Accetta la sfida")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent)
                .tint(AeroTheme.accentEmerald)
            }
        }
        .foregroundStyle(AeroTheme.textPrimary)
    }

    private var statusLine: some View {
        Text(statusText)
            .font(.caption)
            .foregroundStyle(statusColor)
    }

    private var statusText: String {
        switch state.status {
        case .invited:
            return state.iAmInviter ? String(localized: "Invito inviato, in attesa…") : String(localized: "Invito ricevuto")
        case .active:
            if state.gameKind == .battleship, state.pendingShot != nil { return String(localized: "Colpo sparato, attendo l'esito…") }
            return state.myTurn ? String(localized: "Tocca a te") : String(localized: "Turno dell'avversario")
        case .finished:
            switch state.outcome {
            case .iWon: return String(localized: "Hai vinto 🎉")
            case .iLost: return String(localized: "Hai perso")
            case .draw: return String(localized: "Pareggio")
            case .ongoing: return String(localized: "Partita conclusa")
            }
        }
    }

    private var statusColor: Color {
        switch state.outcome {
        case .iWon: return AeroTheme.accentEmerald
        case .iLost: return .red
        default: return state.myTurn ? AeroTheme.accentCyan : AeroTheme.textSecondary
        }
    }

    // MARK: Tris

    private var ticTacToeBoard: some View {
        let board = state.ticTacToe ?? TicTacToe()
        let canPlay = state.status == .active && state.myTurn
        return LazyVGrid(columns: Array(repeating: GridItem(.fixed(52), spacing: 6), count: 3), spacing: 6) {
            ForEach(0..<9, id: \.self) { cell in
                let mark = board.board[cell]
                Button {
                    onAction(.play(cell: cell))
                } label: {
                    Text(mark == .x ? "X" : mark == .o ? "O" : "")
                        .font(AeroTheme.title(24))
                        .foregroundStyle(mark == state.myMark ? AeroTheme.accentCyan : AeroTheme.accentEmerald)
                        .frame(width: 52, height: 52)
                        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(!canPlay || mark != .empty)
                .accessibilityLabel(mark == .empty ? Text("Cella \(cell + 1), vuota") : Text("Cella \(cell + 1), \(mark == .x ? "X" : "O")"))
            }
        }
    }

    // MARK: Battaglia navale

    private var battleshipBoards: some View {
        let board = state.battleship
        let canShoot = state.status == .active && state.myTurn && state.pendingShot == nil
        return VStack(alignment: .leading, spacing: 8) {
            Text("Mare nemico")
                .font(.caption2)
                .foregroundStyle(AeroTheme.textSecondary)
            grid { cell in
                let outcome = board?.myShots[cell]
                Button {
                    onAction(.play(cell: cell))
                } label: {
                    Rectangle()
                        .fill(colorForShot(outcome))
                        .frame(width: 15, height: 15)
                }
                .buttonStyle(.plain)
                .disabled(!canShoot || outcome != nil)
            }
            if let board {
                Text("La tua flotta · \(board.myShipsRemaining) navi a galla")
                    .font(.caption2)
                    .foregroundStyle(AeroTheme.textSecondary)
                let shipCells = Set(board.myShips.flatMap(\.cells))
                let hits = Set(board.myShips.flatMap { Array($0.hits) })
                grid { cell in
                    Rectangle()
                        .fill(hits.contains(cell) ? Color.red : shipCells.contains(cell) ? AeroTheme.accentEmerald.opacity(0.8)
                              : board.shotsReceived.contains(cell) ? Color.white.opacity(0.25) : Color.white.opacity(0.06))
                        .frame(width: 10, height: 10)
                }
            }
        }
    }

    private func grid<Cell: View>(@ViewBuilder cell: @escaping (Int) -> Cell) -> some View {
        VStack(spacing: 2) {
            ForEach(0..<Battleship.size, id: \.self) { row in
                HStack(spacing: 2) {
                    ForEach(0..<Battleship.size, id: \.self) { column in
                        cell(row * Battleship.size + column)
                    }
                }
            }
        }
    }

    private func colorForShot(_ outcome: Battleship.Outcome?) -> Color {
        switch outcome {
        case .none: return Color.white.opacity(0.08)
        case .miss: return Color.white.opacity(0.3)
        case .hit: return .orange
        case .sunk: return .red
        }
    }
}
