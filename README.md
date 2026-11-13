# Grove Vault 🌳

An offline, open-source password manager for **Android and Windows**. It has the same features as SafeInCloud, plus passkeys, SafeInCloud database import/export, and full KeePass import and export.

* **Licence:** GPL-3.0-or-later (see `LICENSE`, `NOTICE`)
* **Requires:** Android 8.0+ (API 26); targets Android 14 (API 34)
* **Privacy:** no ads, no trackers, no analytics, no accounts. The only network use is downloading website icons (can be turned off); vault data is never sent anywhere. The only dependency is the Android framework.

## Download

[**GroveVault-1.4.2.apk**](apk/GroveVault-1.4.2.apk) (Android 8.0+). Installs over earlier versions.

## Features

### Vault and cards
- Encrypted with **AES-256-GCM** using a random data key, unlocked with **Argon2id** (64 MiB, memory-hard) from your master password, plus an optional key file and/or YubiKey. The whole file header is authenticated. Older vaults upgrade automatically.
- **16 built-in templates:** web account, email, login, credit card, bank account, ID/passport, driving licence, insurance, membership, Wi-Fi, software licence, server/SSH, social security, PIN, secure note and custom. You can edit them, add your own, or restore the defaults.
- **13 field types:** text, login, email, password, website, number, PIN, phone, date, expiry date, one-time password (2FA), secret text and multi-line text. Fields can be added, renamed, retyped and reordered.
- **Labels**: like folders, but a card can have several. Also favourites, 20 icons and 12 colours.
- **Attachments**: files or images up to 5 MB, encrypted inside the vault.
- **Password history**: each secret field keeps its previous 20 values.
- **Trash** with restore. You can also duplicate a card or save one as a template.

