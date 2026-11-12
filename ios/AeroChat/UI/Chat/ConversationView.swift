import SwiftUI
import PhotosUI
import CoreLocation
#if canImport(Translation)
import Translation
#endif

@MainActor
@Observable
final class ConversationViewModel {

    private(set) var conversation: ConversationRecord
    private(set) var messages: [MessageRecord] = []
    private(set) var reachability: Reachability = .outOfRange
    var draft = ""
    var photoItem: PhotosPickerItem?
    var errorMessage: String?
    var infoMessage: String?
    var showPaywall = false
    var showGroupInvite = false
    /// Scheda partecipanti (tocco sul nome del gruppo).
    var showGroupInfo = false
    /// Chat 1-a-1 scelta con "Scrivi" nella scheda: aperta appena la scheda si chiude.
    var pendingDirectChat: ConversationRecord?
    var openedDirectChat: ConversationRecord?
    var showCamera = false
    /// Messaggio ricevuto da tradurre (foglio di sistema "Traduci", offline con le lingue scaricate).
    var translateTarget: MessageRecord?
    /// Lettura GPS in corso per "Posizione".
    private(set) var isLocating = false
    @ObservationIgnored private let locator = OneShotLocator()
    /// Foto aperta nel visore a schermo intero.
    var viewedImage: ViewedImage?
    let player = VoiceNotePlayer()
    let recorder = VoiceNoteRecorder()
    private(set) var playingMessageID: Data?
    /// Foto scelte ma ancora in lettura/compressione: mostrate come segnaposto con spinner.
    private(set) var pendingPhotos: [UUID] = []
    /// Compagno della chat 1-a-1 (foto profilo e colore nell'header).
    private(set) var peer: PeerRecord?
    /// Membri del gruppo (noi esclusi): servono per proporre e organizzare lo Scopone.
    private(set) var groupMembers: [GroupMemberRecord] = []
    /// Scelta dei 3 giocatori dello Scopone.
    var showScoponePicker = false
    /// Partita a carte aperta nel tavolo a schermo intero.
    var openedCardTable: CardTableItem?
    /// Messaggio in corso di segnalazione (menu contestuale).
    var reportTarget: MessageRecord?
    /// Nostro messaggio in modifica: il testo e' nella barra di scrittura, l'invio manda la revisione.
    private(set) var editingMessage: MessageRecord?
    /// Nostro messaggio da eliminare per tutti (in attesa della conferma).
    var deleteTarget: MessageRecord?
    /// Contatti verificati di persona (QR): le foto degli altri restano sfocate finche' non vengono toccate.
    private(set) var trustedSenders: Set<Data> = []
    /// Risposte rapide suggerite dal modello on-device per l'ultimo messaggio ricevuto.
    private(set) var suggestions: [String] = []
    private var suggestionsFor: Data?
    private var suggestionsTask: Task<Void, Never>?

    private let environment: AppEnvironment
    private var tasks: [Task<Void, Never>] = []
    /// "Sta scrivendo" verso gli altri partecipanti (solo 1-a-1 e gruppi).
    @ObservationIgnored private var typingSender: TypingSender?

    init(conversation: ConversationRecord, environment: AppEnvironment) {
        self.conversation = conversation
        self.environment = environment
        typingSender = TypingSender { [weak self] isTyping in
            guard let self else { return }
            let conversation = self.conversation
            let environment = self.environment
            Task { await environment.sendTyping(isTyping, in: conversation) }
        }
        // Limite dei 120 s: la nota registrata parte comunque, come con il tap su "ferma".
        recorder.onAutoStop = { [weak self] recording in
            self?.sendVoiceNote(recording)
        }
    }

    var isGroup: Bool { conversation.type == .group }
    /// Chat del passeggero virtuale ("Prova subito"): niente radio, giochi con il bot locale.
    var isDemo: Bool { DemoPeerIdentity.isDemoPeer(conversation.peerNodeID) }

    /// Persone della conversazione a portata diretta (gruppi e Cabina), per "3 persone vicine".
    var nearbyCount: Int? {
        switch conversation.type {
        case .direct: return nil
        case .cabinLounge: return environment.neighbors.count
        case .group:
            let neighbors = Set(environment.neighbors.map(\.nodeID))
            return groupMembers.filter { neighbors.contains($0.nodeID) }.count
        }
    }

    /// Ultimo suggerimento della prova: dopo un paio di messaggi o una partita finita.
    var showsDemoInviteHint: Bool {
        guard isDemo else { return false }
        if messages.filter({ $0.isOutgoing && $0.contentType == .text }).count >= 2 { return true }
        return messages.contains { message in
            message.gameState.flatMap { try? GameSessionState.decode($0) }?.status == .finished
        }
    }
    var store: MediaStore? { environment.mediaStore }
    var showsAttachments: Bool { conversation.type != .cabinLounge }
    /// "Posizione" nel menu allegati: chat 1-a-1 e gruppi, non in Cabina Pubblica ne' nella Cabina del volo.
    var allowsLocation: Bool { conversation.type == .direct || (conversation.type == .group && !conversation.isFlightLounge) }

