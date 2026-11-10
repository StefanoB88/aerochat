# AeroChat – App Review notes (da incollare in App Store Connect › "Notes for Review")

> Copy for the reviewer (English). Keep it under the 4000-character limit of the field.

AeroChat is a peer-to-peer Bluetooth Low Energy mesh messenger for airplanes, cruises, festivals and
blackouts. There are **no servers and no accounts**: messages travel directly between nearby phones,
end-to-end encrypted (X25519 / Ed25519 / ChaCha20-Poly1305). The only network call is StoreKit.

## How to test with a single device

The app normally needs two phones in Bluetooth range. To review every screen with one device:

1. Launch the app, pick a nickname and tap "Sali a bordo" (Get on board).
2. Open **Settings** (avatar, top-left) › **Informazioni** › tap the **Version** row 5 times.
3. Enable **"Compagno di volo simulato (demo)"**.
4. A contact named **"Equipaggio Demo"** appears in the chat list and replies to every message you send
   (delivered → read → reply, ~2.5 s). It also posts two lines in the **Cabina** (Public Cabin) tab so
   the moderation tools can be exercised.

The demo companion lives only in the local database; it never uses the radio.

## Where to find the reviewed features

| Guideline | Where |
|---|---|
| 1.2 User-generated content – terms | First open of the **Cabina** tab shows the rules sheet; acceptance is mandatory. Full terms: Settings › Legale. |
| 1.2 – report & block | Long-press any received message (chat or Cabina) › **Segnala messaggio** / **Blocca**. Blocking is immediate; reports are stored locally and can be forwarded to safety@aeromesh.app. |
| 1.2 – filtering | Profanity is refused on send and masked on receive in the Public Cabin (Settings › Sicurezza toggle). |
| 1.2 – unsolicited images | Photos from contacts never verified in person stay blurred until tapped (Settings › Sicurezza). |
| 3.1.1 In-app purchases | Settings › Flight Group Pass, or the "Pass" button in the chat list. Three one-time products: 24 h, 72 h, lifetime. Terms, Privacy and Apple EULA links are on the paywall. |
| 3.1.1 – "1 pays, group uses" | A paying user shares an Ed25519-signed `PRO_PASS_GRANT` with group members for ≤72 h. The grant is a feature of the purchased product (described in the product text), not a transfer of the purchase. |
| 5.1.1 Privacy | No data collection, no tracking (PrivacyInfo.xcprivacy). Privacy policy: Settings › Legale › Informativa privacy (also offline). |
| Encryption export | Standard algorithms via Apple CryptoKit; `ITSAppUsesNonExemptEncryption = YES`. Self-classification report filed annually. |
| Location | Only when the user attaches it to an emergency broadcast (Settings › Avviso di emergenza). Never sent to a server. |
| Live Activity | Starts automatically when at least one phone is in range; cannot be shown with the demo companion. See video. |

## Features that need two devices (demo video attached)

Pairing by QR / invite link, Flight Cabin from boarding pass (IATA BCBP barcode), photos, voice notes,
calls, mini-games, cabin quiz, proximity radar, emergency broadcast, offline translation,
Pro Pass propagation to Android. The attached video shows two iPhones and one Android phone in
airplane mode exchanging all of the above.

## Background modes justification

- `bluetooth-central` / `bluetooth-peripheral`: receive messages and keep trusted companions connected while the phone is locked.
- `audio`: voice calls and voice-note playback.

## Test account

None required (no accounts exist).

---

## Checklist before submitting (internal)

- [ ] Bump `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`.
- [ ] Create products in App Store Connect: `com.aeromesh.pass24h` (consumable or non-renewing),
      `com.aeromesh.pass72h`, `com.aeromesh.lifetime` (non-consumable). Localize names/descriptions.
- [ ] Encryption: answer "Yes – standard algorithms" and upload the self-classification report (or set the
      compliance code in `ITSEncryptionExportComplianceCode`).
- [ ] Age rating: 12+ (infrequent/mild user-generated content; unrestricted web access = No).
- [ ] Host privacy policy and terms at `aeromesh.app/privacy` and `aeromesh.app/terms` (the in-app copies are
      in `UI/Settings/LegalView.swift`). Point `AppLinks` to the real domain if it changes.
- [ ] Verify the Apple Root CA G3 fingerprint pinned in `JWSTransactionVerifier`.
- [ ] Replace the personal-team bundle identifier (`com.stefanobrandipersonalteam.AeroChat`) with the
      company identifier in the app, the widget extension and the URL scheme name.
- [ ] Record the two-device demo video (airplane mode on, Bluetooth on).
- [ ] Screenshots: 6.9", 6.5", iPad 13" – 10 locales (see `fastlane/metadata`).
- [ ] Mirror the protocol additions on Android (see `../PROTOCOL_CHANGES_1_1.md`).
