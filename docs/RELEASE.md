# Direct Android release procedure

Phone Ledger is distributed by direct sideload, not Google Play. The stable signing key is owned and kept offline by the repository owner. It must never be committed, uploaded to CI, placed in a GitHub secret, or copied into a release archive.

## One-time key creation

On the offline signing system, create a long-lived APK signing key and make two independently protected offline backups. Record the certificate's SHA-256 fingerprint in the release notes. Loss of this key prevents in-place upgrades; compromise permits malicious upgrades.

Example using JDK `keytool` (choose the path, alias, passwords, and distinguished name interactively):

```sh
keytool -genkeypair -keystore phone-ledger-release.p12 -storetype PKCS12 \
  -alias phone-ledger -keyalg RSA -keysize 4096 -validity 9125
```

## Build, sign, and verify

1. From a clean tagged source tree, run unit tests and build the unsigned release with JDK 17:

   ```sh
   ./gradlew clean testReleaseUnitTest assembleRelease --no-daemon --no-parallel --max-workers=1
   ```

2. Transfer only `app/build/outputs/apk/release/app-release-unsigned.apk` and its SHA-256 digest to the offline signer.
3. Use the Android SDK's current `apksigner` to sign into a new output file. Do not overwrite the unsigned input:

   ```sh
   apksigner sign --ks phone-ledger-release.p12 --ks-key-alias phone-ledger \
     --out phone-ledger-v0.3.0.apk app-release-unsigned.apk
   apksigner verify --verbose --print-certs phone-ledger-v0.3.0.apk
   sha256sum phone-ledger-v0.3.0.apk
   ```

4. Transfer the signed APK and digest back. On an isolated test phone, verify install, unlock, Autofill, backup/restore, standalone sync, and an upgrade over the prior stable APK without uninstalling.
5. Attach the signed APK and checksum to the matching GitHub tag. Put the signing-certificate SHA-256 fingerprint in the release notes and compare it with the established fingerprint before publishing.

Debug-signed artifacts may be published for testing, but must use `-debug` in the filename and a GitHub prerelease. A debug build is not a stable release and cannot establish the stable upgrade-signing lineage.
