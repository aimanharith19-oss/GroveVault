Grove Vault for Windows
=======================

1. Extract this whole zip somewhere permanent, e.g. C:\Users\<you>\Apps\GroveVault
   (keep GroveVault.exe next to the "runtime" and "app" folders).
2. Double-click GroveVault.exe. Right-click it -> "Pin to Start" or "Send to -> Desktop" for a shortcut.
3. Create a vault, or open a .grove file (for example a backup from the Android app).

Moving data in:  Import -> From SafeInCloud (.db or .xml) / From KeePass (.kdbx) / From CSV.
Moving data out: Export -> To SafeInCloud (.db) / To KeePass (.kdbx) / To CSV.

Using the same data on your phone:
 - Phone -> PC: in the Android app turn on Backup & import -> Automatic backup to a Google Drive / OneDrive
   folder, then on the PC use File -> Merge from another .grove file (or open the backup directly).
 - PC -> phone: put the .grove file where the phone can reach it, then in the Android app use
   Backup & import -> Restore from backup -> Merge.
 - If the PC vault file sits in a synced folder and changes elsewhere, Grove Vault merges the changes
   automatically when you switch back to its window.

Security: works offline (no network access), locks after inactivity (Tools -> Settings), clears copied
passwords from the clipboard, and keeps a .bak copy of the vault file next to it.
No installation and no admin rights needed; a Java runtime is included.
Windows SmartScreen may warn because the program isn't code-signed: choose "More info" -> "Run anyway".

Grove Vault is open source under the GNU GPL v3 (see LICENSE).
