import Foundation
import Photos
import UIKit

/// Salva una foto della chat (WebP) nel rullino. Richiede solo il permesso "aggiungi" (`.addOnly`).
enum PhotoLibrarySaver {

    enum Failure: Error, Equatable {
        case permissionDenied
        case unreadableImage
    }

    static func save(imageData: Data) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw Failure.permissionDenied }
        // Il WebP viene convertito in JPEG: Foto e le app di terze parti lo gestiscono ovunque.
        guard let image = AvatarView.decode(imageData), let jpeg = image.jpegData(compressionQuality: 0.92) else {
            throw Failure.unreadableImage
        }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetCreationRequest.forAsset().addResource(with: .photo, data: jpeg, options: nil)
        }
    }
}
