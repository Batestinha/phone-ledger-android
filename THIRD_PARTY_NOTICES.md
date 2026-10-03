# Third-party notices

Phone Ledger depends on these unmodified components:

- Google libphonenumber (`com.googlecode.libphonenumber:libphonenumber`), Apache License 2.0.
- Bouncy Castle (`org.bouncycastle:bcprov-jdk18on`), MIT-style Bouncy Castle license.
- ZXing Core (`com.google.zxing:core`), Apache License 2.0.
- ZXing Android Embedded (`com.journeyapps:zxing-android-embedded`), Apache License 2.0.

`app/src/main/resources/bip39_english.txt` is the canonical English word list from Bitcoin BIP-39. BIP-39 is published under the MIT License. The app contains an independent, narrow implementation for converting exactly 256 bits of entropy to and from the standard 24-word form; it does not derive cryptocurrency wallet seeds.

Dependency versions are pinned in `app/build.gradle`. This notice is informational; the corresponding upstream license texts and source repositories remain authoritative.
