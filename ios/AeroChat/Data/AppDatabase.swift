import Foundation
import GRDB

/// Wrapper del database SQLite dell'app.
///
/// - `DatabasePool` => modalita' **WAL** attiva: le letture della UI (osservazioni) non vengono
///   mai bloccate dalle scritture del `MeshRouterActor` in background.
/// - La directory del DB usa `completeUntilFirstUserAuthentication` cosi' SQLite resta
///   accessibile anche a schermo bloccato (pacchetti BLE ricevuti in background).
final class AppDatabase: Sendable {

    let pool: DatabasePool

    /// Apre (e migra) il database all'URL indicato.
    init(url: URL) throws {
        var configuration = Configuration()
        configuration.busyMode = .timeout(5)
        configuration.label = "AeroMesh.DB"
        configuration.prepareDatabase { db in
            // WAL + synchronous=NORMAL e' la combinazione consigliata: durabilita' dei commit a livello
            // di checkpoint, nessun fsync su ogni scrittura radio.
            try db.execute(sql: "PRAGMA synchronous = NORMAL")
        }
        pool = try DatabasePool(path: url.path, configuration: configuration)
        try AppDatabase.migrator.migrate(pool)
    }

    /// Database condiviso dell'app in `Application Support/AeroMesh/aeromesh.sqlite`.
    static func makeShared() throws -> AppDatabase {
        let fileManager = FileManager.default
        let support = try fileManager.url(for: .applicationSupportDirectory,
                                          in: .userDomainMask,
                                          appropriateFor: nil,
                                          create: true)
        let folder = support.appendingPathComponent("AeroMesh", isDirectory: true)
        if !fileManager.fileExists(atPath: folder.path) {
            try fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
        }
        try? fileManager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: folder.path
        )
        return try AppDatabase(url: folder.appendingPathComponent("aeromesh.sqlite"))
    }

    /// Database usa-e-getta in una cartella temporanea (test, preview).
    static func makeTemporary() throws -> AppDatabase {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("AeroMeshTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return try AppDatabase(url: folder.appendingPathComponent("aeromesh.sqlite"))
    }

    /// Percorso del file (diagnostica).
    var path: String { pool.path }

    // MARK: - Repository

    var identities: IdentityRepository { IdentityRepository(database: self) }
    var peers: PeerRepository { PeerRepository(database: self) }
    var conversations: ConversationRepository { ConversationRepository(database: self) }
    var messages: MessageRepository { MessageRepository(database: self) }
    var seenPackets: SeenPacketStore { SeenPacketStore(database: self) }
    var relayQueue: RelayQueueStore { RelayQueueStore(database: self) }

    // MARK: - Diagnostica

    /// Nomi di tabelle e tabelle virtuali presenti (esclusi gli oggetti interni di SQLite).
    func tableNames() async throws -> Set<String> {
        try await pool.read { db in
            Set(try String.fetchAll(
                db,
                sql: "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
            ))
        }
    }

    /// `true` se il database gira in modalita' WAL.
    func isWALEnabled() async throws -> Bool {
        try await pool.read { db in
            (try String.fetchOne(db, sql: "PRAGMA journal_mode") ?? "").lowercased() == "wal"
        }
    }

    // MARK: - Manutenzione

    /// Job periodico di pulizia: pacchetti visti scaduti, coda relay scaduta, Lounge oltre le 12h.
    func runMaintenance(now: Date = Date()) async throws {
        let nowMs = now.unixMilliseconds
        try await pool.write { db in
            try SeenPacketRecord.filter(SeenPacketRecord.Columns.expiresAtMs <= nowMs).deleteAll(db)
            try RelayQueueRecord.filter(RelayQueueRecord.Columns.expiresAtMs <= nowMs).deleteAll(db)
            try MessageRecord.purgeExpiredLoungeMessages(db, nowMs: nowMs)
            try ConversationRecord.purgeExpired(db, nowMs: nowMs)
        }
    }
}