### Website icons
- Cards show the **website's own icon**, downloaded automatically from the card's website (or the passkey's site) and cached on the phone under hashed file names.
- Only the site address is used: the icon is fetched straight from that website over HTTPS (never plain HTTP, never local network addresses). No third-party icon service is involved, so no single company sees your list of sites.
- Prefers the sharp high-resolution icons (apple-touch-icon / 192 px) and falls back to `/favicon.ico`; sites without an icon keep the coloured badge and are retried after 3 days.
- Cards linked to an Android app show that app's installed icon (no download).
- Settings → Appearance → *Download website icons* (on by default).

### Everyday use
- **List or grid**: tap ▦ / ☰ in the top bar to switch; the choice is remembered.
- Search, filter chips, three sort orders and favourites at the top.
- **Tap any value to copy it.** Copies are marked as sensitive and the clipboard clears automatically (15 s to 2 min, or never).
- **Live 2FA codes (TOTP)** with a countdown. Works with `otpauth://` links, plain base32 keys and Steam Guard.
- Password reveal, a large-text view, and one-tap open for websites, phone numbers and email addresses.
- A **password generator** with random or passphrase mode, length, character sets and an option to avoid look-alike characters.
- An offline **strength meter**.

### Security audit
- **Weak passwords**, **reused passwords** (it lists which cards share each one), and **expiring or expired cards**, with a configurable warning period and a daily reminder.

### Physical security key & key file (both apps)
- **YubiKey login**: HMAC-SHA1 challenge-response (slot 2), on Android over **NFC** (tap the key on the back of the phone) or **USB**, and on Windows over **USB**. Enrol several keys; any one unlocks. Compatible with keys set up for KeePassXC / KeePassDX.
- **Key file**: any file (best: the random one Grove creates) that must be present to unlock, e.g. on a USB stick or SD card.
- Combine them: password + key file + security key. See [SECURITY.md](SECURITY.md) for the full design and the loopholes fixed in 1.3.0.

### Locking
- Auto-lock: immediately, or after 30 s, 1 min, 5 min, 15 min or 1 h, or never. Optionally lock when the screen turns off.
- **Biometric unlock** (Android 9+). The vault key is wrapped by a hardware Keystore key that is invalidated if your fingerprints or face data change.
- **PIN unlock**: a PIN-derived key *and* a Keystore key wrap the vault key, and 3 wrong tries turn PIN unlock off.
- Optionally **erase the vault after 5, 10 or 20 wrong passwords**.
- Screenshots and the recent-apps preview are blocked unless you allow them.
- Change the master password, or erase all data.

### Autofill
- **Keyboard suggestions (Android 11+)**: matching logins appear as chips in the keyboard's suggestion strip (Gboard, Samsung Keyboard and others that support inline autofill), with the site's icon; plus a pinned *Grove Vault* chip to search or unlock.
- **Passwords in Android 14's sign-in sheet**: apps that use Credential Manager can get and save passwords (not just passkeys) from Grove Vault (Settings → Passwords, passkeys & accounts → Grove Vault).
- **Small save window**: after you sign in on a website or app, a compact pop-up asks *Save to Grove Vault?* (title, login, password, label) or *Update password?* for an existing login. *Never here* stops asking for that site/app.
- An Android **autofill service** for apps and browsers. It matches cards by web domain, by linked app, or by app name.
- When the vault is locked, it asks you to unlock first, then fills.
- **Search Grove Vault…**: pick any card to fill. The card is then remembered for that app.
- Fills login, password, 2FA code and credit-card fields. After filling a login, it can copy the 2FA code.
- **Save prompt** after you log in: it updates the password of a matching card, or opens a new card already filled in.

### Backup and restore
- **Back up now**: saves an encrypted `.grove` file to the phone, an SD card, or Google Drive, Dropbox or OneDrive through their file pickers.
- **Restore**, then choose **Replace** (your vault becomes the backup) or **Merge** (newer cards win).
- **Automatic folder backups** every change, daily or weekly, keeping the newest 3 to 50. Point it at a cloud-synced folder for off-device copies.
- **Local snapshots**: automatic, up to twice a day, keeping 7. A snapshot is also taken before every Replace.

### SafeInCloud databases
- **Import `SafeInCloud.db`** (the encrypted database from SafeInCloud's app folder, cloud folder or backups) or an **XML export** from SafeInCloud for Windows/Mac.
- Brings in cards, templates, labels, favourites, deleted cards (into Trash), archived cards (label “Archived”), password history, linked Android apps, notes, images and file attachments.
- **Export `SafeInCloud.db`** that SafeInCloud can open, with your chosen password.
- Tested against SafeInCloud's own encryption routine in both directions, including non-ASCII passwords.

### Passkeys (Android 14+)
- Grove Vault is a **passkey provider**: sites and apps that offer passkeys can save them in your vault, and you sign in from the system passkey sheet.
- ES256 (P-256) discoverable credentials; attestation “none”; backup-eligible flags set (they're synced through your vault backups).
- Website requests are only accepted from browsers on the published privileged-browser list, and the site must match the passkey's domain. App requests use the app's signing-key origin, so the site's server can check it.
- Creating a passkey asks for your fingerprint, face or screen lock.
- Passkeys are kept in KeePass exports in **KeePassXC's format** (`KPEX_PASSKEY_*`), and KeePassXC passkeys are imported.
- Turn it on in Settings → Autofill → Passkeys (Android: Passwords, passkeys & accounts → Grove Vault).

### Windows app
- Same security as the phone: Argon2id vaults, **key file**, **YubiKey over USB**, **PIN quick unlock protected by Windows DPAPI**, lock-out after wrong attempts and optional erase, auto-lock, clipboard clearing.
- **Website icons**, **Auto-type** (fills login + password into the previous window), **daily backups to a folder**, expiry reminders.
- Portable `GroveVault.exe` (no installer, no admin rights, Java runtime bundled). Opens `.grove` vaults and backups from the Android app.
- Card list with search, labels, favourites, audit filters (weak / reused / expiring), trash and templates; live 2FA codes; click to copy with clipboard auto-clear; card editor with fields, labels, attachments and the generator.
- Import/export SafeInCloud, KeePass and CSV; merge another `.grove` file; auto-lock after inactivity; `.bak` copy on every save.
- If the vault file is in a synced folder and changes elsewhere, the changes are merged when you return to the window.

### KeePass and other managers
- **Import KeePass `.kdbx`:** KDBX 3.1 and 4.x, AES-256 and ChaCha20, AES-KDF, Argon2d and Argon2id. Works with a password, a key file (XML v1/v2, binary or hex), or both. Groups and tags become labels. TOTP from KeePassXC (`otp`), KeePass (`TimeOtp-*`) and older plugins (`TOTP Seed`) is understood. Attachments, custom fields, expiry dates and the Recycle Bin (optional) are imported too.
- **Export KeePass `.kdbx`:** KDBX 4.0 with Argon2d, Argon2id or AES-KDF, and AES-256 or ChaCha20. Labels become groups and tags, favourites become a `Favorite` tag, and app links are stored as `AndroidApp`. The file opens in KeePassXC, KeePassDX, KeePass 2, Strongbox and other KeePass apps.
- **CSV import:** Chrome, Edge, Firefox, Bitwarden, LastPass, 1Password, Proton Pass, KeePassXC and similar column layouts.
- **CSV export:** plain text, with a warning before saving.
- *Not supported:* Twofish-encrypted KeePass databases (KeePass's default is AES). Change the cipher in KeePass first.

## Building

**Windows app:** `desktop/build-windows.sh` compiles the jar, jlinks a Windows Java runtime from Temurin 21.0.8 jmods, and cross-compiles `GroveVault.exe` with zig (`pip install ziglang`).

**Android app:**

**Without Gradle:** run `./build.sh`. It uses aapt2, javac, dx and uber-apk-signer, and expects them in `../tools`:

| Tool | Where it comes from |
|---|---|
| `../tools/ap/android-34/android.jar` | `git clone --sparse https://github.com/Sable/android-platforms` |
| `../tools/prebuilt/linux/aapt2_64` | extracted from the Apktool 2.10 jar |
| `../tools/d2j/*/lib/dx-*.jar` | dex2jar 2.4 release |
| `../tools/uber.jar` | uber-apk-signer 1.3.0 |

**With Android Studio:** open the folder. The Gradle files are included.

Set `VERSION_CODE` and `VERSION_NAME` to stamp a release. The first build creates `release.keystore`. Keep it, so updates install over earlier versions.

## Tests

`test/CoreTest.java` runs on a desktop JVM and checks:
- Argon2d, Argon2i and Argon2id against the RFC 9106 test vectors
- TOTP against the RFC 6238 test vectors
- a vault round trip, and rejection of a wrong password
- the generator and strength meter
- CSV import and export
- KeePass import of pykeepass's test databases (KDBX 3/4, AES/ChaCha20, AES-KDF/Argon2, key files)
- KeePass export in every cipher and KDF combination

The exported files were also opened with pykeepass, an independent implementation.

```
javac -d out $(find app/src/main/java/org/grove/vault/core -name '*.java') test/CoreTest.java
java -cp out CoreTest <path-to-pykeepass/tests> <output-dir>
```

## Vault file format (`.grove`)

```
"GROV" | u8 version=1 | u8 kdf=1 (PBKDF2-HMAC-SHA256) | i32 iterations | 32-byte salt | 12-byte IV | AES-256-GCM( gzip( XML ) )
```

The header is the GCM associated data, so it can't be changed without detection.

## Credits
The feature set was inspired by SafeInCloud. All code is original, and no SafeInCloud code, artwork or text is used.
