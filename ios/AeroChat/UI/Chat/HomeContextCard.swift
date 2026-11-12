import SwiftUI

/// Stato in parole semplici: "3 persone vicine" invece degli stati tecnici della radio.
enum PeopleStatus {
    static func text(neighbors: Int, nearby: Int, radioState: RadioState) -> String {
        switch radioState {
        case .poweredOff: return String(localized: "Bluetooth spento")
        case .unauthorized, .unsupported: return String(localized: "Bluetooth non disponibile")
        default: break
        }
        let count = max(neighbors, nearby)
        return count == 0 ? String(localized: "Nessuno vicino per ora") : String(localized: "\(count) persone vicine")
    }
}

/// Sottotitolo della barra di navigazione (solo iOS 26; prima la card della schermata principale basta).
struct PeopleSubtitle: ViewModifier {
    let text: String

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.navigationSubtitle(text)
        } else {
            content
        }
    }
}

/// Card contestuale in cima alla lista chat: in volo porta alla Cabina del volo e ai giochi con chi e' a
/// bordo, a terra invita amici e crea gruppi; mostra quante persone sono vicine.
struct HomeContextCard: View {

    let isInFlight: Bool
    /// Modalita' viaggio scelta (vale solo a terra).
    @Binding var travelMode: TravelMode
    let flightNumber: String?
    let hasFlightCabin: Bool
    let nearbyCount: Int
    let onOpenFlightCabin: () -> Void
    let onPlayOnBoard: () -> Void
    let onAddBoardingPass: () -> Void
    let onInvite: () -> Void
    let onNewGroup: () -> Void
    let onNearby: () -> Void
    let onRadar: () -> Void
    let onShareLocation: () -> Void
    let onTranslationHelp: () -> Void
    let onDiagnosis: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                Image(systemName: isInFlight ? "airplane" : (travelMode == .flight ? "person.2.wave.2.fill" : travelMode.systemImage))
                    .font(.title)
                    .foregroundStyle(AeroTheme.accentCyan)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(AeroTheme.title(20, weight: .bold))
                        .foregroundStyle(AeroTheme.textPrimary)
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(AeroTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Button {
                    Haptics.tap()
                    onDismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(AeroTheme.textSecondary)
                        .padding(6)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Nascondi")
            }
            if !isInFlight {
                Picker("Modalità viaggio", selection: $travelMode) {
                    ForEach(TravelMode.allCases) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                .onChange(of: travelMode) { _, _ in Haptics.tap() }
            }
            if isInFlight {
                if hasFlightCabin {
                    action("Entra nella Cabina del volo", systemImage: "airplane.departure", prominent: true, perform: onOpenFlightCabin)
                    action("Gioca con chi è a bordo", systemImage: "suit.spade.fill", prominent: false, perform: onPlayOnBoard)
                } else {
                    action("Aggiungi la carta d'imbarco", systemImage: "barcode.viewfinder", prominent: true, perform: onAddBoardingPass)
                    action("Gioca con chi è a bordo", systemImage: "suit.spade.fill", prominent: false, perform: onPlayOnBoard)
                }
            } else {
                switch travelMode {
                case .flight:
                    action("Invita amici", systemImage: "qrcode", prominent: true, perform: onInvite)
                    action("Crea un gruppo di viaggio", systemImage: "person.3.fill", prominent: false, perform: onNewGroup)
                case .ship:
                    action("Crea il gruppo della crociera", systemImage: "person.3.fill", prominent: true, perform: onNewGroup)
                    action("Persone vicine", systemImage: "person.wave.2.fill", prominent: false, perform: onNearby)
                case .festival:
                    action("Crea il gruppo degli amici", systemImage: "person.3.fill", prominent: true, perform: onNewGroup)
                    action("Radar: ritrova gli amici", systemImage: "dot.radiowaves.left.and.right", prominent: false, perform: onRadar)
                case .mountain:
                    action("Crea il gruppo dell'escursione", systemImage: "person.3.fill", prominent: true, perform: onNewGroup)
                    action("Condividi la tua posizione", systemImage: "location.fill", prominent: false, perform: onShareLocation)
                case .abroad:
                    action("Persone vicine", systemImage: "person.wave.2.fill", prominent: true, perform: onNearby)
                    action("Traduzione offline", systemImage: "character.bubble", prominent: false, perform: onTranslationHelp)
                }
            }
            if nearbyCount > 0, !(isInFlight && hasFlightCabin) {
                Button {
                    Haptics.tap()
                    onNearby()
                } label: {
                    HStack {
                        Image(systemName: "person.wave.2.fill")
                        Text("\(nearbyCount) persone vicine")
                            .font(.subheadline.weight(.semibold))
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(AeroTheme.accentEmerald)
                }
                .buttonStyle(.plain)
            } else if nearbyCount == 0 {
                // "Nessuno vicino per ora": la diagnosi spiega cosa controllare.
                Button {
                    Haptics.tap()
                    onDiagnosis()
                } label: {
                    HStack {
                        Image(systemName: "questionmark.circle")
                        Text("Perché non vedo nessuno?")
                            .font(.subheadline.weight(.semibold))
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(AeroTheme.textSecondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(16)
        .background(
            LinearGradient(colors: [AeroTheme.accentCyan.opacity(0.18), AeroTheme.surface],
                           startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(AeroTheme.accentCyan.opacity(0.3)))
    }

    private var title: String {
        if isInFlight {
            if let flightNumber { return String(localized: "Sei sul volo \(flightNumber)") }
            return String(localized: "Sei in volo?")
        }
        switch travelMode {
        case .flight: return String(localized: "Porta i tuoi amici a bordo")
        case .ship: return String(localized: "In crociera senza rete")
        case .festival: return String(localized: "Al festival senza campo")
        case .mountain: return String(localized: "In montagna senza copertura")
        case .abroad: return String(localized: "All'estero senza roaming")
        }
    }

    private var subtitle: String {
        if isInFlight {
            if hasFlightCabin {
                return nearbyCount > 0
                    ? String(localized: "\(nearbyCount) persone vicine. Scrivi a tutto l'aereo o sfida qualcuno a carte.")
                    : String(localized: "Scrivi a tutto l'aereo o sfida qualcuno a carte, anche senza Wi-Fi.")
            }
            return String(localized: "Inquadra la carta d'imbarco per entrare nella chat del tuo volo.")
        }
        switch travelMode {
        case .flight: return String(localized: "Aggiungili ora con il QR: in volo vi troverete da soli via Bluetooth.")
        case .ship: return String(localized: "Chatta con chi è a bordo anche in mare aperto, senza roaming né Wi-Fi a pagamento.")
        case .festival: return String(localized: "Quando la rete crolla tra la folla, i messaggi passano da telefono a telefono.")
        case .mountain: return String(localized: "Resta in contatto con il gruppo dell'escursione anche dove il telefono non prende.")
        case .abroad: return String(localized: "Scrivi a chi è vicino senza dati mobili e traduci i messaggi senza internet.")
        }
    }

    private func action(_ title: LocalizedStringKey, systemImage: String, prominent: Bool, perform: @escaping () -> Void) -> some View {
        Button {
            Haptics.tap()
            perform()
        } label: {
            Label(title, systemImage: systemImage)
                .font(AeroTheme.title(16, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 24)
                .padding(.vertical, 6)
        }
        .buttonStyle(prominent ? AnyPrimitiveButtonStyle(.borderedProminent) : AnyPrimitiveButtonStyle(.bordered))
        .tint(AeroTheme.accentCyan)
        .foregroundStyle(prominent ? Color.black : AeroTheme.accentCyan)
    }
}

/// Stile di pulsante scelto a runtime (pieno o bordato).
struct AnyPrimitiveButtonStyle: PrimitiveButtonStyle {
    private let make: (Configuration) -> AnyView

    init<S: PrimitiveButtonStyle>(_ style: S) {
        make = { AnyView(style.makeBody(configuration: $0)) }
    }

    func makeBody(configuration: Configuration) -> some View {
        make(configuration)
    }
}
