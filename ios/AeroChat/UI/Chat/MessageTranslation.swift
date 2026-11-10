import SwiftUI
import NaturalLanguage
#if canImport(Translation)
import Translation
#endif

/// Traduzione offline dei messaggi ricevuti (framework Translation, iOS 18+): le lingue scaricate sul
/// telefono permettono di capire il vicino di posto anche a 10.000 metri. Nulla lascia il dispositivo.
enum MessageTranslation {

    /// `true` dove il framework esiste (iOS 18 o successivi).
    static var isSupported: Bool {
        if #available(iOS 18.0, *) { return true }
        return false
    }

    /// Lingua dell'utente verso cui tradurre.
    static var targetLanguage: Locale.Language { Locale.current.language }

    /// Lingua rilevata di un testo (nil se incerta): i messaggi gia' nella lingua dell'utente non vanno tradotti.
    static func detectedLanguageCode(of text: String) -> String? {
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        guard let language = recognizer.dominantLanguage,
              let confidence = recognizer.languageHypotheses(withMaximum: 1)[language], confidence > 0.6 else { return nil }
        return language.rawValue
    }

    static func needsTranslation(_ text: String) -> Bool {
        guard let detected = detectedLanguageCode(of: text) else { return true }
        return detected != targetLanguage.languageCode?.identifier
    }
}

extension View {
    /// Applica il task di traduzione dove disponibile; su iOS 17 la vista resta invariata.
    @ViewBuilder
    func messageTranslation(_ viewModel: ConversationViewModel) -> some View {
        if #available(iOS 18.0, *) {
            modifier(MessageTranslationModifier(viewModel: viewModel))
        } else {
            self
        }
    }
}

/// Testo ricevuto da tradurre (valori semplici: attraversano il confine del main actor senza problemi).
private struct PendingTranslation: Sendable {
    let id: Data
    let text: String
}

@available(iOS 18.0, *)
private struct MessageTranslationModifier: ViewModifier {

    @Bindable var viewModel: ConversationViewModel
    @State private var configuration: TranslationSession.Configuration?

    func body(content: Content) -> some View {
        // La sessione non e' Sendable e va usata nel contesto del task; il view model si legge e si
        // aggiorna solo tramite closure isolate al main actor con valori semplici.
        let pendingTexts: @MainActor @Sendable () -> [PendingTranslation] = { [viewModel] in
            viewModel.messages.compactMap { message in
                guard !message.isOutgoing, message.contentType == .text, let text = message.text, !text.isEmpty,
                      viewModel.translations[message.msgID] == nil else { return nil }
                return PendingTranslation(id: message.msgID, text: text)
            }
        }
        let isEnabled: @MainActor @Sendable () -> Bool = { [viewModel] in viewModel.translationEnabled }
        let store: @MainActor @Sendable (Data, String) -> Void = { [viewModel] id, translation in
            viewModel.translations[id] = translation
        }
        return content
            .translationTask(configuration) { @concurrent session in
                // Una frase alla volta: in cabina le lingue sono miste e il framework rileva quella di ogni testo.
                for item in await pendingTexts() {
                    guard await isEnabled() else { return }
                    guard MessageTranslation.needsTranslation(item.text) else {
                        await store(item.id, "")
                        continue
                    }
                    do {
                        let response = try await session.translate(item.text)
                        await store(item.id, response.targetText == item.text ? "" : response.targetText)
                    } catch {
                        // Lingua non scaricata, download rifiutato o frase non riconosciuta: si passa oltre.
                        await store(item.id, "")
                        if error is CancellationError { return }
                    }
                }
            }
            .onChange(of: viewModel.translationEnabled, initial: true) { _, enabled in
                if enabled {
                    if configuration == nil {
                        configuration = TranslationSession.Configuration(source: nil, target: MessageTranslation.targetLanguage)
                    } else {
                        configuration?.invalidate()
                    }
                } else {
                    configuration = nil
                    viewModel.translations.removeAll()
                }
            }
            .onChange(of: viewModel.messages.last?.msgID) { _, _ in
                guard viewModel.translationEnabled else { return }
                configuration?.invalidate()
            }
    }
}
