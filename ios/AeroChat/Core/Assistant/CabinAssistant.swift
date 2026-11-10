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

    /// Riga della Cabina passata al riassunto.
    struct ChatLine: Sendable {
        let author: String
        let text: String
    }

    /// Sotto questa soglia non c'e' nulla da riassumere: il modello, con pochi dati, inventava.
    static let minimumLinesForSummary = 3

    /// Riassunto ancorato alla chat: il modello riceve le righe numerate e deve citare, per ogni punto,
    /// le righe da cui lo ricava; i punti senza righe valide o senza parole in comune con esse vengono
    /// scartati. Campionamento greedy (niente creativita'). Ritorna i punti come elenco puntato.
    static func summarize(_ lines: [ChatLine]) async throws -> [String] {
        let recent = Array(lines.suffix(50))
        guard recent.count >= minimumLinesForSummary else { return [] }
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            let session = LanguageModelSession(instructions: """
                You summarize a public chat between airplane passengers. You receive numbered lines in the \
                format "[n] author: text". Report only what is explicitly written in those lines: topics people \
                discuss, questions asked, practical info (delays, gates, seats, taxis, meetups). Every point must \
                list the line numbers it comes from. If something is not written in the lines, do not say it. \
                Do not guess feelings, places or events. Do not include links. \(languageInstruction)
                """)
            let transcript = recent.enumerated().map { index, line in
                "[\(index + 1)] \(line.author): \(line.text.prefix(200))"
            }.joined(separator: "\n")
            let response = try await session.respond(to: "Chat lines:\n\(transcript)",
                                                     generating: CabinDigest.self,
                                                     options: GenerationOptions(sampling: .greedy))
            return grounded(response.content.points.map { ($0.summary, $0.sourceLines) }, lines: recent)
        }
        #endif
        throw CocoaError(.featureUnsupported)
    }

    /// Tiene solo i punti che citano righe esistenti e condividono con esse almeno una parola significativa.
    static func grounded(_ points: [(summary: String, sources: [Int])], lines: [ChatLine]) -> [String] {
        func words(_ text: String) -> Set<String> {
            Set(text.lowercased()
                .folding(options: .diacriticInsensitive, locale: nil)
                .split { !$0.isLetter && !$0.isNumber }
                .map(String.init)
                .filter { $0.count >= 4 || $0.allSatisfy(\.isNumber) })
        }
        var seen = Set<String>()
        return points.compactMap { point in
            let summary = point.summary.trimmingCharacters(in: .whitespacesAndNewlines)
            let valid = point.sources.filter { (1...lines.count).contains($0) }
            guard !summary.isEmpty, !valid.isEmpty, seen.insert(summary.lowercased()).inserted else { return nil }
            let sourceWords = valid.reduce(into: Set<String>()) { result, index in
                result.formUnion(words(lines[index - 1].text))
                result.formUnion(words(lines[index - 1].author))
            }
            return words(summary).isDisjoint(with: sourceWords) ? nil : summary
        }
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
private struct CabinDigest {
    @Guide(description: "Up to 5 facts or topics taken literally from the chat lines", .maximumCount(5))
    var points: [DigestPoint]
}

@available(iOS 26.0, *)
@Generable
private struct DigestPoint {
    @Guide(description: "The line numbers this point is taken from", .minimumCount(1))
    var sourceLines: [Int]
    @Guide(description: "One short sentence restating what those lines say, without adding anything")
    var summary: String
}

@available(iOS 26.0, *)
@Generable
private struct QuickReplies {
    @Guide(description: "Exactly three short, distinct replies", .count(3))
    var replies: [String]
}
#endif
