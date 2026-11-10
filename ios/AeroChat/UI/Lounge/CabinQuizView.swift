import SwiftUI

/// Quiz di cabina: domande a tutta la Cabina con classifica live. Chi apre il foglio senza quiz in corso
/// puo' condurne uno.
struct CabinQuizSheet: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var errorMessage: String?

    private var state: QuizService.State? { environment.quizState }

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                if let state {
                    activeQuiz(state)
                } else {
                    lobby
                }
            }
            .navigationTitle("Quiz di cabina")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() }.toolbarLabel() }
                if state != nil {
                    ToolbarItem(placement: .destructiveAction) {
                        Button("Esci") {
                            Haptics.tap()
                            Task { await environment.quiz?.leave() }
                        }
                        .foregroundStyle(.red)
                        .toolbarLabel()
                    }
                }
            }
            .overlay(alignment: .bottom) {
                if let errorMessage {
                    Text(errorMessage)
                        .font(.footnote)
                        .padding(12)
                        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .padding()
                        .task {
                            try? await Task.sleep(for: .seconds(3))
                            self.errorMessage = nil
                        }
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var lobby: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "questionmark.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(AeroTheme.accentCyan)
            Text("Sfida tutta la cabina")
                .font(AeroTheme.title(26))
                .foregroundStyle(AeroTheme.textPrimary)
            Text("5 domande su viaggi, geografia e aviazione. Chi è a portata di Bluetooth vede la domanda e risponde: vince chi è veloce e preciso.")
                .font(.footnote)
                .foregroundStyle(AeroTheme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
            Button {
                Haptics.impact()
                Task {
                    do { try await environment.quiz?.host() } catch { errorMessage = String(localized: "C'è già un quiz in corso.") }
                }
            } label: {
                Label("Conduci un quiz", systemImage: "megaphone.fill")
                    .font(AeroTheme.title(18, weight: .bold))
                    .frame(maxWidth: .infinity, minHeight: 28)
                    .padding(.vertical, 14)
            }
            .buttonStyle(.borderedProminent)
            .tint(AeroTheme.accentCyan)
            .foregroundStyle(.black)
            .padding(.horizontal, 24)
            Text("\(environment.neighbors.count) passeggeri a portata adesso")
                .font(.caption)
                .foregroundStyle(AeroTheme.textSecondary)
                .padding(.bottom, 16)
        }
    }

    @ViewBuilder
    private func activeQuiz(_ state: QuizService.State) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header(state)
                switch state.phase {
                case .waiting:
                    ProgressView("Preparazione della prima domanda…")
                        .tint(AeroTheme.accentCyan)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 40)
                case .question, .reveal:
                    questionCard(state)
                    if state.phase == .reveal || state.isHost {
                        leaderboard(state)
                    }
                case .ended:
                    podium(state)
                }
            }
            .padding(20)
        }
    }

    private func header(_ state: QuizService.State) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(state.isHost ? "Conduci tu" : "Conduce \(state.hostNickname)")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AeroTheme.accentEmerald)
                if state.round > 0 {
                    Text("Domanda \(state.round) di \(state.totalRounds)")
                        .font(AeroTheme.title(20, weight: .bold))
                        .foregroundStyle(AeroTheme.textPrimary)
                }
            }
            Spacer()
            if state.phase == .question {
                CountdownRing(deadline: Date(unixMilliseconds: state.deadlineMs), duration: QuizService.questionDuration)
            }
        }
    }

    private func questionCard(_ state: QuizService.State) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(state.question ?? "")
                .font(AeroTheme.title(22, weight: .semibold))
                .foregroundStyle(AeroTheme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(Array(state.options.enumerated()), id: \.offset) { index, option in
                Button {
                    Haptics.tap()
                    Task {
                        do { try await environment.quiz?.answer(index) } catch { errorMessage = String(localized: "Tempo scaduto per questa domanda.") }
                    }
                } label: {
                    HStack {
                        Text(Self.letter(index))
                            .font(.headline.weight(.bold))
                            .frame(width: 28, height: 28)
                            .background(Circle().fill(.white.opacity(0.1)))
                        Text(option)
                            .font(.body.weight(.medium))
                            .multilineTextAlignment(.leading)
                        Spacer()
                        if state.correctIndex == index {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(AeroTheme.accentEmerald)
                        } else if state.myAnswer == index, state.phase == .reveal {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
                        } else if state.myAnswer == index {
                            Image(systemName: "checkmark").foregroundStyle(AeroTheme.accentCyan)
                        }
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity)
                    .background(Self.optionBackground(index: index, state: state), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)
                .foregroundStyle(AeroTheme.textPrimary)
                .disabled(state.phase != .question || state.myAnswer != nil)
            }
            if state.isHost, state.phase == .question {
                Text("\(state.answerCount) risposte ricevute")
                    .font(.caption)
                    .foregroundStyle(AeroTheme.textSecondary)
            }
            if state.phase == .question, state.myAnswer != nil {
                Text("Risposta inviata: aspetta la rivelazione.")
                    .font(.caption)
                    .foregroundStyle(AeroTheme.textSecondary)
            }
        }
    }

    private static func optionBackground(index: Int, state: QuizService.State) -> Color {
        if state.correctIndex == index { return AeroTheme.accentEmerald.opacity(0.25) }
        if state.myAnswer == index { return AeroTheme.accentCyan.opacity(0.2) }
        return AeroTheme.surface
    }

    private static func letter(_ index: Int) -> String {
        ["A", "B", "C", "D"][min(index, 3)]
    }

    private func leaderboard(_ state: QuizService.State) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("CLASSIFICA")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AeroTheme.textSecondary)
            if state.scores.isEmpty {
                Text("Nessuna risposta giusta per ora.")
                    .font(.footnote)
                    .foregroundStyle(AeroTheme.textSecondary)
            }
            ForEach(Array(state.scores.prefix(10).enumerated()), id: \.element.id) { position, entry in
                HStack {
                    Text("\(position + 1).")
                        .font(.body.monospacedDigit().weight(.semibold))
                        .foregroundStyle(AeroTheme.textSecondary)
                        .frame(width: 28, alignment: .leading)
                    Text(entry.nickname)
                        .foregroundStyle(entry.nodeID == environment.identityKeys?.nodeID ? AeroTheme.accentCyan : AeroTheme.textPrimary)
                    Spacer()
                    Text("\(entry.points)")
                        .font(.body.monospacedDigit().weight(.bold))
                        .foregroundStyle(AeroTheme.textPrimary)
                }
                .padding(.vertical, 4)
            }
        }
        .padding(16)
        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: AeroTheme.cornerRadius, style: .continuous))
    }

    private func podium(_ state: QuizService.State) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "trophy.fill")
                .font(.system(size: 56))
                .foregroundStyle(.yellow)
            Text("Quiz finito!")
                .font(AeroTheme.title(26))
                .foregroundStyle(AeroTheme.textPrimary)
            if let winner = state.scores.first {
                Text("Vince \(winner.nickname) con \(winner.points) punti")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AeroTheme.accentEmerald)
            }
            leaderboard(state)
            Button {
                Haptics.impact()
                Task {
                    await environment.quiz?.leave()
                    do { try await environment.quiz?.host() } catch { errorMessage = String(localized: "C'è già un quiz in corso.") }
                }
            } label: {
                Label("Nuovo quiz", systemImage: "arrow.clockwise")
                    .font(AeroTheme.title(18, weight: .bold))
                    .frame(maxWidth: .infinity, minHeight: 28)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .tint(AeroTheme.accentCyan)
            .foregroundStyle(.black)
        }
        .frame(maxWidth: .infinity)
    }
}

/// Anello del tempo rimanente per rispondere.
private struct CountdownRing: View {

    let deadline: Date
    let duration: TimeInterval

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { context in
            let remaining = max(0, deadline.timeIntervalSince(context.date))
            ZStack {
                Circle().stroke(.white.opacity(0.15), lineWidth: 5)
                Circle()
                    .trim(from: 0, to: min(1, remaining / duration))
                    .stroke(remaining < 5 ? Color.red : AeroTheme.accentCyan, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text("\(Int(remaining.rounded(.up)))")
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(AeroTheme.textPrimary)
            }
            .frame(width: 52, height: 52)
            .accessibilityLabel(Text("\(Int(remaining)) secondi rimasti"))
        }
    }
}
