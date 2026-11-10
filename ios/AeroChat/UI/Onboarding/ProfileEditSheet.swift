import SwiftUI
import PhotosUI

/// Modifica del profilo in qualsiasi momento: foto, nickname e colore vengono annunciati ai contatti.
struct ProfileEditSheet: View {

    var body: some View {
        NavigationStack {
            ProfileEditView()
        }
    }
}

/// Contenuto della modifica profilo, presentabile sia come foglio sia dentro la navigazione delle Impostazioni.
struct ProfileEditView: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss

    @State private var nickname = ""
    @State private var colorHex = OnboardingView.palette[0]
    @State private var avatarImage: Data?
    @State private var photoItem: PhotosPickerItem?
    @State private var showPhotoPicker = false
    @State private var isProcessingPhoto = false
    @State private var isSaving = false
    @State private var errorMessage: String?
    @State private var didLoad = false

    private var trimmed: String { nickname.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        ZStack {
            AeroTheme.background.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 28) {
                    avatarPicker
                    nicknameField
                    colorPicker
                    if let errorMessage {
                        Text(errorMessage).font(.footnote).foregroundStyle(.red)
                    }
                    Text("Foto, nickname e colore sono visibili ai tuoi compagni di volo e ai membri dei tuoi gruppi.")
                        .font(.caption)
                        .foregroundStyle(AeroTheme.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .padding(24)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .navigationTitle("Il tuo profilo")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(AeroTheme.background, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Annulla") { dismiss() }.toolbarLabel()
            }
            ToolbarItem(placement: .confirmationAction) {
                if isSaving {
                    ProgressView().tint(AeroTheme.accentCyan)
                } else {
                    Button("Salva") { Task { await save() } }
                        .toolbarLabel()
                        .disabled(trimmed.count < 2 || isProcessingPhoto)
                }
            }
        }
        .onAppear(perform: load)
        .onChange(of: photoItem) { _, item in
            if let item { processPhoto(item) }
        }
    }

    private var avatarPicker: some View {
        VStack(spacing: 12) {
            Button {
                showPhotoPicker = true
            } label: {
                ZStack(alignment: .bottomTrailing) {
                    AvatarView(imageData: avatarImage, colorHex: colorHex, size: 120)
                        .overlay {
                            if isProcessingPhoto {
                                Circle().fill(.black.opacity(0.5))
                                ProgressView().tint(.white).controlSize(.large)
                            }
                        }
                    Image(systemName: "camera.circle.fill")
                        .font(.system(size: 34))
                        .foregroundStyle(.black, AeroTheme.accentCyan)
                }
            }
            .disabled(isProcessingPhoto)
            .photosPicker(isPresented: $showPhotoPicker, selection: $photoItem, matching: .images, photoLibrary: .shared())
            .accessibilityLabel("Scegli foto profilo")

            if avatarImage != nil {
                Button(role: .destructive) {
                    avatarImage = nil
                    Haptics.tap()
                } label: {
                    Label("Rimuovi foto", systemImage: "trash")
                        .font(.footnote)
                }
                .tint(.red)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var nicknameField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("NICKNAME")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AeroTheme.textSecondary)
            TextField("Nickname", text: $nickname)
                .textInputAutocapitalization(.words)
                .padding(16)
                .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .foregroundStyle(AeroTheme.textPrimary)
                .font(AeroTheme.title(20, weight: .semibold))
                .onChange(of: nickname) { _, value in
                    if value.count > MeshProtocol.maxNicknameLength {
                        nickname = String(value.prefix(MeshProtocol.maxNicknameLength))
                    }
                }
        }
    }

    private var colorPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("COLORE AVATAR")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AeroTheme.textSecondary)
            HStack(spacing: 12) {
                ForEach(OnboardingView.palette, id: \.self) { hex in
                    Circle()
                        .fill(Color(hexString: hex) ?? AeroTheme.accentCyan)
                        .frame(width: 38, height: 38)
                        .overlay(Circle().strokeBorder(.white, lineWidth: colorHex == hex ? 3 : 0))
                        .onTapGesture { colorHex = hex; Haptics.impact() }
                        .accessibilityLabel("Colore avatar \(hex)")
                        .accessibilityAddTraits(colorHex == hex ? .isSelected : [])
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // Con la foto profilo il colore non si vede: scelta disattivata finche' la foto non viene rimossa.
        .disabled(avatarImage != nil)
        .opacity(avatarImage != nil ? 0.35 : 1)
        .animation(.easeOut(duration: 0.2), value: avatarImage != nil)
    }

    private func load() {
        guard !didLoad else { return }
        didLoad = true
        nickname = environment.nickname
        colorHex = environment.avatarColorHex
        avatarImage = environment.avatarImage
    }

    private func processPhoto(_ item: PhotosPickerItem) {
        photoItem = nil
        isProcessingPhoto = true
        errorMessage = nil
        Task {
            defer { isProcessingPhoto = false }
            do {
                guard let data = try await item.loadTransferable(type: Data.self) else { return }
                avatarImage = try await Task.detached(priority: .userInitiated) {
                    try ImageProcessor.prepareAvatar(imageData: data)
                }.value
                Haptics.impact(.light)
            } catch {
                errorMessage = String(localized: "Foto non utilizzabile: \(error.localizedDescription)")
                Haptics.error()
            }
        }
    }

    private func save() async {
        guard trimmed.count >= 2, !isSaving else { return }
        isSaving = true
        defer { isSaving = false }
        do {
            try await environment.updateProfile(nickname: trimmed, avatarColorHex: colorHex, avatarImage: avatarImage)
            Haptics.success()
            dismiss()
        } catch {
            errorMessage = String(localized: "Impossibile salvare il profilo: \(error.localizedDescription)")
            Haptics.error()
        }
    }
}
