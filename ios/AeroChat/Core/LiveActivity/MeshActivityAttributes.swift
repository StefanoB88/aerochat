import Foundation
import ActivityKit

/// Live Activity "A bordo": stato della mesh sulla Lock Screen e nella Dynamic Island.
/// ATTENZIONE: copia identica in `AeroChatWidgets/MeshActivityAttributes.swift` (ActivityKit abbina
/// app ed estensione per nome del tipo); ogni modifica va fatta in entrambi i file.
struct MeshActivityAttributes: ActivityAttributes {

    struct ContentState: Codable, Hashable {
        /// Telefoni AeroChat collegati direttamente.
        var neighborCount: Int
        /// Messaggi non letti in tutte le chat.
        var unreadCount: Int
        /// Titolo della Cabina del volo (es. "AZ 204 · FCO → LHR"), se presente.
        var flightTitle: String?
        /// Chat con il nostro turno in una partita, se c'e'.
        var yourTurnIn: String?
        var updatedAt: Date
    }

    /// Nickname locale, fisso per la durata dell'attivita'.
    var nickname: String
}
