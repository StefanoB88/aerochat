import Foundation
import GRDB

struct ReportRepository: Sendable {

    let database: AppDatabase

    @discardableResult
    func insert(_ report: ReportRecord) async throws -> ReportRecord {
        try await database.pool.write { db in
            var record = report
            try record.insert(db)
            return record
        }
    }

    func fetchAll() async throws -> [ReportRecord] {
        try await database.pool.read { db in
            try ReportRecord.order(ReportRecord.Columns.createdAtMs.desc).fetchAll(db)
        }
    }

    func count() async throws -> Int {
        try await database.pool.read { db in
            try ReportRecord.fetchCount(db)
        }
    }

    func delete(id: Int64) async throws {
        _ = try await database.pool.write { db in
            try ReportRecord.deleteOne(db, key: id)
        }
    }

    func observeAll() -> AsyncStream<[ReportRecord]> {
        database.pool.observeStream { db in
            try ReportRecord.order(ReportRecord.Columns.createdAtMs.desc).fetchAll(db)
        }
    }
}
