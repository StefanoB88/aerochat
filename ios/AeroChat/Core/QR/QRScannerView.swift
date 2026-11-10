import SwiftUI
@preconcurrency import AVFoundation
import UIKit

/// Scanner QR basato su `AVCaptureMetadataOutput`, incapsulato per SwiftUI.
/// Chiama `onCode` una sola volta per ogni codice `aeromesh://` valido inquadrato.
struct QRScannerView: UIViewControllerRepresentable {

    let onCode: @MainActor (String) -> Void
    var onError: (@MainActor (QRScannerError) -> Void)?
    /// Simbologie riconosciute: QR per il pairing; PDF417/Aztec per le carte d'imbarco (IATA BCBP).
    var metadataTypes: [AVMetadataObject.ObjectType] = [.qr]

    func makeUIViewController(context: Context) -> QRScannerViewController {
        let controller = QRScannerViewController()
        controller.onCode = onCode
        controller.onError = onError
        controller.metadataTypes = metadataTypes
        return controller
    }

    func updateUIViewController(_ uiViewController: QRScannerViewController, context: Context) {}
}

enum QRScannerError: Error, Equatable {
    case cameraUnavailable
    case permissionDenied
}

final class QRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    var onCode: (@MainActor (String) -> Void)?
    var onError: (@MainActor (QRScannerError) -> Void)?
    var metadataTypes: [AVMetadataObject.ObjectType] = [.qr]

    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "AeroMesh.QRScanner")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var lastCode: String?
    private var lastCodeAt = Date.distantPast

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        requestAccessAndConfigure()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private func requestAccessAndConfigure() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if granted { self.configureSession() } else { self.onError?(.permissionDenied) }
                }
            }
        default:
            onError?(.permissionDenied)
        }
    }

    private func configureSession() {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device) else {
            onError?(.cameraUnavailable)
            return
        }
        let output = AVCaptureMetadataOutput()
        session.beginConfiguration()
        guard session.canAddInput(input), session.canAddOutput(output) else {
            session.commitConfiguration()
            onError?(.cameraUnavailable)
            return
        }
        session.addInput(input)
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = metadataTypes.filter { output.availableMetadataObjectTypes.contains($0) }
        session.commitConfiguration()

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.insertSublayer(layer, at: 0)
        previewLayer = layer

        sessionQueue.async { [session] in
            session.startRunning()
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard let object = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first,
              metadataTypes.contains(object.type),
              let value = object.stringValue else { return }
        // Debounce: lo stesso codice non viene riconsegnato per 2 secondi.
        if value == lastCode, Date().timeIntervalSince(lastCodeAt) < 2 { return }
        lastCode = value
        lastCodeAt = Date()
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        onCode?(value)
    }
}
