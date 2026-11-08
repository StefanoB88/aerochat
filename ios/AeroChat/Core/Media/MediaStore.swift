import Foundation

/// Posizione su disco dei media ricevuti/inviati (`Application Support/AeroMesh/Media`).
struct MediaStore: Sendable {

    enum Kind: String, Sendable {
        case image
        case audio

        var fileExtension: String {
            switch self {
            case .image: return "webp"
            case .audio: return "opus"
            }
        }

        var mimeType: String {
            switch self {
            case .image: return MediaStore.webpMimeType
            case .audio: return MediaStore.opusMimeType
            }
        }

        init?(mimeType: String) {
            switch mimeType.lowercased() {
            case MediaStore.webpMimeType: self = .image
            case MediaStore.opusMimeType, "audio/ogg", "audio/ogg; codecs=opus": self = .audio
            default: return nil
            }
        }
    }

    static let webpMimeType = "image/webp"
    static let opusMimeType = "audio/opus"

    let directory: URL

    init(directory: URL) {
        self.directory = directory
    }

    static func makeShared() throws -> MediaStore {
        let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let directory = support.appendingPathComponent("AeroMesh/Media", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return MediaStore(directory: directory)
    }

    static func makeTemporary() throws -> MediaStore {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("AeroMeshMedia-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return MediaStore(directory: directory)
    }

    func url(mediaID: String, kind: Kind) -> URL {
        directory.appendingPathComponent("\(Self.sanitize(mediaID)).\(kind.fileExtension)")
    }

    /// Scrive atomicamente e ritorna il path relativo alla directory media (salvato in `media_local_path`).
    func write(_ data: Data, mediaID: String, kind: Kind) throws -> String {
        let target = url(mediaID: mediaID, kind: kind)
        try data.write(to: target, options: .atomic)
        return target.lastPathComponent
    }

    func read(relativePath: String) throws -> Data {
        try Data(contentsOf: directory.appendingPathComponent(relativePath))
    }

    func absoluteURL(relativePath: String) -> URL {
        directory.appendingPathComponent(relativePath)
    }

    func exists(relativePath: String) -> Bool {
        FileManager.default.fileExists(atPath: absoluteURL(relativePath: relativePath).path)
    }

    private static func sanitize(_ mediaID: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        return String(mediaID.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" })
    }
}