    /// Azione "Posizione" del menu allegati (nil dove non e' prevista).
    var locationAction: (() -> Void)? {
        guard allowsLocation else { return nil }
        return { [weak self] in self?.shareLocation() }
    }
    /// Avanzamento dei trasferimenti media in corso, per `msg_id` (osservato da `AppEnvironment`).
    var mediaProgress: [Data: Double] { environment.mediaProgress }

    func start() {
        guard tasks.isEmpty, let database = environment.database else { return }
        let id = conversation.id
        NotificationPresence.visibleConversationID = id
        tasks.append(Task { [weak self] in
            for await list in database.messages.observe(conversationID: id) {
                guard let self else { break }
                self.messages = list
                await self.markReadIfVisible()
                self.refreshSuggestions()
            }
        })
        if let peerID = conversation.peerNodeID {
            tasks.append(Task { [weak self] in
                for await updated in database.peers.observe(nodeID: peerID) {
                    guard let self else { break }
                    if updated != self.peer { self.peer = updated }
                }
            })
        }
        if conversation.type == .group {
            tasks.append(Task { [weak self] in
                for await list in database.conversations.observeGroupMembers(of: id) {
                    guard let self else { break }
                    if list != self.groupMembers { self.groupMembers = list }
                }
            })
        }
        tasks.append(Task { [weak self] in
            for await updated in database.conversations.observe(id: id) {
                guard let self, let updated else { continue }
                self.conversation = updated
            }
        })
        tasks.append(Task { [weak self] in
            for await list in database.peers.observeAll() {
                guard let self else { break }
                let trusted = Set(list.filter(\.isTrusted).map(\.nodeID))
                if trusted != self.trustedSenders { self.trustedSenders = trusted }
            }
        })
        tasks.append(Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { break }
                // In background (BLE tiene viva l'app) nessuno guarda l'header: niente letture DB ogni 3 s.
                if let router = self.environment.router, UIApplication.shared.applicationState == .active {
                    // Assegnare solo se cambia: ogni scrittura ridisegna l'header della chat.
                    let updated = await router.reachability(of: self.conversation)
                    if updated != self.reachability { self.reachability = updated }
                }
                try? await Task.sleep(for: .seconds(3))
            }
        })
    }

    func stop() {
        typingSender?.stop()
        if NotificationPresence.visibleConversationID == conversation.id {
            NotificationPresence.visibleConversationID = nil
        }
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        player.stop()
        recorder.cancel()
    }

    /// Conferma di lettura solo se l'utente vede davvero la chat: non a schermo bloccato o in background.
    func markReadIfVisible() async {
        guard UIApplication.shared.applicationState == .active, UIApplication.shared.isProtectedDataAvailable else { return }
        guard messages.contains(where: { !$0.isOutgoing && $0.deliveryStatus != .read }) || conversation.unreadCount > 0 else { return }
        await environment.markConversationRead(conversation)
    }

    // MARK: Sta scrivendo

    /// Chi sta scrivendo in questa chat (dagli altri partecipanti), per l'intestazione.
    var typingText: String? {
        TypingStore.headerText(for: environment.typing.typers(in: conversation.id), isGroup: isGroup)
    }

    /// Testo della barra cambiato: "sta scrivendo" agli altri (non durante la modifica di un messaggio).
    func draftChanged() {
        guard editingMessage == nil, MeshRouterActor.allowsTyping(in: conversation) else {
            typingSender?.stop()
            return
        }
        typingSender?.textChanged(draft)
    }

    /// App in background: niente "sta scrivendo" rimasto acceso.
    func stopTyping() {
        typingSender?.stop()
    }

    // MARK: Invio

    func sendText() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        if let editingMessage {
            commitEdit(of: editingMessage, text: text)
            return
        }
        guard !text.isEmpty, let router = environment.router else { return }
        if conversation.type == .cabinLounge, MeshRouterActor.containsLink(text) {
            infoMessage = String(localized: "Nella Cabina Pubblica non si possono condividere link.")
            return
        }
        draft = ""
        typingSender?.stop()
        Task {
            do {
                try await router.sendText(text, in: conversation)
                Haptics.impact(.light)
            } catch {
                errorMessage = String(localized: "Invio non riuscito: \(error.localizedDescription)")
            }
        }
    }

    // MARK: Posizione

    /// Una lettura GPS e invio come normale testo "📍 lat, lon (±m)" (permesso con il flusso standard).
    func shareLocation() {
        guard allowsLocation, !isLocating else { return }
        isLocating = true
        infoMessage = String(localized: "Rilevo la posizione…")
        locator.onLocation = { [weak self] location in
            guard let self, self.isLocating else { return }
            self.isLocating = false
            self.infoMessage = nil
            let accuracy = location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil
            self.sendLocationText(LocationShare.format(latitude: location.coordinate.latitude,
                                                       longitude: location.coordinate.longitude, accuracyMeters: accuracy))
        }
        locator.onFailure = { [weak self] in
            guard let self, self.isLocating else { return }
            self.isLocating = false
            // Permesso negato: l'avviso con "Apri Impostazioni" lo mostra gia' il locator.
            self.infoMessage = self.locator.isDenied ? nil : String(localized: "Posizione non disponibile: attiva la localizzazione e riprova all'aperto.")
        }
        locator.request()
        if locator.isDenied { isLocating = false; infoMessage = nil }
    }

    private func sendLocationText(_ text: String) {
        guard let router = environment.router else { return }
        Task {
            do {
                try await router.sendText(text, in: conversation)
                Haptics.impact(.light)
            } catch {
                errorMessage = String(localized: "Invio non riuscito: \(error.localizedDescription)")
            }
        }
    }

    // MARK: Modifica e cancellazione per tutti

    /// Voci del menu solo sui nostri messaggi entro 15 minuti e in una conversazione non scaduta.
    func canEdit(_ message: MessageRecord) -> Bool {
        message.canBeEdited() && !conversation.isExpired()
    }

    func canDeleteForEveryone(_ message: MessageRecord) -> Bool {
        message.canBeDeletedForEveryone() && !conversation.isExpired()
    }

    func startEditing(_ message: MessageRecord) {
        guard canEdit(message) else { return }
        Haptics.tap()
        editingMessage = message
        draft = message.text ?? ""
    }

    func cancelEditing() {
        editingMessage = nil
        draft = ""
    }

    private func commitEdit(of message: MessageRecord, text: String) {
        guard !text.isEmpty else { return }
        editingMessage = nil
        draft = ""
        guard text != message.text else { return }
        Task {
            do {
                try await environment.editMessage(message, newText: text)
                Haptics.impact(.light)
            } catch {
                errorMessage = AppEnvironment.revisionFailureText(error, isDelete: false)
            }
        }
    }

    /// Conferma "Elimina per tutti" presentata dalla bolla del messaggio scelto.
    func deleteConfirmationBinding(for message: MessageRecord) -> Binding<Bool> {
        Binding(get: { [weak self] in self?.deleteTarget?.msgID == message.msgID },
                set: { [weak self] presented in
                    guard let self, !presented, self.deleteTarget?.msgID == message.msgID else { return }
                    self.deleteTarget = nil
                })
    }

    func deleteForEveryone(_ message: MessageRecord) {
        if editingMessage?.msgID == message.msgID { cancelEditing() }
        if playingMessageID == message.msgID {
            player.stop()
            playingMessageID = nil
        }
        Task {
            do {
                try await environment.deleteMessageForEveryone(message)
                Haptics.impact(.medium)
            } catch {
                errorMessage = AppEnvironment.revisionFailureText(error, isDelete: true)
            }
        }
    }

    func sendPhoto(_ item: PhotosPickerItem) {
        photoItem = nil
        sendPhotoData { try await item.loadTransferable(type: Data.self) }
    }

    /// Apre la fotocamera (stesso controllo della libreria: foto solo con collegamento diretto).
    func takePhoto() {
        guard allows(.photo) else { return }
        Task {
            // Negata in passato: avviso con "Apri Impostazioni" a ogni tentativo.
            guard await PermissionPrompt.ensureCamera() else { return }
            showCamera = true
        }
    }

    /// Scatto completato dalla fotocamera (`nil` = annullato).
    func sendCapturedPhoto(_ data: Data?) {
        showCamera = false
        guard let data else { return }
        sendPhotoData { data }
    }

    /// Percorso comune libreria/fotocamera: segnaposto, compressione WebP fuori dal main thread, invio.
    private func sendPhotoData(loadData: @escaping () async throws -> Data?) {
        guard allows(.photo), let media = environment.media else { return }
        let pendingID = UUID()
        pendingPhotos.append(pendingID)
        Task {
            // Il segnaposto sparisce quando compare la bolla vera (o in caso di errore).
            defer { pendingPhotos.removeAll { $0 == pendingID } }
            do {
                guard let data = try await loadData() else { return }
                let prepared = try await Task.detached(priority: .userInitiated) { try ImageProcessor.prepareForMesh(imageData: data) }.value
                pendingPhotos.removeAll { $0 == pendingID }
                try await media.sendImage(prepared, in: conversation)
                Haptics.impact(.medium)
            } catch MediaTransferService.Failure.recipientOutOfRange {
                infoMessage = String(localized: "Compagno fuori portata: le foto partono solo con un collegamento attivo.")
            } catch {
                errorMessage = String(localized: "Foto non inviata: \(error.localizedDescription)")
            }
        }
    }

    func toggleRecording() {
        if recorder.isRecording {
            Task {
                do {
                    sendVoiceNote(try await recorder.stop())
                } catch VoiceNoteRecorder.Failure.tooShort {
                    infoMessage = String(localized: "Nota troppo breve.")
                } catch {
                    errorMessage = String(localized: "Registrazione non riuscita: \(error.localizedDescription)")
                }
            }
            return
        }
        guard allows(.voiceNote) else { return }
        Task {
            guard await PermissionPrompt.ensureMicrophone() else { return }
            do {
                try recorder.start()
                Haptics.impact(.rigid)
            } catch {
                errorMessage = String(localized: "Microfono non disponibile: \(error.localizedDescription)")
            }
        }
    }

    func saveImage(_ message: MessageRecord) {
        Task {
            guard let result = await saveImageToLibrary(message) else { return }
            if result.isError { errorMessage = result.text } else { infoMessage = result.text }
        }
    }

    /// Salvataggio nel rullino condiviso da menu della bolla e visore a schermo intero:
    /// restituisce il testo da mostrare (il visore lo presenta sopra di se').
    func saveImageToLibrary(_ message: MessageRecord) async -> (text: String, isError: Bool)? {
        guard let path = message.mediaLocalPath, let store else { return nil }
        guard await PermissionPrompt.ensurePhotosAddOnly() else { return nil }
        do {
            let data = try store.read(relativePath: path)
            try await PhotoLibrarySaver.save(imageData: data)
            Haptics.success()
            return (String(localized: "Foto salvata nel rullino."), false)
        } catch PhotoLibrarySaver.Failure.permissionDenied {
            PermissionPrompt.showDenied(.photos)
            return nil
        } catch {
            return (String(localized: "Foto non salvata: \(error.localizedDescription)"), true)
        }
    }

    private func sendVoiceNote(_ recording: VoiceNoteRecorder.Recording) {
        guard let media = environment.media else { return }
        Task {
            do {
                try await media.sendVoiceNote(recording, in: conversation)
                Haptics.impact(.medium)
            } catch MediaTransferService.Failure.recipientOutOfRange {
                infoMessage = String(localized: "Compagno fuori portata: le note vocali partono solo con un collegamento attivo.")
            } catch {
                errorMessage = String(localized: "Nota vocale non inviata: \(error.localizedDescription)")
            }
        }
    }

    // MARK: Chiamate

    /// Pulsante attivo (aspetto) solo quando una chiamata e' davvero possibile adesso.
    var canCallNow: Bool {
        FeatureGate.decision(for: .call, userIsPro: environment.isPro, conversation: conversation,
                             reachability: reachability) == .allowed
    }

    func startCall() {
        switch FeatureGate.decision(for: .call, userIsPro: environment.isPro, conversation: conversation, reachability: reachability) {
        case .allowed:
            guard let calls = environment.calls else { return }
            Haptics.impact(.medium)
            Task { await calls.placeCall(in: conversation) }
        case .peerOutOfRange:
            infoMessage = String(localized: "Compagno fuori portata: le chiamate richiedono un collegamento diretto.")
        default:
            _ = allows(.call)
        }
    }

    // MARK: Giochi

    /// Giochi del menu: nelle chat 1-a-1 tutti quelli a 2; nei gruppi da almeno 4 (noi compresi) solo lo Scopone.
    var availableGames: [GameKind] {
        switch conversation.type {
        case .direct: return isDemo ? DemoGameBot.supportedKinds : [.ticTacToe, .battleship, .scopa, .briscola]
        case .group: return groupMembers.count + 1 >= 4 ? [.scopone] : [.ticTacToe, .battleship]
        case .cabinLounge: return []
        }
    }

    /// Prima partita gratis (§B2): senza Pass, una partita al giorno e una per ogni volo per chi la avvia.
    private var isFreeGameAvailable: Bool {
        FreeGameAllowance.isAvailableNow(activeFlightCode: environment.activeFlightLounge?.flightCode)
    }

    /// `true` se serve consumare la partita gratis (niente Pass, ma partita gratis disponibile); il gate
    /// mostra il paywall come prima quando anche quella e' gia' usata.
    private func gameAccess() -> (allowed: Bool, usesFreeGame: Bool) {
        if isDemo { return (true, false) }
        let decision = FeatureGate.decision(for: .game, userIsPro: environment.isPro, conversation: conversation, reachability: reachability)
        if decision == .requiresPass, isFreeGameAvailable { return (true, true) }
        return (allows(.game), false)
    }

    private func consumeFreeGame() {
        FreeGameAllowance.consumeNow(activeFlightCode: environment.activeFlightLounge?.flightCode)
        infoMessage = String(localized: "Partita gratis 🎁 Ne hai una al giorno e una per ogni volo; dopo serve il Pass.")
    }

    func startGame(_ kind: GameKind) {
        let access = gameAccess()
        guard access.allowed, let games = environment.games else { return }
        if kind == .scopone {
            showScoponePicker = true
            return
        }
        Task {
            do {
                try await games.invite(kind: kind, in: conversation)
                // Si consuma quando l'invito parte.
                if access.usesFreeGame { consumeFreeGame() }
                Haptics.impact()
            } catch GameService.Failure.directConversationOnly {
                infoMessage = String(localized: "I mini-giochi si giocano nelle chat 1-a-1.")
            } catch {
                errorMessage = String(localized: "Sfida non inviata: \(error.localizedDescription)")
            }
        }
    }

    /// Scopone: invito ai 3 membri scelti (in ordine di posto 1..3).
    func inviteScopone(players: [Data]) {
        let access = gameAccess()
        guard access.allowed, let games = environment.games else { return }
        Task {
            do {
                try await games.invite(kind: .scopone, in: conversation, players: players)
                if access.usesFreeGame { consumeFreeGame() }
                Haptics.impact()
            } catch GameService.Failure.invalidPlayers {
                infoMessage = String(localized: "Scegli esattamente 3 membri del gruppo.")
            } catch {
                errorMessage = String(localized: "Sfida non inviata: \(error.localizedDescription)")
            }
        }
    }

    func handleGame(_ action: GameBubbleAction, message: MessageRecord) {
        handleGame(action, gameID: message.msgID.hexString)
    }

    func handleGame(_ action: GameBubbleAction, gameID: String) {
        if action == .openTable {
            Haptics.tap()
            openedCardTable = CardTableItem(id: gameID)
            return
        }
        guard let games = environment.games else { return }
        Task {
            do {
                switch action {
                case .accept: try await games.accept(gameID: gameID)
                case .play(let cell): try await games.play(gameID: gameID, cell: cell)
                case .resign: try await games.resign(gameID: gameID)
                case .playCard(let card, let captured): try await games.playCard(gameID: gameID, card: card, captured: captured)
                case .openTable: return
                }
                Haptics.impact(.light)
            } catch GameService.Failure.notYourTurn {
                infoMessage = String(localized: "Non è il tuo turno.")
            } catch GameService.Failure.invalidMove {
                Haptics.error()
                infoMessage = String(localized: "Mossa non valida.")
            } catch {
                errorMessage = String(localized: "Mossa non valida: \(error.localizedDescription)")
            }
        }
    }

    /// Stato aggiornato di una partita (il tavolo si ridisegna a ogni mossa ricevuta).
    func gameState(gameID: String) -> GameSessionState? {
        guard let message = messages.first(where: { $0.msgID.hexString == gameID }),
              let data = message.gameState else { return nil }
        return try? GameSessionState.decode(data)
    }

    // MARK: Audio

    func togglePlayback(_ message: MessageRecord) {
        guard let path = message.mediaLocalPath, let store else { return }
        if playingMessageID == message.msgID, player.isPlaying {
            player.pause()
            return
        }
        Task {
            do {
                if playingMessageID != message.msgID {
                    let data = try store.read(relativePath: path)
                    try await player.load(oggOpus: data, mediaPath: path)
                    playingMessageID = message.msgID
                }
                try player.play()
            } catch {
                errorMessage = String(localized: "Riproduzione non riuscita: \(error.localizedDescription)")
            }
        }
    }

    // MARK: Risposte rapide (Apple Intelligence)

    /// Tre suggerimenti per l'ultimo messaggio ricevuto; spariscono appena rispondiamo.
    func refreshSuggestions() {
        guard CabinAssistant.isAvailable, AppSettings.smartRepliesEnabled, conversation.type != .cabinLounge,
              let last = messages.last, !last.isOutgoing, last.contentType == .text, let text = last.text, !text.isEmpty else {
            suggestions = []
            suggestionsFor = nil
            suggestionsTask?.cancel()
            return
        }
        guard suggestionsFor != last.msgID else { return }
        suggestionsFor = last.msgID
        suggestions = []
        suggestionsTask?.cancel()
        let history = messages.suffix(8).compactMap { message -> String? in
            guard message.contentType == .text, let text = message.text else { return nil }
            return "\(message.isOutgoing ? String(localized: "Io") : (message.senderNickname ?? String(localized: "Compagno"))): \(text)"
        }
        suggestionsTask = Task { [weak self] in
            let replies = (try? await CabinAssistant.suggestReplies(history: history, lastIncoming: text)) ?? []
            guard !Task.isCancelled, let self, self.suggestionsFor == last.msgID else { return }
            self.suggestions = replies
        }
    }

    func useSuggestion(_ text: String) {
        editingMessage = nil
        draft = text
        suggestions = []
        sendText()
    }

    // MARK: Sicurezza

    /// Foto ricevuta da un mittente mai scansionato di persona: sfocata finche' l'utente non la tocca.
    func hidesMedia(_ message: MessageRecord) -> Bool {
        guard !message.isOutgoing, message.contentType == .image, AppSettings.blursStrangerMedia else { return false }
        return !trustedSenders.contains(message.senderNodeID)
    }

    func block(sender message: MessageRecord) {
        Task {
            await environment.blockUser(nodeID: message.senderNodeID, nickname: message.senderNickname)
            Haptics.warning()
            infoMessage = String(localized: "\(message.senderNickname ?? String(localized: "Utente")) bloccato. Puoi sbloccarlo da Impostazioni.")
        }
    }

    // MARK: Gate

    /// Applica il modello Freemium e spiega all'utente cosa manca.
    func allows(_ feature: ProFeature) -> Bool {
        // Passeggero virtuale: la prova dei giochi e' per tutti (tutto in locale).
        if isDemo, feature == .game { return true }
        switch FeatureGate.decision(for: feature, userIsPro: environment.isPro, conversation: conversation, reachability: reachability) {
        case .allowed:
            return true
        case .requiresPass:
            showPaywall = true
        case .photosNeedDirectLink:
            infoMessage = String(localized: "Sei connesso tramite un ponte: le foto richiedono una connessione diretta. Testo e mini-vocali restano attivi.")
        case .peerOutOfRange:
            infoMessage = String(localized: "Compagno fuori portata: il testo viene messo in coda, i media partono appena rientrate a portata.")
        case .loungeTextOnly:
            infoMessage = String(localized: "La Cabina Pubblica è solo testo.")
        case .callNeedsDirectLink:
            infoMessage = String(localized: "Le chiamate funzionano solo con il compagno a portata diretta, senza ponti.")
        case .callSignalTooWeak:
            infoMessage = String(localized: "Segnale troppo debole per una chiamata: avvicinatevi di qualche fila.")
        }
        return false
    }
}

