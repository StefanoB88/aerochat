import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Intelligenza on-device (Apple Intelligence, iOS 26+): riassunto della Cabina e risposte rapide.
/// Funziona a 10.000 metri perche' il modello gira sul telefono; dove non c'e', le funzioni spariscono.
enum CabinAssistant {

    static var isAvailable: Bool {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            return SystemLanguageModel.default.isAvailable
        }
        #endif
        return false
    }

    private static var languageInstruction: String {
        let name = Locale.current.localizedString(forLanguageCode: Locale.current.language.languageCode?.identifier ?? "en") ?? "English"
        return "The person's locale is \(Locale.current.identifier). You MUST respond in \(name)."
    }

    /// Riassunto in 3-4 frasi delle ultime voci della Cabina (solo testo, nessun nome inventato).
    static func summarize(_ lines: [String]) async throws -> String {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            let session = LanguageModelSession(instructions: """
                You summarize a public, anonymous chat between airplane passengers. Write 3 or 4 short sentences: \
                what people are talking about, any practical info (delays, gates, taxis, meetups) and the general mood. \
                Never invent facts and never include links. \(languageInstruction)
                """)
            let transcript = lines.suffix(60).joined(separator: "\n")
            let response = try await session.respond(to: "Chat transcript:\n\(transcript)")
            return response.content.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        #endif
        throw CocoaError(.featureUnsupported)
    }

    /// Tre risposte brevi e naturali all'ultimo messaggio ricevuto.
    static func suggestReplies(history: [String], lastIncoming: String) async throws -> [String] {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            let session = LanguageModelSession(instructions: """
                You suggest quick replies in a casual chat between travel companions on a plane. \
                Each reply is at most 8 words, friendly, and sounds like a real person. \(languageInstruction)
                """)
            let context = history.suffix(8).joined(separator: "\n")
            let response = try await session.respond(to: "Conversation:\n\(context)\n\nLast message received: \"\(lastIncoming)\"",
                                                     generating: QuickReplies.self)
            return response.content.replies.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .prefix(3)
                .map { $0 }
        }
        #endif
        throw CocoaError(.featureUnsupported)
    }
}

#if canImport(FoundationModels)
@available(iOS 26.0, *)
@Generable
private struct QuickReplies {
    @Guide(description: "Exactly three short, distinct replies", .count(3))
    var replies: [String]
}
#endif
