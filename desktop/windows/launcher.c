/*
 * Grove Vault for Windows - native launcher.
 * Starts the bundled Java runtime (runtime\bin\javaw.exe) with app\GroveVault.jar, passing any file argument
 * (so double-clicking a .grove file opens it). Licensed GPL-3.0-or-later.
 */
#include <windows.h>
#include <wchar.h>

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR args, int show) {
    (void)inst; (void)prev; (void)show;
    wchar_t dir[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, dir, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return 1;
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash) *slash = 0;

    wchar_t javaw[MAX_PATH + 64];
    _snwprintf(javaw, MAX_PATH + 64, L"%ls\\runtime\\bin\\javaw.exe", dir);
    javaw[MAX_PATH + 63] = 0;

    size_t len = wcslen(javaw) + wcslen(dir) + (args ? wcslen(args) : 0) + 256;
    wchar_t *cmd = (wchar_t *)HeapAlloc(GetProcessHeap(), 0, len * sizeof(wchar_t));
    if (!cmd) return 1;
    _snwprintf(cmd, len, L"\"%ls\" -Dsun.java2d.uiScale.enabled=true -Dfile.encoding=UTF-8 -jar \"%ls\\app\\GroveVault.jar\" %ls",
               javaw, dir, args ? args : L"");
    cmd[len - 1] = 0;

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof si);
    si.cb = sizeof si;
    ZeroMemory(&pi, sizeof pi);
    if (!CreateProcessW(javaw, cmd, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
        MessageBoxW(NULL, L"Grove Vault couldn't start its Java runtime.\n\nKeep GroveVault.exe together with the 'runtime' and 'app' folders (extract the whole zip).",
                    L"Grove Vault", MB_ICONERROR | MB_OK);
        return 1;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    HeapFree(GetProcessHeap(), 0, cmd);
    return 0;
}
