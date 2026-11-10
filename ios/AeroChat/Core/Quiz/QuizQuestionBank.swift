import Foundation

/// Domanda del quiz di cabina: testo, 4 opzioni e indice di quella giusta.
struct QuizQuestion: Sendable, Equatable {
    let text: String
    let options: [String]
    let correctIndex: Int
}

/// Banca domande integrata (viaggi, geografia, aviazione): il conduttore le invia nella propria lingua.
enum QuizQuestionBank {

    static var questions: [QuizQuestion] {
        [
            QuizQuestion(text: String(localized: "A quale altitudine di crociera vola di solito un aereo di linea?"),
                         options: [String(localized: "3.000 m"), String(localized: "6.000 m"), String(localized: "11.000 m"), String(localized: "20.000 m")], correctIndex: 2),
            QuizQuestion(text: String(localized: "Qual è l'aeroporto più trafficato d'Europa per passeggeri?"),
                         options: [String(localized: "Parigi CDG"), String(localized: "Londra Heathrow"), String(localized: "Amsterdam Schiphol"), String(localized: "Francoforte")], correctIndex: 1),
            QuizQuestion(text: String(localized: "Cosa indica il codice IATA “FCO”?"),
                         options: [String(localized: "Firenze"), String(localized: "Roma Fiumicino"), String(localized: "Fuerteventura"), String(localized: "Frankfurt")], correctIndex: 1),
            QuizQuestion(text: String(localized: "Qual è la capitale dell'Australia?"),
                         options: [String(localized: "Sydney"), String(localized: "Melbourne"), String(localized: "Canberra"), String(localized: "Perth")], correctIndex: 2),
            QuizQuestion(text: String(localized: "Quanti fusi orari attraversa la Russia?"),
                         options: ["5", "7", "9", "11"], correctIndex: 3),
            QuizQuestion(text: String(localized: "Qual è il volo di linea più lungo del mondo (per durata)?"),
                         options: [String(localized: "Singapore–New York"), String(localized: "Londra–Sydney"), String(localized: "Dubai–Los Angeles"), String(localized: "Doha–Auckland")], correctIndex: 0),
            QuizQuestion(text: String(localized: "Perché i finestrini degli aerei sono ovali?"),
                         options: [String(localized: "Per estetica"), String(localized: "Per distribuire la pressione"), String(localized: "Per pesare meno"), String(localized: "Per vedere meglio")], correctIndex: 1),
            QuizQuestion(text: String(localized: "Quale città è attraversata da due continenti?"),
                         options: [String(localized: "Il Cairo"), String(localized: "Istanbul"), String(localized: "Mosca"), String(localized: "Atene")], correctIndex: 1),
            QuizQuestion(text: String(localized: "Qual è l'oceano più grande?"),
                         options: [String(localized: "Atlantico"), String(localized: "Indiano"), String(localized: "Pacifico"), String(localized: "Artico")], correctIndex: 2),
            QuizQuestion(text: String(localized: "Quante lingue ufficiali ha la Svizzera?"),
                         options: ["2", "3", "4", "5"], correctIndex: 2),
            QuizQuestion(text: String(localized: "A che velocità decolla mediamente un aereo di linea?"),
                         options: [String(localized: "100 km/h"), String(localized: "180 km/h"), String(localized: "280 km/h"), String(localized: "450 km/h")], correctIndex: 2),
            QuizQuestion(text: String(localized: "Qual è il paese con più isole al mondo?"),
                         options: [String(localized: "Indonesia"), String(localized: "Filippine"), String(localized: "Svezia"), String(localized: "Canada")], correctIndex: 2),
            QuizQuestion(text: String(localized: "Che cosa significa la scatola nera di un aereo?"),
                         options: [String(localized: "È nera"), String(localized: "Registra dati e voci"), String(localized: "Contiene il carburante"), String(localized: "Guida l'autopilota")], correctIndex: 1),
            QuizQuestion(text: String(localized: "Qual è la montagna più alta d'Europa?"),
                         options: [String(localized: "Monte Bianco"), String(localized: "Elbrus"), String(localized: "Cervino"), String(localized: "Monte Rosa")], correctIndex: 1),
            QuizQuestion(text: String(localized: "In quale città si trova l'aeroporto “Marco Polo”?"),
                         options: [String(localized: "Venezia"), String(localized: "Genova"), String(localized: "Trieste"), String(localized: "Verona")], correctIndex: 0),
            QuizQuestion(text: String(localized: "Qual è la valuta del Giappone?"),
                         options: [String(localized: "Won"), String(localized: "Yuan"), String(localized: "Yen"), String(localized: "Baht")], correctIndex: 2),
            QuizQuestion(text: String(localized: "Quanto dura circa il giro del mondo di un aereo di linea senza scali (ipotetico)?"),
                         options: [String(localized: "12 ore"), String(localized: "24 ore"), String(localized: "45 ore"), String(localized: "3 giorni")], correctIndex: 2),
            QuizQuestion(text: String(localized: "Qual è il fiume più lungo del mondo?"),
                         options: [String(localized: "Nilo"), String(localized: "Rio delle Amazzoni"), String(localized: "Yangtze"), String(localized: "Mississippi")], correctIndex: 0),
            QuizQuestion(text: String(localized: "Che cosa misura l'altimetro di un aereo?"),
                         options: [String(localized: "La temperatura"), String(localized: "La pressione atmosferica"), String(localized: "La velocità del vento"), String(localized: "L'umidità")], correctIndex: 1),
            QuizQuestion(text: String(localized: "Qual è il deserto più grande del mondo (non polare)?"),
                         options: [String(localized: "Gobi"), String(localized: "Sahara"), String(localized: "Kalahari"), String(localized: "Atacama")], correctIndex: 1),
            QuizQuestion(text: String(localized: "Qual è il paese più piccolo del mondo?"),
                         options: [String(localized: "Monaco"), String(localized: "San Marino"), String(localized: "Città del Vaticano"), String(localized: "Liechtenstein")], correctIndex: 2),
            QuizQuestion(text: String(localized: "Perché le luci della cabina si abbassano al decollo e all'atterraggio?"),
                         options: [String(localized: "Per risparmiare energia"), String(localized: "Per abituare gli occhi al buio in caso di evacuazione"), String(localized: "Per far dormire i passeggeri"), String(localized: "Per legge europea")], correctIndex: 1),
            QuizQuestion(text: String(localized: "Qual è la città più popolosa del mondo?"),
                         options: [String(localized: "Shanghai"), String(localized: "Tokyo"), String(localized: "Delhi"), String(localized: "San Paolo")], correctIndex: 1),
            QuizQuestion(text: String(localized: "Quante ruote ha in genere il carrello di un Boeing 747?"),
                         options: ["6", "10", "18", "24"], correctIndex: 2),
        ]
    }
}
