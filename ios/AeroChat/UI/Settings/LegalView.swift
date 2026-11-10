import SwiftUI

/// Documenti legali consultabili offline (a bordo non c'e' rete): Termini di utilizzo e Informativa privacy.
enum LegalDocument: String, Identifiable {
    case terms
    case privacy

    var id: String { rawValue }

    var title: String {
        switch self {
        case .terms: return String(localized: "Termini di utilizzo")
        case .privacy: return String(localized: "Informativa privacy")
        }
    }

    var onlineURL: URL {
        switch self {
        case .terms: return AppLinks.termsOfUse
        case .privacy: return AppLinks.privacyPolicy
        }
    }

    struct Section: Identifiable {
        let id = UUID()
        let heading: String
        let body: String
    }

    var sections: [Section] {
        switch self {
        case .terms:
            return [
                Section(heading: String(localized: "1. Il servizio"),
                        body: String(localized: "AeroChat permette di scambiare messaggi, foto, note vocali, chiamate e mini-giochi tra dispositivi vicini tramite Bluetooth, senza server e senza account. Il servizio è fornito così com'è: la copertura dipende dai telefoni presenti e non è garantita.")),
                Section(heading: String(localized: "2. Comportamento"),
                        body: String(localized: "Non è consentito usare AeroChat per molestare, minacciare, discriminare, diffondere spam, contenuti sessuali espliciti, violenti o illegali, né per impersonare altre persone. Nella Cabina Pubblica valgono inoltre le regole mostrate alla prima apertura. Chi viola queste condizioni può essere bloccato dagli altri passeggeri e segnalato.")),
                Section(heading: String(localized: "3. Segnalazioni e blocco"),
                        body: String(localized: "Ogni messaggio ricevuto può essere segnalato e il suo autore bloccato con un tocco. Il blocco è immediato e vale su questo dispositivo: i messaggi dell'utente bloccato non vengono più mostrati né ritrasmessi. Le segnalazioni inoltrate via email vengono esaminate entro 24 ore e possono portare alla rimozione di chi abusa dalle future versioni dell'app.")),
                Section(heading: String(localized: "4. Acquisti"),
                        body: String(localized: "Il Flight Group Pass è un acquisto una tantum gestito dall'App Store, senza rinnovo automatico. Il Pass 72 ore scade al termine della sua durata; il Pass Lifetime è permanente. Il pass viene condiviso con i membri dei tuoi gruppi per la durata indicata. I rimborsi seguono le politiche di Apple.")),
                Section(heading: String(localized: "5. Età minima"),
                        body: String(localized: "AeroChat è destinata a chi ha almeno 13 anni. La Cabina Pubblica mette in contatto sconosciuti: i minori dovrebbero usarla con la supervisione di un adulto.")),
                Section(heading: String(localized: "6. Limitazione di responsabilità"),
                        body: String(localized: "I contenuti scambiati sono creati dagli utenti e cifrati end-to-end: non possiamo leggerli né moderarli in modo centralizzato. Non rispondiamo di messaggi non recapitati, di perdite di dati o dell'uso dell'app in violazione delle norme della compagnia aerea o del luogo in cui ti trovi.")),
                Section(heading: String(localized: "7. Modifiche"),
                        body: String(localized: "Possiamo aggiornare questi termini con una nuova versione dell'app. L'uso continuato dopo l'aggiornamento equivale ad accettazione. Per gli acquisti in-app si applica anche il contratto di licenza standard di Apple (EULA).")),
            ]
        case .privacy:
            return [
                Section(heading: String(localized: "Nessun account, nessun server"),
                        body: String(localized: "AeroChat non richiede email, numero di telefono o registrazione. Non abbiamo server che ricevono i tuoi messaggi: viaggiano direttamente tra i telefoni via Bluetooth, cifrati end-to-end (X25519, Ed25519, ChaCha20-Poly1305).")),
                Section(heading: String(localized: "Dati sul dispositivo"),
                        body: String(localized: "Nickname, colore o foto profilo, chiavi crittografiche, contatti aggiunti via QR, messaggi, media, partite e segnalazioni restano sul tuo iPhone, in una cartella protetta dal sistema. Puoi eliminarli cancellando le chat o disinstallando l'app.")),
                Section(heading: String(localized: "Cosa vedono gli altri"),
                        body: String(localized: "I compagni con cui chatti vedono nickname, colore e foto profilo. In Cabina Pubblica gli altri passeggeri vedono solo il nickname e un identificativo tecnico anonimo del tuo dispositivo, che cambia se reinstalli l'app. I telefoni intermedi che fanno da ponte vedono solo pacchetti cifrati.")),
                Section(heading: String(localized: "Autorizzazioni"),
                        body: String(localized: "Bluetooth: indispensabile per la rete mesh. Fotocamera: QR code, boarding pass e foto da inviare. Microfono: note vocali e chiamate. Foto: solo le immagini che scegli di condividere o salvare. Notifiche: avvisi dei messaggi in arrivo. Ogni autorizzazione è facoltativa, tranne il Bluetooth.")),
                Section(heading: String(localized: "Nessun tracciamento"),
                        body: String(localized: "L'app non contiene SDK pubblicitari, analytics di terze parti o identificatori per il tracciamento. L'unica comunicazione con l'esterno è quella con l'App Store per gli acquisti in-app, gestita da Apple.")),
                Section(heading: String(localized: "Segnalazioni"),
                        body: String(localized: "Se scegli di inoltrare una segnalazione via email, riceviamo il tuo indirizzo email, il nickname e l'identificativo anonimo dell'utente segnalato e il testo del messaggio. Li usiamo solo per gestire la segnalazione e li cancelliamo entro 90 giorni.")),
                Section(heading: String(localized: "Contatti"),
                        body: String(localized: "Per domande sulla privacy scrivi a \(AppLinks.supportEmail). Titolare del trattamento: lo sviluppatore indicato nella scheda dell'App Store.")),
            ]
        }
    }
}

struct LegalView: View {

    let document: LegalDocument
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            LegalContentView(document: document)
                .navigationTitle(document.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() } }
                }
        }
        .preferredColorScheme(.dark)
    }
}

/// Contenuto del documento, riutilizzato dal foglio modale e dalla navigazione delle Impostazioni.
struct LegalContentView: View {

    let document: LegalDocument

    var body: some View {
        ZStack {
            AeroTheme.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Text("Ultimo aggiornamento: novembre 2026")
                        .font(.caption)
                        .foregroundStyle(AeroTheme.textSecondary)
                    ForEach(document.sections) { section in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(section.heading)
                                .font(AeroTheme.title(17, weight: .semibold))
                                .foregroundStyle(AeroTheme.textPrimary)
                            Text(section.body)
                                .font(.footnote)
                                .foregroundStyle(AeroTheme.textSecondary)
                        }
                    }
                    Link(destination: document.onlineURL) {
                        Label("Versione online", systemImage: "safari")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(AeroTheme.accentCyan)
                    }
                }
                .padding(24)
            }
        }
    }
}
