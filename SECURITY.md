# Grove Vault security design

## What protects your vault

| Layer | How |
|---|---|
| Encryption | AES-256-GCM (authenticated). Every header byte is authenticated, so tampering is detected. |
| Password → key | **Argon2id**, 64 MiB memory, 4 passes, 2 lanes (memory-hard: GPU/ASIC guessing is expensive). Older PBKDF2 vaults upgrade automatically on the next password unlock. |
| Envelope encryption | A random 256-bit data key encrypts the vault; it is wrapped once per unlock method. Changing the password or unlock method always creates a **new data key** (old backups + old password can't read new data). |
| Key file (optional) | `HMAC-SHA256(Argon2id(password), SHA-256(key file))`: the vault can't be opened without the file. |
| Security key (optional) | YubiKey HMAC-SHA1 challenge-response (slot 2) over **NFC or USB** (Android) and **USB** (Windows). Several keys can be enrolled (keep a backup key); any one unlocks. When required, there is **no password-only slot**. |
| Fingerprint / PIN (Android) | Data key wrapped by an Android Keystore key (StrongBox when available), invalidated when biometrics change, usable only while the phone is unlocked. PIN (6–12 digits) adds a PBKDF2 layer; 3 wrong PINs delete it. By default these are **disabled when a key file or security key is required**. |
| PIN (Windows) | Data key sealed with a PIN-derived key and Windows DPAPI (bound to your Windows account). 3 wrong PINs delete it. |
| Brute-force on the device | Lock-out delay after 3 wrong attempts (5 s doubling, max 5 min); optional erase after 5/10/20 wrong attempts. |

## Loopholes found and fixed in 1.3.0

1. **Autofill phishing via fake package names**: matching cards by guessing from an app's package name (e.g. `com.evil.facebook`) was removed. Apps only get a card through an explicit link, and that link is **pinned to the app's signing certificate**; look-alike/sideloaded apps get a warning instead of your password.
2. **Autofill phishing via fake web domains**: any app can claim to show `bank.com`. Web domains are now only trusted from **verified browsers** (package + signing certificate on the published allow-list).
3. **Weak key derivation**: PBKDF2 → Argon2id (memory-hard), with automatic upgrade.
4. **Password change kept the same key**: now every change rotates the data key.
5. **Malicious files**: decompression size limits (zip bombs) for vault, KeePass and SafeInCloud files; limits on KDF parameters read from files (a tampered file can't make the app allocate gigabytes or hang); XML external entities disabled.
6. **Icon downloader SSRF**: every redirect hop and every resolved address is checked; local network, loopback, link-local and CGNAT addresses are refused, and only HTTPS is used.
7. **Overlay / tapjacking**: touches are ignored while another app draws over Grove Vault; other apps' overlays are hidden (Android 12+).
8. **Keyboard learning**: password fields ask the keyboard not to learn or store input.
9. **Recent-apps preview**: blocked (FLAG_SECURE, and recents screenshots off on Android 13+).
10. **Short PINs**: minimum 6 digits; repeated/sequential PINs rejected. Master password minimum 8 characters.
11. **Unlimited guessing on the unlock screen**: lock-out delays added (both platforms).

## What Grove Vault can't protect against

- Malware with root/administrator access on an **unlocked** device (it can read memory or log keystrokes).
- Someone who has your master password **and** all required factors.
- Losing the master password, the key file, or every enrolled security key: the vault cannot be recovered. Keep a backup key and a copy of the key file.
