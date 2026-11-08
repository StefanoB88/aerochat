import Foundation
import GRDB

extension AppDatabase {

    /// Migrazioni dello schema. Ogni cambiamento futuro va aggiunto come nuova migrazione,
    /// mai modificando quelle gia' pubblicate.
    static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()

        #if DEBUG
        // In sviluppo ricrea il DB se lo schema cambia (evita stati incoerenti tra build).
        migrator.eraseDatabaseOnSchemaChange = true
        #endif

        migrator.registerMigration("v1_initial_schema") { db in

            // MARK: identity (singola riga, id = 1)
            try db.create(table: "identity") { t in
                t.column("id", .integer).primaryKey().check { $0 == 1 }
                t.column("node_id", .blob).notNull()
                t.column("x25519_pub", .blob).notNull()
                t.column("ed25519_pub", .blob).notNull()
                t.column("nickname", .text).notNull()
                t.column("avatar_color_hex", .text).notNull()
                t.column("pro_tier", .text).notNull().defaults(to: "free")
                t.column("pro_valid_until_ms", .integer)
                t.column("pro_receipt_jws", .text)
                t.column("created_at_ms", .integer).notNull()
            }

            // MARK: peers
            try db.create(table: "peers") { t in
                t.primaryKey("node_id", .blob)
                t.column("x25519_pub", .blob).notNull().unique()
                t.column("ed25519_pub", .blob).notNull()
                t.column("nickname", .text).notNull()
                t.column("is_trusted", .boolean).notNull().defaults(to: false)
                t.column("is_blocked", .boolean).notNull().defaults(to: false)
                t.column("last_seen_at_ms", .integer)
                t.column("last_hop_count", .integer)
                t.column("last_rssi", .integer)
                t.column("created_at_ms", .integer).notNull()
            }
            try db.create(indexOn: "peers", columns: ["is_trusted"])

            // MARK: conversations
            try db.create(table: "conversations") { t in
                t.primaryKey("id", .text)
                t.column("type", .text).notNull()
                t.column("title", .text).notNull()
                // 8 byte: peer node_id (direct), SHA256(group_uuid)[0..8] (group), 8 zeri (cabin_lounge).
                t.column("recipient_id", .blob).notNull()
                t.column("peer_node_id", .blob).references("peers", onDelete: .setNull)
                t.column("group_secret_key", .blob)
                t.column("pro_unlocked_until_ms", .integer)
                t.column("created_at_ms", .integer).notNull()
                t.column("expires_at_ms", .integer)
                t.column("last_message_at_ms", .integer)
                t.column("unread_count", .integer).notNull().defaults(to: 0)
            }
            try db.create(indexOn: "conversations", columns: ["recipient_id"])
            try db.create(indexOn: "conversations", columns: ["expires_at_ms"])

            // MARK: group_members (necessaria al Group Key Gossip)
            try db.create(table: "group_members") { t in
                t.column("conversation_id", .text).notNull().references("conversations", onDelete: .cascade)
                t.column("node_id", .blob).notNull()
                t.column("nickname", .text).notNull()
                t.column("x25519_pub", .blob).notNull()
                t.column("ed25519_pub", .blob).notNull()
                t.column("joined_at_ms", .integer).notNull()
                t.primaryKey(["conversation_id", "node_id"])
            }

            // MARK: messages
            try db.create(table: "messages") { t in
                t.primaryKey("msg_id", .blob)
                t.column("conversation_id", .text).notNull().references("conversations", onDelete: .cascade)
                t.column("sender_node_id", .blob).notNull()
                t.column("sender_nickname", .text)
                t.column("content_type", .text).notNull()
                t.column("text", .text)
                t.column("media_local_path", .text)
                t.column("blurhash", .text)
                t.column("audio_waveform", .blob)
                t.column("duration_ms", .integer)
                t.column("reply_to_msg_id", .blob)
                t.column("game_state", .blob)
                t.column("is_outgoing", .boolean).notNull()
                t.column("delivery_status", .text).notNull()
                t.column("hop_count_received", .integer)
                t.column("created_at_ms", .integer).notNull()
            }
            try db.create(indexOn: "messages", columns: ["conversation_id", "created_at_ms"])

            // MARK: messages_fts (FTS5, external content su `messages`, sincronizzata via trigger)
            try db.create(virtualTable: "messages_fts", using: FTS5()) { t in
                t.synchronize(withTable: "messages")
                t.tokenizer = .unicode61()
                t.column("text")
            }

            // MARK: seen_packets (deduplicazione managed flooding)
            try db.create(table: "seen_packets") { t in
                t.primaryKey("msg_id", .blob)
                t.column("expires_at_ms", .integer).notNull()
            }
            try db.create(indexOn: "seen_packets", columns: ["expires_at_ms"])

            // MARK: relay_queue (store-and-forward)
            try db.create(table: "relay_queue") { t in
                t.primaryKey("msg_id", .blob)
                t.column("recipient_id", .blob).notNull()
                t.column("raw_envelope_bytes", .blob).notNull()
                t.column("size_bytes", .integer).notNull()
                t.column("enqueued_at_ms", .integer).notNull()
                t.column("expires_at_ms", .integer).notNull()
            }
            try db.create(indexOn: "relay_queue", columns: ["expires_at_ms"])
            try db.create(indexOn: "relay_queue", columns: ["enqueued_at_ms"])
        }

        // Identificatore CoreBluetooth del peripheral del compagno fidato: serve a
        // `retrievePeripherals(withIdentifiers:)` + `connect(options: nil)` per la riconnessione
        // automatica infinita in background (fix iOS <-> Android a schermo spento).
        migrator.registerMigration("v2_peer_ble_identifier") { db in
            try db.alter(table: "peers") { t in
                t.add(column: "ble_identifier", .text)
            }
        }

        // Chat fissate in cima alla lista (Tab 1).
        migrator.registerMigration("v3_conversation_pinned") { db in
            try db.alter(table: "conversations") { t in
                t.add(column: "is_pinned", .boolean).notNull().defaults(to: false)
            }
        }

        // Profilo modificabile e foto profilo condivisa con i contatti (`PROFILE_UPDATE`).
        migrator.registerMigration("v4_profiles") { db in
            try db.alter(table: "identity") { t in
                t.add(column: "avatar_image", .blob)
                t.add(column: "profile_updated_at_ms", .integer)
            }
            try db.alter(table: "peers") { t in
                t.add(column: "avatar_color_hex", .text)
                t.add(column: "avatar_image", .blob)
                t.add(column: "profile_updated_at_ms", .integer)
            }
        }

        // Lettori dei nostri messaggi di gruppo (`READ_RECEIPT`): la spunta blu arriva solo quando
        // hanno letto tutti i membri. I record seguono il messaggio eliminato (`ON DELETE CASCADE`).
        migrator.registerMigration("v5_message_reads") { db in
            try db.create(table: "message_reads") { t in
                t.column("msg_id", .blob).notNull().references("messages", onDelete: .cascade)
                t.column("reader_node_id", .blob).notNull()
                t.column("read_at_ms", .integer).notNull()
                t.primaryKey(["msg_id", "reader_node_id"])
            }
        }

        return migrator
    }
}
