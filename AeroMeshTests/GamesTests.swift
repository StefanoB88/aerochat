import Testing
import Foundation
@testable import MyApp

@Suite("Mini-giochi – Tris, Battaglia navale, GameService sulla mesh")
struct GamesTests {

    @Test("Tris: turni, celle occupate, vittoria, pareggio e snapshot")
    func ticTacToeEngine() throws {
        var game = TicTacToe()
        #expect(throws: TicTacToe.Failure.notYourTurn) { try game.play(0, as: .o) }
        try game.play(0, as: .x)
        #expect(throws: TicTacToe.Failure.cellTaken) { try game.play(0, as: .o) }
        #expect(throws: TicTacToe.Failure.outOfBounds) { try game.play(9, as: .o) }
        try game.play(3, as: .o)
        try game.play(1, as: .x)
        try game.play(4, as: .o)
        try game.play(2, as: .x)
        #expect(game.winner == .x)
        #expect(game.isFinished)
        #expect(throws: TicTacToe.Failure.finished) { try game.play(5, as: .o) }
        #expect(game.snapshot == Data([1, 1, 1, 2, 2, 0, 0, 0, 0]))
        #expect(TicTacToe(snapshot: game.snapshot) == game)
        #expect(TicTacToe(snapshot: Data([2, 2, 0, 0, 0, 0, 0, 0, 0])) == nil, "troppi O")

        var draw = TicTacToe()
        for (cell, mark) in zip([0, 1, 2, 4, 3, 5, 7, 6, 8], [TicTacToe.Mark.x, .o, .x, .o, .x, .o, .x, .o, .x]) {
            try draw.play(cell, as: mark)
        }
        #expect(draw.isDraw)
        #expect(draw.winner == nil)
    }

    @Test("Battaglia navale: flotta casuale valida, colpi, affondamenti e fine partita")
    func battleshipEngine() throws {
        var generator = SystemRandomNumberGenerator()
        let fleet = Battleship.randomFleet(using: &generator)
        #expect(Battleship.isValidFleet(fleet))
        #expect(fleet.count == 4)

        var board = Battleship(ships: fleet)
        let ship = fleet[0]
        var outcomes: [Battleship.Outcome] = []
        for cell in ship.cells { outcomes.append(try board.receiveShot(cell)) }
        #expect(outcomes.dropLast().allSatisfy { $0 == .hit })
        #expect(outcomes.last == .sunk)
        #expect(board.myShipsRemaining == 3)
        #expect(throws: Battleship.Failure.alreadyShot) { try board.receiveShot(ship.cells[0]) }
        #expect(throws: Battleship.Failure.outOfBounds) { try board.receiveShot(100) }

        let occupied = Set(fleet.flatMap(\.cells))
        let water = try #require((0..<100).first { !occupied.contains($0) })
        #expect(try board.receiveShot(water) == .miss)

        for other in fleet.dropFirst() {
            for cell in other.cells { _ = try board.receiveShot(cell) }
        }
        #expect(board.allMyShipsSunk)

        board.recordMyShot(5, outcome: .sunk)
        #expect(board.shotsSnapshot[5] == 3)
        #expect(!board.canShoot(5) && board.canShoot(6))
    }

    @Test("Tris sulla mesh: invito, accettazione, mosse alternate, vittoria di chi invita")
    func ticTacToeOverMesh() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        let aliceGames = GameService(router: alice.router, database: alice.database, identity: alice.identity, nickname: "Alice")
        let bobGames = GameService(router: bob.router, database: bob.database, identity: bob.identity, nickname: "Bob")
        await aliceGames.start()
        await bobGames.start()

        let conversation = try await alice.directConversation(with: bob)
        let invite = try await aliceGames.invite(kind: .ticTacToe, in: conversation)
        #expect(invite.status == .invited && invite.iAmInviter)

        #expect(await eventually { (try? await bobGames.session(gameID: invite.gameID)) != nil })
        let bobView = try await bobGames.session(gameID: invite.gameID)
        #expect(bobView.status == .invited && !bobView.iAmInviter)
        #expect(try await bob.database.messages.fetch(msgID: Data(hexString: invite.gameID) ?? Data())?.contentType == .game)

        try await bobGames.accept(gameID: invite.gameID)
        #expect(await eventually { (try? await aliceGames.session(gameID: invite.gameID))?.myTurn == true })

