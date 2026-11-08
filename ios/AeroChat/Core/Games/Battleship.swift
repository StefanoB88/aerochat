import Foundation

/// Battaglia navale 10x10 con flotta ridotta (4-3-3-2) per partite brevi tra un decollo e un atterraggio.
/// La disposizione delle proprie navi resta locale: in rete viaggiano solo colpi ed esiti.
struct Battleship: Codable, Sendable, Equatable {

    enum Outcome: UInt8, Codable, Sendable {
        case miss = 1
        case hit = 2
        case sunk = 3
    }

    enum Failure: Error, Equatable {
        case outOfBounds
        case alreadyShot
    }

    struct Ship: Codable, Sendable, Equatable {
        var cells: [Int]
        var hits: Set<Int> = []
        var isSunk: Bool { hits.count == cells.count }
    }

    static let size = 10
    static let cellCount = size * size
    static let fleet = [4, 3, 3, 2]

    /// Le nostre navi (segreto locale).
    private(set) var myShips: [Ship]
    /// Colpi ricevuti dall'avversario.
    private(set) var shotsReceived: Set<Int> = []
    /// I nostri colpi con esito.
    private(set) var myShots: [Int: Outcome] = [:]
    /// Navi avversarie affondate (contate dai REPORT `sunk`).
    private(set) var enemyShipsSunk = 0

    init(ships: [Ship]) {
        self.myShips = ships
    }

    /// Flotta casuale senza sovrapposizioni.
    static func randomFleet<G: RandomNumberGenerator>(using generator: inout G) -> [Ship] {
        var occupied: Set<Int> = []
        var ships: [Ship] = []
        for length in fleet {
            var placed = false
            var attempts = 0
            while !placed && attempts < 500 {
                attempts += 1
                let horizontal = Bool.random(using: &generator)
                let row = Int.random(in: 0..<size, using: &generator)
                let column = Int.random(in: 0..<size, using: &generator)
                let cells: [Int] = (0..<length).compactMap { offset in
                    let r = horizontal ? row : row + offset
                    let c = horizontal ? column + offset : column
                    guard r < size, c < size else { return nil }
                    return r * size + c
                }
                guard cells.count == length, occupied.isDisjoint(with: cells) else { continue }
                occupied.formUnion(cells)
                ships.append(Ship(cells: cells))
                placed = true
            }
        }
        return ships
    }

    static func randomFleet() -> [Ship] {
        var generator = SystemRandomNumberGenerator()
        return randomFleet(using: &generator)
    }

    static func isValidFleet(_ ships: [Ship]) -> Bool {
        guard ships.map(\.cells.count).sorted() == fleet.sorted() else { return false }
        let all = ships.flatMap(\.cells)
        return Set(all).count == all.count && all.allSatisfy { (0..<cellCount).contains($0) }
    }

    var allMyShipsSunk: Bool { myShips.allSatisfy(\.isSunk) }
    var enemyFleetDestroyed: Bool { enemyShipsSunk >= Self.fleet.count }
    var myShipsRemaining: Int { myShips.filter { !$0.isSunk }.count }

    /// Colpo ricevuto: aggiorna le navi e restituisce l'esito da riportare.
    mutating func receiveShot(_ cell: Int) throws -> Outcome {
        guard (0..<Self.cellCount).contains(cell) else { throw Failure.outOfBounds }
        guard !shotsReceived.contains(cell) else { throw Failure.alreadyShot }
        shotsReceived.insert(cell)
        for index in myShips.indices where myShips[index].cells.contains(cell) {
            myShips[index].hits.insert(cell)
            return myShips[index].isSunk ? .sunk : .hit
        }
        return .miss
    }

    /// Esito del nostro colpo (dal REPORT avversario).
    mutating func recordMyShot(_ cell: Int, outcome: Outcome) {
        myShots[cell] = outcome
        if outcome == .sunk { enemyShipsSunk += 1 }
    }

    func canShoot(_ cell: Int) -> Bool {
        (0..<Self.cellCount).contains(cell) && myShots[cell] == nil
    }

    /// Mappa pubblica dei nostri colpi (100 byte: 0 sconosciuto, 1 acqua, 2 colpito, 3 affondato).
    var shotsSnapshot: Data {
        var bytes = [UInt8](repeating: 0, count: Self.cellCount)
        for (cell, outcome) in myShots { bytes[cell] = outcome.rawValue }
        return Data(bytes)
    }
}
