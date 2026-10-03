# Phone Ledger for Android

This is the focused Android application developed from the AliasVault 0.30.7 fork. It stores a person's own phone numbers, offers them only to detected phone-number fields through Android Autofill, and records a durable disclosure event before a selected number is returned to the requesting form.

> **Alpha status:** v0.2.0 is intended for controlled sideload testing. The downloadable APK is debug-signed and the self-hosted sync path still requires live two-device validation against a deployed server before a production release.

## Privacy model

- Local encrypted vault; no account is required.
- AES-256-GCM with a key derived from the master password using PBKDF2-HMAC-SHA256 (310,000 iterations).
- Optional authentication-per-use biometric unlock backed by Android Keystore, plus an optional rate-limited six-digit PIN.
- No contacts, phone-state, call-log, SMS, or accessibility permission. Network access is used only after self-hosted sync is configured.
- Web origins reported by browsers are accepted as requested, but stored as `UNVERIFIED` together with the browser package and signing-certificate digest.
- Android exposes an origin, not the full browser path. Automatic records therefore contain the reported origin. A full URL can be stored on manual events.
- An automatic event means the user selected a number for autofill; it does not claim the form was submitted.

## Build

Use the checked-in Gradle wrapper. The build is intentionally limited to one worker by `gradle.properties`:

```sh
./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=1
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. Published test artifacts are attached to GitHub releases rather than committed to the repository.

The `fixture` module is a separate-package phone form used only to verify Autofill on physical devices. It requests no permissions and contains no production code.

## Self-hosted sync

Version 0.2 can synchronize through the API included with an AliasVault 0.30.7 self-hosted installation. In **Settings & data → Configure self-hosted sync**, either create a dedicated account or connect an existing dedicated account. Enter the API root such as `https://vault.example.org/api`, without the trailing `/v1`.

The account must be dedicated to Phone Ledger. AliasVault's API exposes one opaque vault slot per account, so using the same account in both applications would make the clients compete for that slot. If server-side public registration is disabled, temporarily enable `PUBLIC_REGISTRATION_ENABLED` to create the account and disable it again afterward, or connect an account created while registration was enabled.

Security and conflict behavior:

- Authentication uses AliasVault's Argon2id + 2048-bit SRP flow and verifies the server's SRP proof. The plaintext password is never sent to the server. Existing accounts with TOTP can provide their six-digit code while connecting.
- The ledger payload is separately encrypted with AES-256-GCM using a domain-separated key derived from the sync password. The server stores only the opaque encrypted blob.
- Server URL, refresh/access tokens, and the payload key are held inside the local encrypted vault. They are excluded from sync payloads and encrypted backup exports.
- Only HTTPS URLs are accepted. Android's normal certificate trust validation applies; cleartext HTTP is disabled.
- Entity IDs, update timestamps, and tombstones provide deterministic last-write-wins merging. Revision preconditions cause a fetch/merge/retry when two devices upload concurrently.
- Sync runs while the local vault is unlocked: after local mutations, when the app resumes, or when **Sync now** is pressed. Local edits remain available offline and are retried on a later trigger.
- The server can observe the account name, ciphertext size, request timing, and IP address according to its configuration. Phone Ledger always reports zero AliasVault credentials and sends no phone-number metadata outside the encrypted payload.

## CSV import

The header is `label,number,region,favorite,notes`. `number` is required. `region` is required when the number does not begin with `+`. Duplicate canonical E.164 numbers are skipped and reported.

## License and provenance

This work is based on [AliasVault 0.30.7 at commit `7a1ffeb94`](https://github.com/aliasvault/aliasvault/commit/7a1ffeb94f71645ce36f2cc5e1344f7cdb15d61c) and remains under the GNU Affero General Public License v3.0. Phone Ledger is a new name and Android application ID; it is not presented as an official AliasVault product.

The Android client and server are intentionally separate projects. This repository contains only the client. It currently uses an unmodified AliasVault 0.30.7 self-hosted API; a separate Phone Ledger server repository should be created if and when server-specific endpoints or deployment changes are introduced.

## Milestone status

Version 0.2 adds optional self-hosted multi-device synchronization while preserving the fully local mode as the default. It deliberately reuses the reviewed AliasVault server authentication and opaque-vault APIs instead of introducing a second password protocol or server database.
