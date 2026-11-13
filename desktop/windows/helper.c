/*
 * GroveHelper.exe - small native helper for Grove Vault on Windows (GPL-3.0-or-later).
 *
 *   GroveHelper yk <challenge-hex>          YubiKey HMAC-SHA1 challenge-response (slot 2) over USB HID.
 *                                          Prints the 20-byte response as hex, or "TOUCH" lines while waiting, or "ERR <msg>".
 *   GroveHelper protect <hex>              Windows DPAPI (current user): prints protected blob as hex.
 *   GroveHelper unprotect <hex>            Reverses "protect".
 *
 * The YubiKey protocol mirrors org.grove.vault.core.YubiKey (8-byte feature reports, 70-byte frame, CRC-16/X.25).
 */
#include <windows.h>
#include <setupapi.h>
#include <hidsdi.h>
#include <hidpi.h>
#include <wincrypt.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#define YUBICO_VID 0x1050
#define SLOT_WRITE_FLAG 0x80
#define RESP_PENDING_FLAG 0x40
#define RESP_TIMEOUT_WAIT_FLAG 0x20
#define DUMMY_REPORT_WRITE 0x8f

static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static int unhex(const char *s, unsigned char *out, int max) {
    int n = (int)strlen(s);
    if (n % 2 || n / 2 > max) return -1;
    for (int i = 0; i < n / 2; i++) {
        int a = hexval(s[2 * i]), b = hexval(s[2 * i + 1]);
        if (a < 0 || b < 0) return -1;
        out[i] = (unsigned char)(a * 16 + b);
    }
    return n / 2;
}

static void puthex(const unsigned char *b, DWORD n) {
    for (DWORD i = 0; i < n; i++) printf("%02x", b[i]);
    printf("\n");
}

static unsigned short crc16(const unsigned char *b, int len) {
    unsigned short crc = 0xffff;
    for (int i = 0; i < len; i++) {
        crc ^= b[i];
        for (int j = 0; j < 8; j++) {
            int lsb = crc & 1;
            crc >>= 1;
            if (lsb) crc ^= 0x8408;
        }
    }
    return crc;
}

/* Opens the YubiKey OTP HID interface (the one with 8-byte feature reports). */
static HANDLE open_yubikey(void) {
    GUID guid;
    HidD_GetHidGuid(&guid);
    HDEVINFO info = SetupDiGetClassDevsW(&guid, NULL, NULL, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
    if (info == INVALID_HANDLE_VALUE) return INVALID_HANDLE_VALUE;
    HANDLE found = INVALID_HANDLE_VALUE;
    SP_DEVICE_INTERFACE_DATA ifd;
    ifd.cbSize = sizeof ifd;
    for (DWORD i = 0; found == INVALID_HANDLE_VALUE && SetupDiEnumDeviceInterfaces(info, NULL, &guid, i, &ifd); i++) {
        DWORD need = 0;
        SetupDiGetDeviceInterfaceDetailW(info, &ifd, NULL, 0, &need, NULL);
        PSP_DEVICE_INTERFACE_DETAIL_DATA_W det = (PSP_DEVICE_INTERFACE_DETAIL_DATA_W)malloc(need);
        if (!det) continue;
        det->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);
        if (SetupDiGetDeviceInterfaceDetailW(info, &ifd, det, need, NULL, NULL)) {
            /* zero access rights: allowed even for keyboard-class HID; feature reports still work */
            HANDLE h = CreateFileW(det->DevicePath, 0, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING, 0, NULL);
            if (h != INVALID_HANDLE_VALUE) {
                HIDD_ATTRIBUTES attr;
                attr.Size = sizeof attr;
                PHIDP_PREPARSED_DATA pp = NULL;
                HIDP_CAPS caps;
                int ok = 0;
                if (HidD_GetAttributes(h, &attr) && attr.VendorID == YUBICO_VID && HidD_GetPreparsedData(h, &pp)) {
                    if (HidP_GetCaps(pp, &caps) == HIDP_STATUS_SUCCESS && caps.FeatureReportByteLength == 9) ok = 1;
                    HidD_FreePreparsedData(pp);
                }
                if (ok) found = h;
                else CloseHandle(h);
            }
        }
        free(det);
    }
    SetupDiDestroyDeviceInfoList(info);
    return found;
}

static int get_report(HANDLE h, unsigned char *r8) {
    unsigned char buf[9] = {0};
    if (!HidD_GetFeature(h, buf, 9)) return 0;
    memcpy(r8, buf + 1, 8);
    return 1;
}

static int set_report(HANDLE h, const unsigned char *r8) {
    unsigned char buf[9] = {0};
    memcpy(buf + 1, r8, 8);
    return HidD_SetFeature(h, buf, 9) ? 1 : 0;
}