        // X: 0,1,2  O: 3,4
        try await aliceGames.play(gameID: invite.gameID, cell: 0)
        #expect(await eventually { (try? await bobGames.session(gameID: invite.gameID))?.myTurn == true })
        await #expect(throws: GameService.Failure.notYourTurn) { try await aliceGames.play(gameID: invite.gameID, cell: 1) }
        try await bobGames.play(gameID: invite.gameID, cell: 3)
        #expect(await eventually { (try? await aliceGames.session(gameID: invite.gameID))?.myTurn == true })
        try await aliceGames.play(gameID: invite.gameID, cell: 1)
        #expect(await eventually { (try? await bobGames.session(gameID: invite.gameID))?.myTurn == true })
        try await bobGames.play(gameID: invite.gameID, cell: 4)
        #expect(await eventually { (try? await aliceGames.session(gameID: invite.gameID))?.myTurn == true })
        let final = try await aliceGames.play(gameID: invite.gameID, cell: 2)
        #expect(final.outcome == .iWon && final.status == .finished)

        #expect(await eventually { (try? await bobGames.session(gameID: invite.gameID))?.outcome == .iLost })
        let bobFinal = try await bobGames.session(gameID: invite.gameID)
        #expect(bobFinal.ticTacToe?.snapshot == Data([1, 1, 1, 2, 2, 0, 0, 0, 0]))
        #expect(bobFinal.status == .finished)
    }

    @Test("Battaglia navale sulla mesh: colpo, report e affondamento aggiornano entrambi i giocatori")
    func battleshipOverMesh() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        let aliceGames = GameService(router: alice.router, database: alice.database, identity: alice.identity, nickname: "Alice")
        let bobGames = GameService(router: bob.router, database: bob.database, identity: bob.identity, nickname: "Bob")
        await aliceGames.start()
        await bobGames.start()

        let conversation = try await alice.directConversation(with: bob)
        let invite = try await aliceGames.invite(kind: .battleship, in: conversation)
        #expect(await eventually { (try? await bobGames.session(gameID: invite.gameID)) != nil })
        let accepted = try await bobGames.accept(gameID: invite.gameID)
        let bobShips = try #require(accepted.battleship?.myShips)
        #expect(Battleship.isValidFleet(bobShips))
        #expect(await eventually { (try? await aliceGames.session(gameID: invite.gameID))?.myTurn == true })

        // Alice conosce (nel test) la flotta di Bob e affonda la prima nave; Bob risponde sparando in acqua
        // (celle libere della flotta di Alice, piazzata alla ricezione dell'ACCEPT).
        let target = bobShips[0]
        let aliceShips = try #require(try await aliceGames.session(gameID: invite.gameID).battleship?.myShips)
        let occupiedByAlice = Set(aliceShips.flatMap(\.cells))
        var bobWaterShots = (0..<100).reversed().filter { !occupiedByAlice.contains($0) }.makeIterator()

        for (index, cell) in target.cells.enumerated() {
            let shot = try await aliceGames.play(gameID: invite.gameID, cell: cell)
            #expect(shot.pendingShot == cell)
            // Bob riceve il colpo, riporta l'esito e tocca a lui.
            #expect(await eventually { (try? await bobGames.session(gameID: invite.gameID))?.myTurn == true })
            // Alice riceve il REPORT.
            #expect(await eventually { (try? await aliceGames.session(gameID: invite.gameID))?.pendingShot == nil })
            let aliceView = try await aliceGames.session(gameID: invite.gameID)
            let expected: Battleship.Outcome = index == target.cells.count - 1 ? .sunk : .hit
            #expect(aliceView.battleship?.myShots[cell] == expected)

            let nextWaterShot = bobWaterShots.next()
            let bobShot = try #require(nextWaterShot)
            try await bobGames.play(gameID: invite.gameID, cell: bobShot)
            #expect(await eventually { (try? await bobGames.session(gameID: invite.gameID))?.pendingShot == nil })
            #expect(await eventually { (try? await aliceGames.session(gameID: invite.gameID))?.myTurn == true })
        }

        let aliceView = try await aliceGames.session(gameID: invite.gameID)
        #expect(aliceView.battleship?.enemyShipsSunk == 1)
        let bobView = try await bobGames.session(gameID: invite.gameID)
        #expect(bobView.battleship?.myShipsRemaining == 3)
        #expect(bobView.battleship?.myShots.count == target.cells.count)
        #expect(bobView.battleship?.myShots.values.allSatisfy { $0 == .miss } == true)

        try await bobGames.resign(gameID: invite.gameID)
        #expect(await eventually { (try? await aliceGames.session(gameID: invite.gameID))?.outcome == .iWon })
    }
}