/// Schermata chat con bolle BlurHash / forma d'onda / giochi e indicatore "Stato Radio" nell'header.
struct ConversationView: View {

    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel: ConversationViewModel?
    let conversation: ConversationRecord

    var body: some View {
        Group {
            if let viewModel {
                ConversationContent(viewModel: viewModel)
            } else {
                AeroTheme.background.ignoresSafeArea()
            }
        }
        .task {
            if viewModel == nil {
                viewModel = ConversationViewModel(conversation: conversation, environment: environment)
            }
            // Anche al ritorno da un altro tab: `onDisappear` ha fermato le osservazioni (start e' idempotente).
            viewModel?.start()
        }
        .onDisappear { viewModel?.stop() }
    }
}

private struct ConversationContent: View {

    @Bindable var viewModel: ConversationViewModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            AeroTheme.background.ignoresSafeArea()
            VStack(spacing: 0) {
                // Viste separate: i timer da 100 ms di registrazione/riproduzione invalidano solo
                // l'indicatore e la bolla in riproduzione, non l'intera lista dei messaggi.
                MessageListView(viewModel: viewModel)
                if !viewModel.suggestions.isEmpty {
                    SmartReplyBar(suggestions: viewModel.suggestions) { viewModel.useSuggestion($0) }
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                if viewModel.showsDemoInviteHint {
                    DemoInviteHint()
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                ComposerBar(text: $viewModel.draft,
                            photoItem: $viewModel.photoItem,
                            recorder: viewModel.recorder,
                            showsAttachments: viewModel.showsAttachments,
                            onSend: viewModel.sendText,
                            onToggleRecording: viewModel.toggleRecording,
                            onStartGame: viewModel.startGame,
                            availableGames: viewModel.availableGames,
                            onTakePhoto: viewModel.takePhoto,
                            onShareLocation: viewModel.locationAction,
                            isEditing: viewModel.editingMessage != nil,
                            onCancelEdit: viewModel.cancelEditing)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: 8) {
                    if viewModel.conversation.type == .direct {
                        AvatarView(imageData: viewModel.peer?.avatarImage, colorHex: viewModel.peer?.avatarColorHex, size: 32)
                    }
                    VStack(spacing: 2) {
                        HStack(spacing: 4) {
                            Text(viewModel.conversation.title)
                                .font(AeroTheme.title(17, weight: .semibold))
                                .foregroundStyle(AeroTheme.textPrimary)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                            if viewModel.isGroup {
                                Image(systemName: "chevron.right")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(AeroTheme.textSecondary)
                            }
                        }
                        if let typing = viewModel.typingText {
                            Text(typing)
                                .font(.caption.italic())
                                .foregroundStyle(AeroTheme.accentCyan)
                                .lineLimit(1)
                                .transition(.opacity)
                        } else if viewModel.isDemo {
                            Label("Passeggero virtuale · solo su questo iPhone", systemImage: "sparkles")
                                .font(.caption)
                                .foregroundStyle(AeroTheme.accentEmerald)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        } else {
                            RadioStatusView(reachability: viewModel.reachability, nearbyCount: viewModel.nearbyCount)
                        }
                    }
                    .animation(.easeInOut(duration: 0.2), value: viewModel.typingText)
                }
                // Gruppo: il nome apre i partecipanti (gesto, non Button: niente capsula di vetro sul titolo).
                .contentShape(Rectangle())
                .onTapGesture {
                    guard viewModel.isGroup else { return }
                    Haptics.tap()
                    viewModel.showGroupInfo = true
                }
                .accessibilityAddTraits(viewModel.isGroup ? .isButton : [])
                .accessibilityHint(viewModel.isGroup ? Text("Mostra i partecipanti del gruppo") : Text(""))
            }
            if viewModel.conversation.type == .direct, !viewModel.isDemo {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { viewModel.startCall() } label: {
                        Image(systemName: "phone.fill")
                            .foregroundStyle(AeroTheme.accentCyan)
                            .opacity(viewModel.canCallNow ? 1 : 0.35)
                    }
                    .accessibilityLabel("Chiamata vocale")
                    .accessibilityHint(viewModel.canCallNow ? Text("") : Text("Disponibile con il compagno a portata diretta"))
                }
            }
            if viewModel.isGroup {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { viewModel.showGroupInvite = true } label: {
                        Image(systemName: "qrcode").foregroundStyle(AeroTheme.accentCyan)
                    }
                    .accessibilityLabel("QR del gruppo")
                }
            }
        }
        .toolbarBackground(AeroTheme.background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .onChange(of: viewModel.photoItem) { _, item in
            if let item { viewModel.sendPhoto(item) }
        }
        .onChange(of: scenePhase) { _, phase in
            // Sblocco / ritorno in primo piano con la chat aperta: ora i messaggi sono davvero letti.
            if phase == .active { Task { await viewModel.markReadIfVisible() } }
            if phase == .background { viewModel.stopTyping() }
        }
        .onChange(of: viewModel.draft) { _, _ in
            viewModel.draftChanged()
        }
        .fullScreenCover(isPresented: $viewModel.showCamera) {
            CameraPicker { data in viewModel.sendCapturedPhoto(data) }
                .ignoresSafeArea()
        }
        .fullScreenCover(item: $viewModel.viewedImage) { viewed in
            if let path = viewed.message.mediaLocalPath {
                ImageViewer(relativePath: path, store: viewModel.store) {
                    await viewModel.saveImageToLibrary(viewed.message)
                }
            }
        }
        .fullScreenCover(item: $viewModel.openedCardTable) { table in
            if let state = viewModel.gameState(gameID: table.id) {
                CardTableView(state: state) { viewModel.handleGame($0, gameID: table.id) }
            } else {
                AeroTheme.background.ignoresSafeArea()
                    .onTapGesture { viewModel.openedCardTable = nil }
            }
        }
        .sheet(isPresented: $viewModel.showScoponePicker) {
            ScoponePlayerPicker(members: viewModel.groupMembers) { viewModel.inviteScopone(players: $0) }
        }
        .sheet(isPresented: $viewModel.showPaywall) { PaywallView() }
        .modifier(TranslateMessageModifier(target: $viewModel.translateTarget))
        .sheet(item: $viewModel.reportTarget) { message in
            ReportSheet(message: message) { _, _ in
                viewModel.infoMessage = String(localized: "Grazie: segnalazione registrata.")
            }
        }
        .sheet(isPresented: $viewModel.showGroupInvite) { QRPairingSheet(group: viewModel.conversation) }
        .sheet(isPresented: $viewModel.showGroupInfo, onDismiss: {
            // Push solo a scheda chiusa: insieme alla chiusura la navigazione puo' perdersi.
            if let direct = viewModel.pendingDirectChat {
                viewModel.pendingDirectChat = nil
                viewModel.openedDirectChat = direct
            }
        }) {
            GroupInfoSheet(group: viewModel.conversation) { viewModel.pendingDirectChat = $0 }
        }
        .navigationDestination(item: $viewModel.openedDirectChat) { direct in
            ConversationView(conversation: direct)
        }
        .alert("Ops", isPresented: Binding(get: { viewModel.errorMessage != nil }, set: { if !$0 { viewModel.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
        .overlay(alignment: .top) {
            if let info = viewModel.infoMessage {
                Text(info)
                    .font(.footnote)
                    .foregroundStyle(AeroTheme.textPrimary)
                    .padding(12)
                    .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .padding()
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task {
                        try? await Task.sleep(for: .seconds(4))
                        viewModel.infoMessage = nil
                    }
            }
        }
        .animation(.easeInOut, value: viewModel.infoMessage)
    }
}

private struct MessageListView: View {

    let viewModel: ConversationViewModel
    @State private var didInitialScroll = false

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(viewModel.messages, id: \.msgID) { message in
                        let isCurrent = viewModel.playingMessageID == message.msgID
                        MessageBubble(message: message,
                                      showSender: viewModel.isGroup || viewModel.conversation.type == .cabinLounge,
                                      store: viewModel.store,
                                      player: isCurrent ? viewModel.player : nil,
                                      isPlaying: isCurrent && viewModel.player.isPlaying,
                                      transferProgress: viewModel.mediaProgress[message.msgID],
                                      onTogglePlayback: { viewModel.togglePlayback(message) },
                                      onGameAction: { viewModel.handleGame($0, message: message) },
                                      onSaveImage: { viewModel.saveImage(message) },
                                      onOpenImage: { viewModel.viewedImage = ViewedImage(message: message) },
                                      hidesMediaUntilTap: viewModel.hidesMedia(message),
                                      onDeleteForEveryone: viewModel.canDeleteForEveryone(message)
                                          ? { Haptics.tap(); viewModel.deleteTarget = message } : nil)
                            .id(message.msgID)
                            .contextMenu {
                                if viewModel.canEdit(message) {
                                    Button {
                                        viewModel.startEditing(message)
                                    } label: {
                                        Label("Modifica", systemImage: "pencil")
                                    }
                                }
                                if viewModel.canDeleteForEveryone(message) {
                                    Button(role: .destructive) {
                                        Haptics.tap()
                                        viewModel.deleteTarget = message
                                    } label: {
                                        Label("Elimina per tutti", systemImage: "trash")
                                    }
                                }
                                if TranslateMessageModifier.isAvailable, !message.isOutgoing, !message.isDeleted,
                                   message.contentType == .text, !(message.text ?? "").isEmpty {
                                    Button {
                                        Haptics.tap()
                                        viewModel.translateTarget = message
                                    } label: {
                                        Label("Traduci", systemImage: "translate")
                                    }
                                }
                                if !message.isOutgoing, !message.isDeleted, message.contentType != .game {
                                    Button {
                                        Haptics.tap()
                                        viewModel.reportTarget = message
                                    } label: {
                                        Label("Segnala messaggio", systemImage: "exclamationmark.bubble")
                                    }
                                    Button(role: .destructive) {
                                        Haptics.tap()
                                        viewModel.block(sender: message)
                                    } label: {
                                        Label("Blocca \(message.senderNickname ?? viewModel.conversation.title)", systemImage: "hand.raised.fill")
                                    }
                                }
                            }
                            // Conferma agganciata alla bolla: su iOS 26 il popover parte dal messaggio.
                            .confirmationDialog("Eliminare il messaggio per tutti?",
                                                isPresented: viewModel.deleteConfirmationBinding(for: message),
                                                titleVisibility: .visible) {
                                Button("Elimina per tutti", role: .destructive) { viewModel.deleteForEveryone(message) }
                                Button("Annulla", role: .cancel) {}
                            } message: {
                                Text("Il messaggio sparirà anche dai dispositivi degli altri partecipanti.")
                            }
                    }
                    ForEach(viewModel.pendingPhotos, id: \.self) { id in
                        PendingPhotoBubble().id(id)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 12)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .dismissesKeyboardOnTap()
            .onChange(of: viewModel.messages.last?.msgID) { _, last in
                guard let last else { return }
                // Primo caricamento senza animazione: niente scorrimento animato attraverso tutta la storia.
                if didInitialScroll {
                    withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last, anchor: .bottom) }
                } else {
                    didInitialScroll = true
                    proxy.scrollTo(last, anchor: .bottom)
                }
            }
            .onChange(of: viewModel.pendingPhotos.last) { _, pending in
                guard let pending else { return }
                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(pending, anchor: .bottom) }
            }
            .onAppear {
                if let last = viewModel.messages.last?.msgID {
                    didInitialScroll = true
                    proxy.scrollTo(last, anchor: .bottom)
                }
            }
        }
    }
}

