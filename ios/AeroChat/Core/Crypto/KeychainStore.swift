import Foundation
import Security

/// Accesso minimale al Keychain per i segreti dell'app (chiavi private raw).
///
/// Tutti gli item sono salvati con `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: leggibili
/// in background dopo il primo sblocco (necessario per decifrare messaggi a schermo spento) e mai
/// migrati su un altro dispositivo tramite backup.
struct KeychainStore: Sendable {

    enum Failure: Error, Equatable {
        case unexpectedStatus(OSStatus)
        case unexpectedItemType
    }

    let service: String

    init(service: String = "com.aeromesh.identity") {
        self.service = service
    }

    private func baseQuery(account: String) -> [CFString: Any] {
        [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
        ]
    }

    /// Salva (o sovrascrive) `data` per `account`.
    func save(_ data: Data, account: String) throws {
        var attributes = baseQuery(account: account)
        attributes[kSecValueData] = data
        attributes[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        let status = SecItemAdd(attributes as CFDictionary, nil)
        switch status {
        case errSecSuccess:
            return
        case errSecDuplicateItem:
            let update: [CFString: Any] = [
                kSecValueData: data,
                kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ]
            let updateStatus = SecItemUpdate(baseQuery(account: account) as CFDictionary, update as CFDictionary)
            guard updateStatus == errSecSuccess else { throw Failure.unexpectedStatus(updateStatus) }
        default:
            throw Failure.unexpectedStatus(status)
        }
    }

    /// Restituisce i byte salvati per `account`, oppure `nil` se assenti.
    func load(account: String) throws -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData] = true
        query[kSecMatchLimit] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data else { throw Failure.unexpectedItemType }
            return data
        case errSecItemNotFound:
            return nil
        default:
            throw Failure.unexpectedStatus(status)
        }
    }

    /// Elimina l'item (nessun errore se non esiste).
    func delete(account: String) throws {
        let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw Failure.unexpectedStatus(status)
        }
    }
}
