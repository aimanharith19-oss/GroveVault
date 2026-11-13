package org.grove.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.grove.vault.core.YubiKey;

import java.io.IOException;

/**
 * Asks a YubiKey (or compatible key with HMAC-SHA1 challenge-response in slot 2) to answer a challenge,
 * over NFC (hold the key to the phone) or USB (plug it in). One prompt handles both.
 */
public final class SecurityKeyReader {
    public interface Callback {
        void onResponse(byte[] response, String via);
        void onError(String message);
    }

    private static final String ACTION_USB_PERMISSION = "org.grove.vault.USB_PERMISSION";
    private static final int YUBICO_VENDOR = 0x1050;

    private final Activity a;
    private final byte[] challenge;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private boolean done;
    private BroadcastReceiver usbReceiver;
    private Runnable usbPoll;

    private SecurityKeyReader(Activity a, byte[] challenge, Callback cb) {
        this.a = a;
        this.challenge = challenge;
        this.cb = cb;
    }

    public static void request(Activity a, String title, byte[] challenge, Callback cb) {
        new SecurityKeyReader(a, challenge, cb).start(title);
    }

    private void start(String title) {
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(a);
        String how;
        if (nfc != null && nfc.isEnabled()) how = "Hold your security key flat against the back of the phone, or plug it into the USB port.";
        else if (nfc != null) how = "Plug your security key into the USB port. (Or turn on NFC in the phone's settings and hold the key to the back of the phone.)";
        else how = "Plug your security key into the USB port.";
        dialog = Ui.dialog(a, title).setMessage("🔐  " + how + "\n\nIf the key blinks, touch it.")
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { finish(null, "Cancelled"); }
                }).setCancelable(false).create();
        dialog.show();
        if (nfc != null && nfc.isEnabled()) {
            nfc.enableReaderMode(a, new NfcAdapter.ReaderCallback() {
                public void onTagDiscovered(Tag tag) { readNfc(tag); }
            }, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
        }
        startUsb();
    }

    private void readNfc(Tag tag) {
        final IsoDep iso = IsoDep.get(tag);
        if (iso == null) return;
        try {
            iso.connect();
            iso.setTimeout(5000);
            byte[] r = YubiKey.respondNfc(new YubiKey.Apdu() {
                public byte[] transceive(byte[] c) throws IOException { return iso.transceive(c); }
            }, YubiKey.SLOT_2, challenge);
            finish(r, "NFC");
        } catch (final IOException e) {
            main.post(new Runnable() {
                public void run() {
                    if (!done && dialog != null) dialog.setMessage("🔐  " + e.getMessage() + "\n\nTry again: hold the key still against the phone for a second.");
                }
            });
        } finally {
            try { iso.close(); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------- USB

    private UsbManager usb() {
        return (UsbManager) a.getSystemService(Context.USB_SERVICE);
    }

    private void startUsb() {
        if (usb() == null) return;
        usbReceiver = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d == null) return;
                if (ACTION_USB_PERMISSION.equals(i.getAction())) {
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) readUsbAsync(d);
                    else update("USB access was denied. Allow it to use the key.");
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
                    tryDevice(d);
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else a.registerReceiver(usbReceiver, f);
        usbPoll = new Runnable() {
            boolean asked;
            public void run() {
                if (done) return;
                if (!asked) {
                    for (UsbDevice d : usb().getDeviceList().values()) {
                        if (otpInterface(d) != null) {
                            asked = true;
                            tryDevice(d);
                            break;
                        }
                    }
                }
                main.postDelayed(this, 1000);
            }
        };
        main.post(usbPoll);
    }

    private void tryDevice(UsbDevice d) {
        if (done || otpInterface(d) == null) return;
        if (usb().hasPermission(d)) readUsbAsync(d);
        else {
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(a.getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb().requestPermission(d, PendingIntent.getBroadcast(a, 0, i, flags));
        }
    }

    /** The YubiKey OTP interface: HID with keyboard protocol (Yubico) — that's where challenge-response lives. */
    private static UsbInterface otpInterface(UsbDevice d) {
        if (d.getVendorId() != YUBICO_VENDOR) return null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == UsbConstants.USB_CLASS_HID && f.getInterfaceProtocol() == 1) return f;
        }
        return null;
    }

    private void readUsbAsync(final UsbDevice d) {
        update("Reading the key…  touch it if it blinks.");
        new Thread(new Runnable() {
            public void run() {
                UsbInterface iface = otpInterface(d);
                UsbDeviceConnection conn = usb().openDevice(d);
                if (conn == null || iface == null) {
                    update("Couldn't open the key. Unplug it and plug it in again.");
                    return;
                }
                try {
                    conn.claimInterface(iface, true);
                    final UsbDeviceConnection c = conn;
                    final int idx = iface.getId();
                    byte[] r = YubiKey.respondUsb(new YubiKey.FeatureReports() {
                        public void set(byte[] rep) throws IOException {
                            if (c.controlTransfer(0x21, 0x09, 0x0300, idx, rep, 8, 1000) < 0) throw new IOException("USB write failed");
                        }
                        public byte[] get() throws IOException {
                            byte[] b = new byte[8];
                            if (c.controlTransfer(0xA1, 0x01, 0x0300, idx, b, 8, 1000) < 0) throw new IOException("USB read failed");
                            return b;
                        }
                        public void sleep(int ms) {
                            try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
                        }
                    }, YubiKey.SLOT_2, challenge, new Runnable() {
                        public void run() { update("Touch your security key now."); }
                    });
                    finish(r, "USB");
                } catch (IOException e) {
                    update(e.getMessage());
                } finally {
                    try { conn.releaseInterface(iface); } catch (Exception ignored) {}
                    conn.close();
                }
            }
        }).start();
    }

    private void update(final String msg) {
        main.post(new Runnable() {
            public void run() {
                if (!done && dialog != null) dialog.setMessage("🔐  " + msg);
            }
        });
    }

    private void finish(final byte[] response, final String errorOrVia) {
        main.post(new Runnable() {
            public void run() {
                if (done) return;
                done = true;
                try { dialog.dismiss(); } catch (Exception ignored) {}
                try {
                    NfcAdapter nfc = NfcAdapter.getDefaultAdapter(a);
                    if (nfc != null) nfc.disableReaderMode(a);
                } catch (Exception ignored) {}
                try { if (usbReceiver != null) a.unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
                if (usbPoll != null) main.removeCallbacks(usbPoll);
                if (response != null) cb.onResponse(response, errorOrVia);
                else cb.onError(errorOrVia);
            }
        });
    }
}