/// Chip con le risposte rapide suggerite dal modello on-device, sopra la barra di composizione.
private struct SmartReplyBar: View {

    let suggestions: [String]
    let onPick: (String) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Image(systemName: "sparkles")
                    .font(.caption)
                    .foregroundStyle(AeroTheme.accentCyan)
                ForEach(suggestions, id: \.self) { suggestion in
                    Button {
                        Haptics.tap()
                        onPick(suggestion)
                    } label: {
                        Text(suggestion)
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(AeroTheme.textPrimary)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(AeroTheme.surface, in: Capsule())
                            .overlay(Capsule().strokeBorder(AeroTheme.accentCyan.opacity(0.35), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
        }
        .background(AeroTheme.background)
        .accessibilityLabel("Risposte rapide suggerite")
    }
}

/// Partita a carte presentata nel tavolo a schermo intero (`gameID` = hex del msg_id dell'invito).
struct CardTableItem: Identifiable, Hashable {
    let id: String
}

/// Suggerimento finale della prova: dal passeggero virtuale alle persone vere.
private struct DemoInviteHint: View {

    @Environment(AppEnvironment.self) private var environment
    @State private var showPairing = false

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "person.wave.2.fill")
                .foregroundStyle(AeroTheme.accentCyan)
            VStack(alignment: .leading, spacing: 2) {
                Text("Invita chi è vicino a te")
                    .font(AeroTheme.title(15, weight: .semibold))
                    .foregroundStyle(AeroTheme.textPrimary)
                Text("Con una persona vera i messaggi passano davvero da telefono a telefono.")
                    .font(.caption)
                    .foregroundStyle(AeroTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            Menu {
                Button { Haptics.tap(); environment.requestedNearby = .all } label: { Label("Persone vicine", systemImage: "person.wave.2") }
                Button { Haptics.tap(); showPairing = true } label: { Label("Mostra il mio QR", systemImage: "qrcode") }
            } label: {
                Text("Invita")
                    .font(.subheadline.weight(.bold))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(AeroTheme.accentCyan, in: Capsule())
                    .foregroundStyle(.black)
            }
        }
        .padding(12)
        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.bottom, 6)
        .sheet(isPresented: $showPairing) { QRPairingSheet() }
    }
}

/// "Traduci" su un messaggio ricevuto: foglio di sistema del framework Translation (iOS 17.4+), che funziona
/// senza internet con le lingue scaricate.
struct TranslateMessageModifier: ViewModifier {

    @Binding var target: MessageRecord?

    static var isAvailable: Bool {
        if #available(iOS 17.4, *) { return true }
        return false
    }

    func body(content: Content) -> some View {
        #if canImport(Translation)
        if #available(iOS 17.4, *) {
            content.translationPresentation(isPresented: Binding(get: { target != nil }, set: { if !$0 { target = nil } }),
                                            text: target?.text ?? "")
        } else {
            content
        }
        #else
        content
        #endif
    }
}
