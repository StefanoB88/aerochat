import Foundation

/// Tris: tabellone 3x3, X (chi invita) muove per primo.
struct TicTacToe: Codable, Sendable, Equatable {

    enum Mark: UInt8, Codable, Sendable {
        case empty = 0
        case x = 1
        case o = 2

        var opponent: Mark {
            switch self {
            case .x: return .o
            case .o: return .x
            case .empty: return .empty
            }
        }
    }

    enum Failure: Error, Equatable {
        case outOfBounds
        case cellTaken
        case notYourTurn
        case finished
    }

    static let lines: [[Int]] = [
        [0, 1, 2], [3, 4, 5], [6, 7, 8],
        [0, 3, 6], [1, 4, 7], [2, 5, 8],
        [0, 4, 8], [2, 4, 6],
    ]

    private(set) var board: [Mark] = Array(repeating: .empty, count: 9)
    private(set) var next: Mark = .x

    init() {}

    /// Ricostruisce dal `board_snapshot` (9 byte 0/1/2) ricevuto dalla rete.
    init?(snapshot: Data) {
        guard snapshot.count == 9 else { return nil }
        var marks: [Mark] = []
        for byte in snapshot {
            guard let mark = Mark(rawValue: byte) else { return nil }
            marks.append(mark)
        }
        let xs = marks.filter { $0 == .x }.count
        let os = marks.filter { $0 == .o }.count
        guard xs == os || xs == os + 1 else { return nil }
        board = marks
        next = xs == os ? .x : .o
    }

    var snapshot: Data { Data(board.map(\.rawValue)) }

    var winner: Mark? {
        for line in Self.lines {
            let first = board[line[0]]
            if first != .empty, first == board[line[1]], first == board[line[2]] {
                return first
            }
        }
        return nil
    }

    var isDraw: Bool { winner == nil && !board.contains(.empty) }
    var isFinished: Bool { winner != nil || isDraw }

    mutating func play(_ cell: Int, as mark: Mark) throws {
        guard !isFinished else { throw Failure.finished }
        guard (0..<9).contains(cell) else { throw Failure.outOfBounds }
        guard mark == next else { throw Failure.notYourTurn }
        guard board[cell] == .empty else { throw Failure.cellTaken }
        board[cell] = mark
        next = mark.opponent
    }
}