static int cmd_yk(const char *hex) {
    unsigned char chal[64];
    int n = unhex(hex, chal, 64);
    if (n <= 0) { printf("ERR bad challenge\n"); return 2; }
    /* PKCS#7-style padding to 64 bytes, same as the Android app */
    for (int i = n; i < 64; i++) chal[i] = (unsigned char)(64 - n);
    HANDLE h = open_yubikey();
    if (h == INVALID_HANDLE_VALUE) { printf("ERR No YubiKey found. Plug it in (USB) and try again.\n"); return 3; }

    unsigned char frame[70] = {0};
    memcpy(frame, chal, 64);
    frame[64] = 0x38; /* slot 2 HMAC challenge-response */
    unsigned short crc = crc16(chal, 64);
    frame[65] = (unsigned char)(crc & 0xff);
    frame[66] = (unsigned char)(crc >> 8);

    unsigned char r[8];
    for (int seq = 0, off = 0; off < 70; seq++, off += 7) {
        int zero = 1;
        for (int i = 0; i < 7; i++) if (frame[off + i]) zero = 0;
        if (zero && seq > 0 && off + 7 < 70) continue;
        int waited = 0;
        for (;;) {
            if (!get_report(h, r)) { printf("ERR USB read failed\n"); CloseHandle(h); return 4; }
            if (!(r[7] & SLOT_WRITE_FLAG)) break;
            Sleep(10);
            if ((waited += 10) > 1500) { printf("ERR The key is busy\n"); CloseHandle(h); return 4; }
        }
        unsigned char rep[8];
        memcpy(rep, frame + off, 7);
        rep[7] = (unsigned char)(SLOT_WRITE_FLAG | seq);
        if (!set_report(h, rep)) { printf("ERR USB write failed\n"); CloseHandle(h); return 4; }
    }

    unsigned char out[28] = {0};
    int got = 0, waited = 0, hinted = 0;
    for (;;) {
        if (!get_report(h, r)) { printf("ERR USB read failed\n"); CloseHandle(h); return 4; }
        if (r[7] & RESP_PENDING_FLAG) break;
        if ((r[7] & RESP_TIMEOUT_WAIT_FLAG) && !hinted) { printf("TOUCH\n"); fflush(stdout); hinted = 1; }
        Sleep(20);
        if ((waited += 20) > 15000) { printf("ERR No answer from the key (touch it if it blinks)\n"); CloseHandle(h); return 5; }
    }
    memcpy(out, r, 7); /* the report that signals 'pending' carries the first 7 bytes */
    got = 7;
    while (got < 22) {
        if (!get_report(h, r)) break;
        if (!(r[7] & RESP_PENDING_FLAG)) break;
        if ((r[7] & 0x1f) == 0 && got > 0) break;
        memcpy(out + got, r, 7);
        got += 7;
    }
    unsigned char reset[8] = {0};
    reset[7] = DUMMY_REPORT_WRITE;
    set_report(h, reset);
    CloseHandle(h);
    if (got < 22) { printf("ERR Incomplete answer from the key\n"); return 6; }
    if (crc16(out, 22) != 0xf0b8) { printf("ERR Corrupted answer from the key\n"); return 6; }
    puthex(out, 20);
    return 0;
}

static int cmd_dpapi(const char *hex, int protect) {
    size_t len = strlen(hex) / 2 + 1;
    unsigned char *in = (unsigned char *)malloc(len);
    int n = in ? unhex(hex, in, (int)len) : -1;
    if (n < 0) { printf("ERR bad input\n"); return 2; }
    DATA_BLOB src = {(DWORD)n, in}, dst = {0, NULL};
    BOOL ok = protect ? CryptProtectData(&src, L"Grove Vault", NULL, NULL, NULL, CRYPTPROTECT_UI_FORBIDDEN, &dst)
                      : CryptUnprotectData(&src, NULL, NULL, NULL, NULL, CRYPTPROTECT_UI_FORBIDDEN, &dst);
    SecureZeroMemory(in, len);
    free(in);
    if (!ok) { printf("ERR DPAPI failed (%lu)\n", GetLastError()); return 3; }
    puthex(dst.pbData, dst.cbData);
    SecureZeroMemory(dst.pbData, dst.cbData);
    LocalFree(dst.pbData);
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "yk") == 0) return cmd_yk(argv[2]);
    if (argc == 3 && strcmp(argv[1], "protect") == 0) return cmd_dpapi(argv[2], 1);
    if (argc == 3 && strcmp(argv[1], "unprotect") == 0) return cmd_dpapi(argv[2], 0);
    printf("ERR usage: GroveHelper yk|protect|unprotect <hex>\n");
    return 1;
}
