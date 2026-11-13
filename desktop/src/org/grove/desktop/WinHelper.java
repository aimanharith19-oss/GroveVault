package org.grove.desktop;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Calls GroveHelper.exe (YubiKey over USB, Windows DPAPI). */
public final class WinHelper {
    private WinHelper() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static File helper() {
        try {
            File jar = new File(WinHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File root = jar.getParentFile().getParentFile(); // <install>\app\GroveVault.jar -> <install>
            return new File(root, "GroveHelper.exe");
        } catch (Exception e) {
            return new File("GroveHelper.exe");
        }
    }

    public static boolean available() {
        return isWindows() && helper().exists();
    }

    private static String run(Consumer<String> status, String... args) throws IOException {
        if (!available()) throw new IOException("Security keys over USB need the Windows version of Grove Vault (GroveHelper.exe is missing).");
        String[] cmd = new String[args.length + 1];
        cmd[0] = helper().getAbsolutePath();
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String result = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.equals("TOUCH")) {
                    if (status != null) status.accept("Touch your security key now.");
                } else if (line.startsWith("ERR ")) {
                    throw new IOException(line.substring(4));
                } else if (!line.isEmpty()) result = line;
            }
        } finally {
            try { p.waitFor(); } catch (InterruptedException ignored) {}
        }
        if (result == null || !result.matches("[0-9a-f]+")) throw new IOException("No answer from the helper");
        return result;
    }

    /** YubiKey slot-2 HMAC-SHA1 challenge-response over USB. */
    public static byte[] yubikey(byte[] challenge, Consumer<String> status) throws IOException {
        return unhex(run(status, "yk", hex(challenge)));
    }

    /** Encrypts with Windows DPAPI for the current Windows user. */
    public static byte[] protect(byte[] data) throws IOException {
        return unhex(run(null, "protect", hex(data)));
    }

    public static byte[] unprotect(byte[] data) throws IOException {
        return unhex(run(null, "unprotect", hex(data)));
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    public static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
