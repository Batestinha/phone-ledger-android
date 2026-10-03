# Phone Ledger for Android

Phone Ledger stores a person's own phone numbers, offers them only to detected phone-number fields through Android Autofill, and keeps a durable ledger of where each number was disclosed. Manual entries cover people, organizations, places, apps, and other disclosures that have no URI.

It is local-first and distributed directly as a sideloaded APK; there is no Google Play dependency. Optional multi-device synchronization uses the separate [Phone Ledger Server](https://github.com/Batestinha/phone-ledger-server).

## Privacy model

- The local vault is encrypted with AES-256-GCM. Its key is derived from the master password with PBKDF2-HMAC-SHA256 (310,000 rounds).
- Optional authentication-per-use biometric unlock uses Android Keystore. An optional six-digit PIN wraps the vault key locally and locks for five minutes after five failures.
- No contacts, phone-state, call-log, SMS, accessibility, advertising, or analytics permission is requested. Camera access is requested only when the user opens the bundled recovery-QR scanner.
- Network access is used only after self-hosted sync is configured. The standalone server receives encrypted blobs, never plaintext phone numbers or disclosure records.
- Web origins reported by browsers are stored as `UNVERIFIED` with the browser package and signing-certificate digest. Android exposes an origin rather than the full browser path; a full URL can be added manually.
- An automatic event means the user selected a number for autofill. It does not claim the form was submitted.

## Build and test

Use JDK 17, the Android SDK declared by the project, and the checked-in Gradle wrapper. Host-side Gradle concurrency is intentionally limited in `gradle.properties`.

```sh
./gradlew testDebugUnitTest assembleDebug --no-daemon --no-parallel --max-workers=1
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. Published test artifacts are attached to GitHub releases and are not committed to the repository.

The `fixture` module is a separate-package phone form used only to verify Autofill on physical devices. It requests no permissions and contains no production code.

## Standalone self-hosted sync

In **Settings & data → Configure self-hosted sync**, either:

- create an account with the server's short-lived, one-use invite; or
- connect an existing account with its 32-character account ID and 24 recovery words, typed manually or scanned from a trusted enrolled device.

There is no server password. The client generates a random 256-bit recovery root, encodes it as a checksummed BIP-39 phrase, and derives domain-separated payload and enrollment keys with HKDF-SHA256. Each phone has an independent Ed25519 device key. The ledger is encrypted client-side with AES-256-GCM, bound to the server instance and account IDs, and read back after upload to verify server retention.

Entity-specific logical mutation stamps—not wall clocks—drive deterministic conflict merging. Tombstones propagate deletion. If a restored server has a lower revision than an enrolled phone has observed, the phone repairs the rollback from its local encrypted state.

Recovery-key rotation is crash- and timeout-tolerant: the pending new key is encrypted into the local vault before the server request. On retry, the client determines whether the server committed by authenticating the remote blob with the pending and prior keys. A successful rotation revokes other devices; enroll them again with the new recovery kit.

Recovery words and recovery QR codes are bearer secrets. Store the written kit offline. The app prevents screenshots while displaying it and never puts it on the clipboard, but a compromised unlocked phone can still expose it.

## Migrating the v0.2 AliasVault alpha

Version 0.3 recognizes the old local AliasVault sync configuration. The migration flow can make one final authenticated fetch and merge, after which you create or join a standalone Phone Ledger server account. Once the new encrypted vault has been uploaded and read back successfully, the old credentials are removed from the local vault. The old remote AliasVault blob is intentionally not deleted; remove it after validating all devices and backups.

If the old refresh session has already expired or been revoked, use the current local data or reinstall v0.2 long enough to reauthenticate and sync before upgrading. The standalone service does not accept AliasVault credentials.

## Data portability

Phone numbers import and export as CSV. Disclosure events export as CSV. These files are plaintext and should be treated as sensitive. The `.phoneledger` backup is encrypted and intentionally excludes sync credentials and recovery material so restoring it cannot clone a live device session.

## Direct release signing

Stable APKs are signed with an owner-held offline key, outside CI. See [docs/RELEASE.md](docs/RELEASE.md). Debug and release-candidate artifacts are clearly named and must not be confused with a stable, owner-signed build.

## License and provenance

This project is based on [AliasVault 0.30.7 at commit `7a1ffeb94`](https://github.com/aliasvault/aliasvault/commit/7a1ffeb94f71645ce36f2cc5e1344f7cdb15d61c) and remains under the GNU Affero General Public License v3.0. Phone Ledger is a new name and Android application ID; it is not an official AliasVault product.

The Android client and standalone server intentionally live in separate repositories. Third-party components and the BIP-39 word-list source are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
