import SwiftUI
import UIKit
import AVFoundation

/// Fotocamera di sistema per scattare una foto da inviare in chat.
///
/// `UIImagePickerController` restituisce un `UIImage` con l'orientamento del sensore: lo
/// ricomprimiamo in JPEG (l'orientamento finisce nell'EXIF) cosi' la foto segue lo stesso percorso
/// delle immagini della libreria (`ImageProcessor.prepareForMesh(imageData:)`, che applica l'EXIF).
struct CameraPicker: UIViewControllerRepresentable {

    /// Chiamato con i dati JPEG dello scatto; `nil` se l'utente annulla.
    let onFinish: (Data?) -> Void

    /// `false` sul simulatore o su dispositivi senza fotocamera posteriore/anteriore.
    static var isAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.allowsEditing = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let onFinish: (Data?) -> Void

        init(onFinish: @escaping (Data?) -> Void) {
            self.onFinish = onFinish
        }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            // Qualita' alta: la compressione vera (WebP 50-80 KB) la fa ImageProcessor.
            let data = (info[.originalImage] as? UIImage)?.jpegData(compressionQuality: 0.95)
            onFinish(data)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onFinish(nil)
        }
    }
}
