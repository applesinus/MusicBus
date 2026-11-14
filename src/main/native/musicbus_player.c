#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif

#include <windows.h>
#include <ole2.h>
#include <oleauto.h>
#include <exdisp.h>
#include <shldisp.h>
#include <shlobj.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef SWC_DESKTOP
#define SWC_DESKTOP 8
#endif
#ifndef SWFO_NEEDDISPATCH
#define SWFO_NEEDDISPATCH 1
#endif
#ifndef SVGIO_BACKGROUND
#define SVGIO_BACKGROUND 0
#endif
#ifndef PIPE_REJECT_REMOTE_CLIENTS
#define PIPE_REJECT_REMOTE_CLIENTS 0x00000008
#endif

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Weverything"
#define STB_VORBIS_HEADER_ONLY
#include "stb_vorbis.c"

#define MINIAUDIO_IMPLEMENTATION
#define MA_NO_MP3
#define MA_NO_FLAC
#define MA_NO_WAV
#define MA_NO_GENERATION
#include "miniaudio.h"
#pragma clang diagnostic pop

/* ============================================================================
 * Protocol constants (must match ExternalPlayerProtocol.java)
 * ========================================================================= */

#define PROTOCOL_VERSION 1u
#define MAX_FRAME_BYTES  (64u * 1024u * 1024u)

#define CMD_PLAY         0x01u
#define CMD_STOP         0x02u
#define CMD_PAUSE        0x03u
#define CMD_RESUME       0x04u
#define CMD_SEEK         0x05u
#define CMD_SET_VOLUME   0x06u
#define CMD_SET_PITCH    0x07u
#define CMD_SET_LOOPING  0x08u
#define CMD_SET_DEVICE   0x09u
#define CMD_PING         0x0Au
#define CMD_LIST_DEVICES 0x0Bu
#define CMD_SHUTDOWN     0x0Fu

#define EVT_READY        0x81u
#define EVT_STATUS       0x82u
#define EVT_ERROR        0x83u
#define EVT_PONG         0x84u
#define EVT_DEVICES      0x85u
#define EVT_LOG          0x86u

#define STATE_PLAYING    0u
#define STATE_PAUSED     1u
#define STATE_STOPPED    2u

#define ERR_DECODE       1u
#define ERR_AUDIO_DEVICE 2u
#define ERR_PROTOCOL     3u

#define WM_APP_QUIT      (WM_APP + 1)
#define WM_APP_REFRESH   (WM_APP + 2)

#define INPUT_BUF_FRAMES 1024u
#define RAMP_STEP        0.0025f /* ~8 ms ramp at 48 kHz */

/* ============================================================================
 * Track & Global State
 * ========================================================================= */

typedef struct TrackContext {
    uint8_t      uuid[16];
    uint8_t*     oggData;
    size_t       oggSize;

    ma_decoder   decoder;
    int          decoderInitialized;

    ma_resampler resampler;
    int          resamplerInitialized;

    ma_uint32    srcSampleRate;
    ma_uint32    dstSampleRate;
    ma_uint64    totalSourceFrames;
    ma_uint64    currentSourceFrame;

    float        targetVolume;
    float        currentGain;
    float        pitch;
    int          looping;
    uint8_t      state;
    int          pausingFade;
    int          decoderEof;

    float        inputBuf[INPUT_BUF_FRAMES * 2u];
    ma_uint32    inputBufFrames;
} TrackContext;

static CRITICAL_SECTION g_audioLock;
static CRITICAL_SECTION g_evtLock;

static TrackContext*    g_activeTrack = NULL;

static ma_context       g_maContext;
static int              g_maContextReady = 0;

static ma_device        g_maDevice;
static int              g_maDeviceReady = 0;
static ma_uint32        g_deviceSampleRate = 48000u;
static char             g_desiredDeviceName[512] = "";

static volatile LONG    g_running = 1;
static volatile LONG    g_evtConnected = 0;
static HANDLE           g_stopEvent = NULL;
static HANDLE           g_pipeConnectedEvent = NULL;
static HANDLE           g_cmdHandle = INVALID_HANDLE_VALUE;
static HANDLE           g_evtHandle = INVALID_HANDLE_VALUE;
static int              g_usingNamedPipes = 0;
static char             g_pipeBase[256] = "";
static HWND             g_hwnd = NULL;
static DWORD            g_parentPid = 0;
static int              g_windowHidden = 0;
static int              g_bootstrapMode = 0;

static uint8_t          g_lastReportedUuid[16];
static uint8_t          g_lastReportedState = 0xFFu;
static int              g_hasReportedOnce = 0;

/* ============================================================================
 * COM GUIDs for Explorer ShellExecute (break out of Java process tree)
 * ========================================================================= */

static const GUID MB_CLSID_ShellWindows =
    { 0x9BA05972, 0xF6A8, 0x11CF, { 0xA4, 0x42, 0x00, 0xA0, 0xC9, 0x0A, 0x8F, 0x39 } };
static const GUID MB_IID_IShellWindows =
    { 0x85CB6900, 0x4D95, 0x11CF, { 0x96, 0x0C, 0x00, 0x80, 0xC7, 0xF4, 0xEE, 0x85 } };
static const GUID MB_IID_IServiceProvider =
    { 0x6D5140C1, 0x7436, 0x11CE, { 0x80, 0x34, 0x00, 0xAA, 0x00, 0x60, 0x09, 0xFA } };
static const GUID MB_SID_STopLevelBrowser =
    { 0x4C96BE40, 0x915C, 0x11CF, { 0x99, 0xD3, 0x00, 0xAA, 0x00, 0x4A, 0xE8, 0x37 } };
static const GUID MB_IID_IShellBrowser =
    { 0x000214E2, 0x0000, 0x0000, { 0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46 } };
static const GUID MB_IID_IShellFolderViewDual =
    { 0xE7A1AF80, 0x4D96, 0x11CF, { 0x96, 0x0C, 0x00, 0x80, 0xC7, 0xF4, 0xEE, 0x85 } };
static const GUID MB_IID_IShellDispatch2 =
    { 0xA4C6892C, 0x3BA9, 0x11D2, { 0x9D, 0xEA, 0x00, 0xC0, 0x4F, 0xB1, 0x61, 0x62 } };

/* ============================================================================
 * Byte Helpers & Frame I/O
 * ========================================================================= */

static uint16_t read_u16_le(const uint8_t* p) {
    return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

static uint32_t read_u32_le(const uint8_t* p) {
    return (uint32_t)p[0]
        | ((uint32_t)p[1] << 8)
        | ((uint32_t)p[2] << 16)
        | ((uint32_t)p[3] << 24);
}

static uint64_t read_u64_le(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) {
        v |= ((uint64_t)p[i]) << (i * 8);
    }
    return v;
}

static float read_f32_le(const uint8_t* p) {
    uint32_t bits = read_u32_le(p);
    float val = 0.0f;
    memcpy(&val, &bits, sizeof(val));
    return val;
}

static double read_f64_le(const uint8_t* p) {
    uint64_t bits = read_u64_le(p);
    double val = 0.0;
    memcpy(&val, &bits, sizeof(val));
    return val;
}

static void write_u16_le(uint8_t* p, uint16_t v) {
    p[0] = (uint8_t)(v & 0xFFu);
    p[1] = (uint8_t)((v >> 8) & 0xFFu);
}

static void write_u32_le(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)(v & 0xFFu);
    p[1] = (uint8_t)((v >> 8) & 0xFFu);
    p[2] = (uint8_t)((v >> 16) & 0xFFu);
    p[3] = (uint8_t)((v >> 24) & 0xFFu);
}

static void write_u64_le(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; i++) {
        p[i] = (uint8_t)((v >> (i * 8)) & 0xFFu);
    }
}

static void write_f64_le(uint8_t* p, double v) {
    uint64_t bits = 0;
    memcpy(&bits, &v, sizeof(bits));
    write_u64_le(p, bits);
}

static float clamp_volume(float v) {
    if (!isfinite(v) || v < 0.0f) return 0.0f;
    if (v > 2.0f) return 2.0f;
    return v;
}

static float clamp_pitch(float p) {
    if (!isfinite(p) || p < 0.05f) return 0.05f;
    if (p > 4.0f) return 4.0f;
    return p;
}

static int read_exact_cmd(uint8_t* dst, size_t len) {
    size_t offset = 0;
    while (offset < len) {
        if (!InterlockedCompareExchange(&g_running, 1, 1)) {
            return 0;
        }
        DWORD chunk = (DWORD)((len - offset) > 65536u ? 65536u : (len - offset));
        DWORD bytesRead = 0;
        BOOL ok = ReadFile(g_cmdHandle, dst + offset, chunk, &bytesRead, NULL);
        if (!ok || bytesRead == 0) {
            return 0;
        }
        offset += (size_t)bytesRead;
    }
    return 1;
}

static int write_exact_evt(const uint8_t* src, size_t len) {
    size_t offset = 0;
    while (offset < len) {
        DWORD chunk = (DWORD)((len - offset) > 65536u ? 65536u : (len - offset));
        DWORD written = 0;
        if (!WriteFile(g_evtHandle, src + offset, chunk, &written, NULL) || written == 0) {
            return 0;
        }
        offset += (size_t)written;
    }
    return 1;
}

static void send_frame(uint8_t type, const uint8_t* payload, uint32_t payloadLen) {
    if (!InterlockedCompareExchange(&g_evtConnected, 1, 1)) {
        return;
    }

    uint8_t header[5];
    write_u32_le(header, 1u + payloadLen);
    header[4] = type;

    EnterCriticalSection(&g_evtLock);
    int ok = write_exact_evt(header, sizeof(header));
    if (ok && payloadLen > 0u && payload != NULL) {
        ok = write_exact_evt(payload, payloadLen);
    }
    LeaveCriticalSection(&g_evtLock);

    if (!ok) {
        InterlockedExchange(&g_running, 0);
        if (g_stopEvent != NULL) {
            SetEvent(g_stopEvent);
        }
    }
}

static void log_msg(const char* msg) {
    if (msg == NULL) return;
    if (InterlockedCompareExchange(&g_evtConnected, 1, 1)) {
        size_t len = strlen(msg);
        if (len > 1024u) len = 1024u;
        uint8_t buf[2 + 1024];
        write_u16_le(buf, (uint16_t)len);
        if (len > 0u) {
            memcpy(buf + 2, msg, len);
        }
        send_frame(EVT_LOG, buf, (uint32_t)(2u + len));
    } else if (!g_usingNamedPipes) {
        fprintf(stderr, "[musicbus-player] %s\n", msg);
        fflush(stderr);
    }
}

static void send_ready(void) {
    uint8_t buf[8];
    write_u32_le(buf, PROTOCOL_VERSION);
    write_u32_le(buf + 4, (uint32_t)GetCurrentProcessId());
    send_frame(EVT_READY, buf, sizeof(buf));
}

static void send_status(const uint8_t uuid[16], uint8_t state, double positionSeconds) {
    uint8_t buf[25];
    memcpy(buf, uuid, 16);
    buf[16] = state;
    write_f64_le(buf + 17, positionSeconds < 0.0 ? 0.0 : positionSeconds);
    send_frame(EVT_STATUS, buf, sizeof(buf));
}

static void send_error(const uint8_t uuid[16], uint32_t code, const char* msg) {
    size_t msgLen = msg != NULL ? strlen(msg) : 0u;
    if (msgLen > 1024u) {
        msgLen = 1024u;
    }
    uint8_t buf[22 + 1024];
    if (uuid != NULL) {
        memcpy(buf, uuid, 16);
    } else {
        memset(buf, 0, 16);
    }
    write_u32_le(buf + 16, code);
    write_u16_le(buf + 20, (uint16_t)msgLen);
    if (msgLen > 0u) {
        memcpy(buf + 22, msg, msgLen);
    }
    send_frame(EVT_ERROR, buf, (uint32_t)(22u + msgLen));
}

static void send_pong(uint64_t timestamp) {
    uint8_t buf[8];
    write_u64_le(buf, timestamp);
    send_frame(EVT_PONG, buf, sizeof(buf));
}

/* ============================================================================
 * Bootstrap Launcher (spawns daemon under explorer.exe, outside Java tree)
 * ========================================================================= */

static void append_escaped_arg_w(wchar_t* dst, size_t dstCap, const wchar_t* arg) {
    size_t cur = wcslen(dst);
    if (cur + 2 >= dstCap) return;
    if (cur > 0) {
        dst[cur++] = L' ';
        dst[cur] = L'\0';
    }

    int needsQuotes = (arg[0] == L'\0');
    for (size_t i = 0; arg[i] != L'\0'; i++) {
        if (arg[i] == L' ' || arg[i] == L'\t' || arg[i] == L'"') {
            needsQuotes = 1;
            break;
        }
    }

    if (!needsQuotes) {
        wcsncat(dst, arg, dstCap - cur - 1);
        return;
    }

    if (cur + 2 < dstCap) {
        dst[cur++] = L'"';
        dst[cur] = L'\0';
    }

    size_t backslashes = 0;
    for (size_t i = 0; arg[i] != L'\0' && cur + 4 < dstCap; i++) {
        wchar_t ch = arg[i];
        if (ch == L'\\') {
            backslashes++;
        } else if (ch == L'"') {
            for (size_t b = 0; b < backslashes * 2 + 1 && cur + 2 < dstCap; b++) {
                dst[cur++] = L'\\';
            }
            dst[cur++] = L'"';
            backslashes = 0;
        } else {
            for (size_t b = 0; b < backslashes && cur + 2 < dstCap; b++) {
                dst[cur++] = L'\\';
            }
            backslashes = 0;
            dst[cur++] = ch;
        }
    }
    for (size_t b = 0; b < backslashes * 2 && cur + 2 < dstCap; b++) {
        dst[cur++] = L'\\';
    }
    if (cur + 1 < dstCap) {
        dst[cur++] = L'"';
        dst[cur] = L'\0';
    }
}

static int launch_via_explorer_com(const wchar_t* exePath, const wchar_t* args) {
    HRESULT hrInit = CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    if (FAILED(hrInit) && hrInit != RPC_E_CHANGED_MODE) {
        return 0;
    }

    int success = 0;
    IShellWindows* psw = NULL;
    IDispatch* pdispDesktop = NULL;
    IServiceProvider* psp = NULL;
    IShellBrowser* psb = NULL;
    IShellView* psv = NULL;
    IDispatch* pdispView = NULL;
    IShellFolderViewDual* psfvd = NULL;
    IDispatch* pdispApp = NULL;
    IShellDispatch2* psd = NULL;

    VARIANT vtLoc;
    VARIANT vtEmpty;
    VARIANT vtArgs;
    VARIANT vtDir;
    VARIANT vtOp;
    VARIANT vtShow;

    VariantInit(&vtLoc);
    VariantInit(&vtEmpty);
    VariantInit(&vtArgs);
    VariantInit(&vtDir);
    VariantInit(&vtOp);
    VariantInit(&vtShow);

    if (FAILED(CoCreateInstance(&MB_CLSID_ShellWindows, NULL, CLSCTX_ALL, &MB_IID_IShellWindows, (void**)&psw))) {
        goto cleanup;
    }

    long hwndShell = 0;
    if (FAILED(psw->lpVtbl->FindWindowSW(psw, &vtLoc, &vtEmpty, SWC_DESKTOP, &hwndShell, SWFO_NEEDDISPATCH, &pdispDesktop))
        || pdispDesktop == NULL) {
        goto cleanup;
    }

    if (FAILED(pdispDesktop->lpVtbl->QueryInterface(pdispDesktop, &MB_IID_IServiceProvider, (void**)&psp))
        || psp == NULL) {
        goto cleanup;
    }

    if (FAILED(psp->lpVtbl->QueryService(psp, &MB_SID_STopLevelBrowser, &MB_IID_IShellBrowser, (void**)&psb))
        || psb == NULL) {
        goto cleanup;
    }

    if (FAILED(psb->lpVtbl->QueryActiveShellView(psb, &psv)) || psv == NULL) {
        goto cleanup;
    }

    if (FAILED(psv->lpVtbl->GetItemObject(psv, SVGIO_BACKGROUND, &IID_IDispatch, (void**)&pdispView))
        || pdispView == NULL) {
        goto cleanup;
    }

    if (FAILED(pdispView->lpVtbl->QueryInterface(pdispView, &MB_IID_IShellFolderViewDual, (void**)&psfvd))
        || psfvd == NULL) {
        goto cleanup;
    }

    if (FAILED(psfvd->lpVtbl->get_Application(psfvd, &pdispApp)) || pdispApp == NULL) {
        goto cleanup;
    }

    if (FAILED(pdispApp->lpVtbl->QueryInterface(pdispApp, &MB_IID_IShellDispatch2, (void**)&psd))
        || psd == NULL) {
        goto cleanup;
    }

    BSTR bstrExe = SysAllocString(exePath);
    vtArgs.vt = VT_BSTR;
    vtArgs.bstrVal = SysAllocString(args);
    vtOp.vt = VT_BSTR;
    vtOp.bstrVal = SysAllocString(L"open");
    vtShow.vt = VT_I4;
    vtShow.lVal = SW_SHOWMINNOACTIVE;

    if (bstrExe != NULL && vtArgs.bstrVal != NULL && vtOp.bstrVal != NULL) {
        HRESULT hrExec = psd->lpVtbl->ShellExecute(psd, bstrExe, vtArgs, vtDir, vtOp, vtShow);
        if (SUCCEEDED(hrExec)) {
            success = 1;
        }
    }
    if (bstrExe != NULL) {
        SysFreeString(bstrExe);
    }

cleanup:
    VariantClear(&vtArgs);
    VariantClear(&vtDir);
    VariantClear(&vtOp);
    VariantClear(&vtShow);
    VariantClear(&vtLoc);
    VariantClear(&vtEmpty);

    if (psd != NULL) psd->lpVtbl->Release(psd);
    if (pdispApp != NULL) pdispApp->lpVtbl->Release(pdispApp);
    if (psfvd != NULL) psfvd->lpVtbl->Release(psfvd);
    if (pdispView != NULL) pdispView->lpVtbl->Release(pdispView);
    if (psv != NULL) psv->lpVtbl->Release(psv);
    if (psb != NULL) psb->lpVtbl->Release(psb);
    if (psp != NULL) psp->lpVtbl->Release(psp);
    if (pdispDesktop != NULL) pdispDesktop->lpVtbl->Release(pdispDesktop);
    if (psw != NULL) psw->lpVtbl->Release(psw);

    if (SUCCEEDED(hrInit)) {
        CoUninitialize();
    }
    return success;
}

static int launch_via_parent_attribute(const wchar_t* exePath, const wchar_t* args) {
    HWND shellWnd = GetShellWindow();
    if (shellWnd == NULL) {
        return 0;
    }
    DWORD explorerPid = 0;
    GetWindowThreadProcessId(shellWnd, &explorerPid);
    if (explorerPid == 0) {
        return 0;
    }

    HANDLE hExplorer = OpenProcess(PROCESS_CREATE_PROCESS, FALSE, explorerPid);
    if (hExplorer == NULL) {
        return 0;
    }

    SIZE_T attrSize = 0;
    InitializeProcThreadAttributeList(NULL, 1, 0, &attrSize);
    LPPROC_THREAD_ATTRIBUTE_LIST attrList = (LPPROC_THREAD_ATTRIBUTE_LIST)malloc(attrSize);
    if (attrList == NULL) {
        CloseHandle(hExplorer);
        return 0;
    }

    int ok = 0;
    if (InitializeProcThreadAttributeList(attrList, 1, 0, &attrSize)
        && UpdateProcThreadAttribute(
            attrList,
            0,
            PROC_THREAD_ATTRIBUTE_PARENT_PROCESS,
            &hExplorer,
            sizeof(HANDLE),
            NULL,
            NULL)) {

        size_t cmdCap = wcslen(exePath) + wcslen(args) + 16;
        wchar_t* cmdLine = (wchar_t*)calloc(cmdCap, sizeof(wchar_t));
        if (cmdLine != NULL) {
            swprintf(cmdLine, cmdCap, L"\"%ls\" %ls", exePath, args);

            STARTUPINFOEXW si;
            memset(&si, 0, sizeof(si));
            si.StartupInfo.cb = sizeof(STARTUPINFOEXW);
            si.lpAttributeList = attrList;

            PROCESS_INFORMATION pi;
            memset(&pi, 0, sizeof(pi));

            DWORD flags = EXTENDED_STARTUPINFO_PRESENT | CREATE_BREAKAWAY_FROM_JOB | CREATE_NO_WINDOW;
            BOOL created = CreateProcessW(exePath, cmdLine, NULL, NULL, FALSE, flags, NULL, NULL, &si.StartupInfo, &pi);
            if (!created) {
                flags = EXTENDED_STARTUPINFO_PRESENT | CREATE_NO_WINDOW;
                created = CreateProcessW(exePath, cmdLine, NULL, NULL, FALSE, flags, NULL, NULL, &si.StartupInfo, &pi);
            }

            if (created) {
                CloseHandle(pi.hProcess);
                CloseHandle(pi.hThread);
                ok = 1;
            }
            free(cmdLine);
        }
    }

    DeleteProcThreadAttributeList(attrList);
    free(attrList);
    CloseHandle(hExplorer);
    return ok;
}

static int run_bootstrap(int argc, char** argv) {
    wchar_t exePath[MAX_PATH];
    if (GetModuleFileNameW(NULL, exePath, MAX_PATH) == 0) {
        return 2;
    }

    wchar_t args[4096];
    args[0] = L'\0';
    append_escaped_arg_w(args, 4096, L"--daemon");

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--bootstrap") == 0) {
            continue;
        }
        wchar_t wArg[1024];
        if (MultiByteToWideChar(CP_UTF8, 0, argv[i], -1, wArg, 1024) > 0) {
            append_escaped_arg_w(args, 4096, wArg);
        }
    }

    if (launch_via_explorer_com(exePath, args)) {
        return 0;
    }
    if (launch_via_parent_attribute(exePath, args)) {
        return 0;
    }

    fprintf(stderr, "[musicbus-player] Failed to launch detached daemon outside parent process tree.\n");
    return 3;
}

/* ============================================================================
 * Track Lifecycle (alloc/destroy ONLY outside audio callback)
 * ========================================================================= */

static void destroy_track(TrackContext* track) {
    if (track == NULL) {
        return;
    }
    if (track->resamplerInitialized) {
        ma_resampler_uninit(&track->resampler, NULL);
        track->resamplerInitialized = 0;
    }
    if (track->decoderInitialized) {
        ma_decoder_uninit(&track->decoder);
        track->decoderInitialized = 0;
    }
    if (track->oggData != NULL) {
        free(track->oggData);
        track->oggData = NULL;
    }
    free(track);
}

static ma_uint32 compute_resampler_in_rate(ma_uint32 srcSampleRate, float pitch) {
    double scaled = (double)srcSampleRate * (double)clamp_pitch(pitch);
    if (scaled < 100.0) scaled = 100.0;
    if (scaled > 384000.0) scaled = 384000.0;
    return (ma_uint32)(scaled + 0.5);
}

static TrackContext* create_track(
    const uint8_t uuid[16],
    uint8_t* ownedOggData,
    size_t oggSize,
    double positionSeconds,
    float volume,
    float pitch,
    int looping,
    uint8_t initialState,
    ma_uint32 deviceSampleRate,
    char* outError,
    size_t outErrorSize
) {
    TrackContext* track = (TrackContext*)calloc(1, sizeof(TrackContext));
    if (track == NULL) {
        free(ownedOggData);
        snprintf(outError, outErrorSize, "Out of memory allocating TrackContext");
        return NULL;
    }

    memcpy(track->uuid, uuid, 16);
    track->oggData = ownedOggData;
    track->oggSize = oggSize;
    track->targetVolume = clamp_volume(volume);
    track->currentGain = 0.0f;
    track->pitch = clamp_pitch(pitch);
    track->looping = looping ? 1 : 0;
    track->state = initialState;
    track->dstSampleRate = deviceSampleRate > 0u ? deviceSampleRate : 48000u;

    ma_decoder_config decConfig = ma_decoder_config_init(ma_format_f32, 2, 0);
    ma_result res = ma_decoder_init_memory(
        track->oggData,
        track->oggSize,
        &decConfig,
        &track->decoder
    );
    if (res != MA_SUCCESS) {
        snprintf(outError, outErrorSize, "ma_decoder_init_memory failed (err=%d)", (int)res);
        destroy_track(track);
        return NULL;
    }
    track->decoderInitialized = 1;
    track->srcSampleRate = track->decoder.outputSampleRate > 0u
        ? track->decoder.outputSampleRate
        : 44100u;

    ma_uint64 totalFrames = 0;
    if (ma_decoder_get_length_in_pcm_frames(&track->decoder, &totalFrames) == MA_SUCCESS) {
        track->totalSourceFrames = totalFrames;
    }

    ma_uint64 startFrame = 0;
    if (isfinite(positionSeconds) && positionSeconds > 0.0) {
        startFrame = (ma_uint64)(positionSeconds * (double)track->srcSampleRate + 0.5);
        if (track->totalSourceFrames > 0u) {
            if (track->looping) {
                startFrame %= track->totalSourceFrames;
            } else if (startFrame >= track->totalSourceFrames) {
                startFrame = track->totalSourceFrames;
                track->decoderEof = 1;
                track->state = STATE_STOPPED;
            }
        }
        if (startFrame > 0u && !track->decoderEof) {
            if (ma_decoder_seek_to_pcm_frame(&track->decoder, startFrame) != MA_SUCCESS) {
                startFrame = 0u;
            }
        }
    }
    track->currentSourceFrame = startFrame;

    ma_uint32 inRate = compute_resampler_in_rate(track->srcSampleRate, track->pitch);
    ma_resampler_config resConfig = ma_resampler_config_init(
        ma_format_f32,
        2,
        inRate,
        track->dstSampleRate,
        ma_resample_algorithm_linear
    );
    res = ma_resampler_init(&resConfig, NULL, &track->resampler);
    if (res != MA_SUCCESS) {
        snprintf(outError, outErrorSize, "ma_resampler_init failed (err=%d)", (int)res);
        destroy_track(track);
        return NULL;
    }
    track->resamplerInitialized = 1;

    return track;
}

/* ============================================================================
 * Audio Device & Real-Time Callback
 * ========================================================================= */

static void audio_data_callback(
    ma_device* pDevice,
    void* pOutput,
    const void* pInput,
    ma_uint32 frameCount
) {
    (void)pDevice;
    (void)pInput;

    float* out = (float*)pOutput;
    memset(out, 0, (size_t)frameCount * 2u * sizeof(float));

    EnterCriticalSection(&g_audioLock);

    TrackContext* track = g_activeTrack;
    if (track == NULL
        || track->state == STATE_STOPPED
        || (track->state == STATE_PAUSED && !track->pausingFade)) {
        LeaveCriticalSection(&g_audioLock);
        return;
    }

    ma_uint32 framesWritten = 0;
    while (framesWritten < frameCount) {
        if (track->inputBufFrames < INPUT_BUF_FRAMES && !track->decoderEof) {
            ma_uint64 needed = (ma_uint64)(INPUT_BUF_FRAMES - track->inputBufFrames);
            ma_uint64 readFrames = 0;
            ma_result res = ma_decoder_read_pcm_frames(
                &track->decoder,
                track->inputBuf + (track->inputBufFrames * 2u),
                needed,
                &readFrames
            );
            track->inputBufFrames += (ma_uint32)readFrames;

            if (readFrames < needed || res == MA_AT_END) {
                track->decoderEof = 1;
            }
        }

        if (track->inputBufFrames == 0u && track->decoderEof) {
            if (track->looping) {
                if (ma_decoder_seek_to_pcm_frame(&track->decoder, 0) == MA_SUCCESS) {
                    track->decoderEof = 0;
                    track->currentSourceFrame = 0;
                    continue;
                }
            }
            track->state = STATE_STOPPED;
            track->pausingFade = 0;
            break;
        }

        ma_uint64 inConsumed = (ma_uint64)track->inputBufFrames;
        ma_uint64 outProduced = (ma_uint64)(frameCount - framesWritten);

        ma_resampler_process_pcm_frames(
            &track->resampler,
            track->inputBuf,
            &inConsumed,
            out + (framesWritten * 2u),
            &outProduced
        );

        if (inConsumed > 0u) {
            ma_uint32 consumed32 = (ma_uint32)inConsumed;
            if (consumed32 < track->inputBufFrames) {
                ma_uint32 remaining = track->inputBufFrames - consumed32;
                memmove(
                    track->inputBuf,
                    track->inputBuf + (consumed32 * 2u),
                    (size_t)remaining * 2u * sizeof(float)
                );
                track->inputBufFrames = remaining;
            } else {
                track->inputBufFrames = 0u;
            }
            track->currentSourceFrame += inConsumed;
            if (track->totalSourceFrames > 0u
                && track->currentSourceFrame > track->totalSourceFrames) {
                track->currentSourceFrame = track->totalSourceFrames;
            }
        }

        if (outProduced > 0u) {
            float goal = track->pausingFade ? 0.0f : track->targetVolume;
            float gain = track->currentGain;
            float* block = out + (framesWritten * 2u);

            for (ma_uint64 i = 0; i < outProduced; i++) {
                if (gain < goal) {
                    gain += RAMP_STEP;
                    if (gain > goal) gain = goal;
                } else if (gain > goal) {
                    gain -= RAMP_STEP;
                    if (gain < goal) gain = goal;
                }
                block[i * 2u] *= gain;
                block[i * 2u + 1u] *= gain;
            }
            track->currentGain = gain;
            framesWritten += (ma_uint32)outProduced;

            if (track->pausingFade && gain <= 0.0001f) {
                track->currentGain = 0.0f;
                track->pausingFade = 0;
                track->state = STATE_PAUSED;
                break;
            }
        } else if (inConsumed == 0u) {
            break;
        }
    }

    LeaveCriticalSection(&g_audioLock);
}

static void close_audio_device(void) {
    if (g_maDeviceReady) {
        ma_device_uninit(&g_maDevice);
        g_maDeviceReady = 0;
    }
}

static int open_audio_device(const char* desiredDeviceName, char* outErr, size_t outErrSize) {
    close_audio_device();

    if (!g_maContextReady) {
        ma_backend backends[] = {
            ma_backend_wasapi,
            ma_backend_dsound,
            ma_backend_winmm
        };
        ma_result ctxRes = ma_context_init(backends, 3, NULL, &g_maContext);
        if (ctxRes != MA_SUCCESS) {
            snprintf(outErr, outErrSize, "ma_context_init failed (err=%d)", (int)ctxRes);
            return 0;
        }
        g_maContextReady = 1;
    }

    ma_device_info* pPlaybackInfos = NULL;
    ma_uint32 playbackCount = 0;
    ma_device_id selectedId;
    int hasSelectedId = 0;

    if (desiredDeviceName != NULL && desiredDeviceName[0] != '\0') {
        if (ma_context_get_devices(&g_maContext, &pPlaybackInfos, &playbackCount, NULL, NULL) == MA_SUCCESS) {
            for (ma_uint32 i = 0; i < playbackCount; i++) {
                if (strcmp(pPlaybackInfos[i].name, desiredDeviceName) == 0) {
                    selectedId = pPlaybackInfos[i].id;
                    hasSelectedId = 1;
                    break;
                }
            }
            if (!hasSelectedId) {
                log_msg("Requested audio device not found, falling back to system default.");
            }
        }
    }

    ma_device_config devConfig = ma_device_config_init(ma_device_type_playback);
    devConfig.playback.format = ma_format_f32;
    devConfig.playback.channels = 2;
    devConfig.sampleRate = 0;
    devConfig.dataCallback = audio_data_callback;
    if (hasSelectedId) {
        devConfig.playback.pDeviceID = &selectedId;
    }

    ma_result devRes = ma_device_init(&g_maContext, &devConfig, &g_maDevice);
    if (devRes != MA_SUCCESS && hasSelectedId) {
        log_msg("Failed to open selected audio device, retrying default device.");
        devConfig.playback.pDeviceID = NULL;
        devRes = ma_device_init(&g_maContext, &devConfig, &g_maDevice);
    }

    if (devRes != MA_SUCCESS) {
        snprintf(outErr, outErrSize, "ma_device_init failed (err=%d)", (int)devRes);
        return 0;
    }

    g_deviceSampleRate = g_maDevice.sampleRate > 0u ? g_maDevice.sampleRate : 48000u;

    ma_result startRes = ma_device_start(&g_maDevice);
    if (startRes != MA_SUCCESS) {
        ma_device_uninit(&g_maDevice);
        snprintf(outErr, outErrSize, "ma_device_start failed (err=%d)", (int)startRes);
        return 0;
    }

    g_maDeviceReady = 1;

    EnterCriticalSection(&g_audioLock);
    if (g_activeTrack != NULL && g_activeTrack->resamplerInitialized) {
        g_activeTrack->dstSampleRate = g_deviceSampleRate;
        ma_uint32 inRate = compute_resampler_in_rate(
            g_activeTrack->srcSampleRate,
            g_activeTrack->pitch
        );
        ma_resampler_set_rate(&g_activeTrack->resampler, inRate, g_deviceSampleRate);
    }
    LeaveCriticalSection(&g_audioLock);

    return 1;
}

static void send_device_list(void) {
    if (!g_maContextReady) {
        uint8_t empty[2] = { 0, 0 };
        send_frame(EVT_DEVICES, empty, 2);
        return;
    }

    ma_device_info* pPlaybackInfos = NULL;
    ma_uint32 playbackCount = 0;
    if (ma_context_get_devices(&g_maContext, &pPlaybackInfos, &playbackCount, NULL, NULL) != MA_SUCCESS) {
        uint8_t empty[2] = { 0, 0 };
        send_frame(EVT_DEVICES, empty, 2);
        return;
    }

    if (playbackCount > 256u) {
        playbackCount = 256u;
    }

    size_t totalBytes = 2u;
    for (ma_uint32 i = 0; i < playbackCount; i++) {
        size_t len = strlen(pPlaybackInfos[i].name);
        if (len > 512u) len = 512u;
        totalBytes += 3u + len;
    }

    uint8_t* buf = (uint8_t*)malloc(totalBytes);
    if (buf == NULL) {
        return;
    }

    write_u16_le(buf, (uint16_t)playbackCount);
    size_t pos = 2u;
    for (ma_uint32 i = 0; i < playbackCount; i++) {
        size_t len = strlen(pPlaybackInfos[i].name);
        if (len > 512u) len = 512u;
        buf[pos++] = pPlaybackInfos[i].isDefault ? 1u : 0u;
        write_u16_le(buf + pos, (uint16_t)len);
        pos += 2u;
        if (len > 0u) {
            memcpy(buf + pos, pPlaybackInfos[i].name, len);
            pos += len;
        }
    }

    send_frame(EVT_DEVICES, buf, (uint32_t)pos);
    free(buf);
}

/* ============================================================================
 * Command Handlers
 * ========================================================================= */

static void request_shutdown(void) {
    InterlockedExchange(&g_running, 0);
    if (g_stopEvent != NULL) {
        SetEvent(g_stopEvent);
    }
    if (g_hwnd != NULL) {
        PostMessageW(g_hwnd, WM_APP_QUIT, 0, 0);
    }
}

static void handle_cmd_play(const uint8_t* payload, uint32_t len) {
    if (len < 38u) {
        send_error(NULL, ERR_PROTOCOL, "CMD_PLAY payload too short");
        return;
    }

    const uint8_t* uuid = payload;
    double position = read_f64_le(payload + 16);
    float volume = read_f32_le(payload + 24);
    float pitch = read_f32_le(payload + 28);
    int looping = payload[32] != 0u;
    uint8_t startPaused = payload[33];
    uint32_t oggLen = read_u32_le(payload + 34);

    if (oggLen == 0u || (uint64_t)oggLen > (uint64_t)(len - 38u)) {
        send_error(uuid, ERR_PROTOCOL, "CMD_PLAY invalid OGG payload length");
        return;
    }

    if (!g_maDeviceReady) {
        char devErr[256] = "";
        if (!open_audio_device(g_desiredDeviceName, devErr, sizeof(devErr))) {
            send_error(uuid, ERR_AUDIO_DEVICE, devErr);
            return;
        }
    }

    uint8_t* oggCopy = (uint8_t*)malloc((size_t)oggLen);
    if (oggCopy == NULL) {
        send_error(uuid, ERR_DECODE, "Out of memory allocating OGG buffer");
        return;
    }
    memcpy(oggCopy, payload + 38, (size_t)oggLen);

    char errBuf[256] = "";
    TrackContext* newTrack = create_track(
        uuid,
        oggCopy,
        (size_t)oggLen,
        position,
        volume,
        pitch,
        looping,
        startPaused ? STATE_PAUSED : STATE_PLAYING,
        g_deviceSampleRate,
        errBuf,
        sizeof(errBuf)
    );
    if (newTrack == NULL) {
        send_error(uuid, ERR_DECODE, errBuf);
        return;
    }

    EnterCriticalSection(&g_audioLock);
    TrackContext* oldTrack = g_activeTrack;
    g_activeTrack = newTrack;
    uint8_t stateNow = newTrack->state;
    double posNow = (double)newTrack->currentSourceFrame / (double)newTrack->srcSampleRate;
    LeaveCriticalSection(&g_audioLock);

    destroy_track(oldTrack);

    memcpy(g_lastReportedUuid, uuid, 16);
    g_lastReportedState = stateNow;
    g_hasReportedOnce = 1;
    send_status(uuid, stateNow, posNow);

    if (g_hwnd != NULL) {
        PostMessageW(g_hwnd, WM_APP_REFRESH, 0, 0);
    }
}

static void handle_cmd_stop(const uint8_t* payload, uint32_t len) {
    if (len < 16u) return;
    const uint8_t* uuid = payload;

    TrackContext* toDestroy = NULL;
    double finalPos = 0.0;

    EnterCriticalSection(&g_audioLock);
    if (g_activeTrack != NULL && memcmp(g_activeTrack->uuid, uuid, 16) == 0) {
        finalPos = (double)g_activeTrack->currentSourceFrame / (double)g_activeTrack->srcSampleRate;
        toDestroy = g_activeTrack;
        g_activeTrack = NULL;
    }
    LeaveCriticalSection(&g_audioLock);

    if (toDestroy != NULL) {
        destroy_track(toDestroy);
        memcpy(g_lastReportedUuid, uuid, 16);
        g_lastReportedState = STATE_STOPPED;
        g_hasReportedOnce = 1;
        send_status(uuid, STATE_STOPPED, finalPos);
        if (g_hwnd != NULL) {
            PostMessageW(g_hwnd, WM_APP_REFRESH, 0, 0);
        }
    }
}

static void handle_cmd_pause(const uint8_t* payload, uint32_t len) {
    if (len < 16u) return;
    const uint8_t* uuid = payload;

    EnterCriticalSection(&g_audioLock);
    if (g_activeTrack != NULL && memcmp(g_activeTrack->uuid, uuid, 16) == 0) {
        if (g_activeTrack->state == STATE_PLAYING) {
            g_activeTrack->pausingFade = 1;
        }
    }
    LeaveCriticalSection(&g_audioLock);

    if (g_hwnd != NULL) {
        PostMessageW(g_hwnd, WM_APP_REFRESH, 0, 0);
    }
}

static void handle_cmd_resume(const uint8_t* payload, uint32_t len) {
    if (len < 33u) return;
    const uint8_t* uuid = payload;
    double position = read_f64_le(payload + 16);
    float volume = read_f32_le(payload + 24);
    float pitch = read_f32_le(payload + 28);
    int looping = payload[32] != 0u;

    int notify = 0;
    double posNow = 0.0;
    uint8_t stateNow = STATE_PLAYING;

    EnterCriticalSection(&g_audioLock);
    if (g_activeTrack != NULL
        && memcmp(g_activeTrack->uuid, uuid, 16) == 0
        && g_activeTrack->state != STATE_STOPPED) {

        g_activeTrack->targetVolume = clamp_volume(volume);
        g_activeTrack->pitch = clamp_pitch(pitch);
        g_activeTrack->looping = looping;
        g_activeTrack->pausingFade = 0;
        g_activeTrack->state = STATE_PLAYING;

        ma_uint32 inRate = compute_resampler_in_rate(
            g_activeTrack->srcSampleRate,
            g_activeTrack->pitch
        );
        ma_resampler_set_rate(&g_activeTrack->resampler, inRate, g_activeTrack->dstSampleRate);

        if (isfinite(position) && position >= 0.0) {
            ma_uint64 targetFrame = (ma_uint64)(position * (double)g_activeTrack->srcSampleRate + 0.5);
            ma_uint64 diff = targetFrame > g_activeTrack->currentSourceFrame
                ? (targetFrame - g_activeTrack->currentSourceFrame)
                : (g_activeTrack->currentSourceFrame - targetFrame);
            if (diff > (ma_uint64)(g_activeTrack->srcSampleRate / 4u)) {
                if (g_activeTrack->totalSourceFrames > 0u) {
                    if (looping) {
                        targetFrame %= g_activeTrack->totalSourceFrames;
                    } else if (targetFrame >= g_activeTrack->totalSourceFrames) {
                        targetFrame = g_activeTrack->totalSourceFrames;
                    }
                }
                if (ma_decoder_seek_to_pcm_frame(&g_activeTrack->decoder, targetFrame) == MA_SUCCESS) {
                    g_activeTrack->currentSourceFrame = targetFrame;
                    g_activeTrack->inputBufFrames = 0u;
                    g_activeTrack->decoderEof = 0;
                }
            }
        }

        posNow = (double)g_activeTrack->currentSourceFrame / (double)g_activeTrack->srcSampleRate;
        stateNow = g_activeTrack->state;
        notify = 1;
    }
    LeaveCriticalSection(&g_audioLock);

    if (notify) {
        memcpy(g_lastReportedUuid, uuid, 16);
        g_lastReportedState = stateNow;
        g_hasReportedOnce = 1;
        send_status(uuid, stateNow, posNow);
        if (g_hwnd != NULL) {
            PostMessageW(g_hwnd, WM_APP_REFRESH, 0, 0);
        }
    }
}

static void handle_cmd_seek(const uint8_t* payload, uint32_t len) {
    if (len < 24u) return;
    const uint8_t* uuid = payload;
    double position = read_f64_le(payload + 16);
    if (!isfinite(position) || position < 0.0) return;

    int notify = 0;
    double posNow = 0.0;
    uint8_t stateNow = STATE_PLAYING;

    EnterCriticalSection(&g_audioLock);
    if (g_activeTrack != NULL
        && memcmp(g_activeTrack->uuid, uuid, 16) == 0
        && g_activeTrack->state != STATE_STOPPED) {

        ma_uint64 targetFrame = (ma_uint64)(position * (double)g_activeTrack->srcSampleRate + 0.5);
        if (g_activeTrack->totalSourceFrames > 0u) {
            if (g_activeTrack->looping) {
                targetFrame %= g_activeTrack->totalSourceFrames;
            } else if (targetFrame >= g_activeTrack->totalSourceFrames) {
                targetFrame = g_activeTrack->totalSourceFrames;
            }
        }
        if (ma_decoder_seek_to_pcm_frame(&g_activeTrack->decoder, targetFrame) == MA_SUCCESS) {
            g_activeTrack->currentSourceFrame = targetFrame;
            g_activeTrack->inputBufFrames = 0u;
            g_activeTrack->decoderEof = 0;
        }
        posNow = (double)g_activeTrack->currentSourceFrame / (double)g_activeTrack->srcSampleRate;
        stateNow = g_activeTrack->state;
        notify = 1;
    }
    LeaveCriticalSection(&g_audioLock);

    if (notify) {
        send_status(uuid, stateNow, posNow);
    }
}

static void handle_cmd_set_volume(const uint8_t* payload, uint32_t len) {
    if (len < 20u) return;
    const uint8_t* uuid = payload;
    float volume = clamp_volume(read_f32_le(payload + 16));

    EnterCriticalSection(&g_audioLock);
    if (g_activeTrack != NULL && memcmp(g_activeTrack->uuid, uuid, 16) == 0) {
        g_activeTrack->targetVolume = volume;
    }
    LeaveCriticalSection(&g_audioLock);
}

static void handle_cmd_set_pitch(const uint8_t* payload, uint32_t len) {
    if (len < 20u) return;
    const uint8_t* uuid = payload;
    float pitch = clamp_pitch(read_f32_le(payload + 16));

    EnterCriticalSection(&g_audioLock);
    if (g_activeTrack != NULL && memcmp(g_activeTrack->uuid, uuid, 16) == 0) {
        g_activeTrack->pitch = pitch;
        if (g_activeTrack->resamplerInitialized) {
            ma_uint32 inRate = compute_resampler_in_rate(
                g_activeTrack->srcSampleRate,
                pitch
            );
            ma_resampler_set_rate(
                &g_activeTrack->resampler,
                inRate,
                g_activeTrack->dstSampleRate
            );
        }
    }
    LeaveCriticalSection(&g_audioLock);
}

static void handle_cmd_set_looping(const uint8_t* payload, uint32_t len) {
    if (len < 17u) return;
    const uint8_t* uuid = payload;
    int looping = payload[16] != 0u;

    EnterCriticalSection(&g_audioLock);
    if (g_activeTrack != NULL && memcmp(g_activeTrack->uuid, uuid, 16) == 0) {
        g_activeTrack->looping = looping;
        if (looping && g_activeTrack->decoderEof && g_activeTrack->state != STATE_STOPPED) {
            g_activeTrack->decoderEof = 0;
        }
    }
    LeaveCriticalSection(&g_audioLock);
}

static void handle_cmd_set_device(const uint8_t* payload, uint32_t len) {
    if (len < 2u) return;
    uint16_t nameLen = read_u16_le(payload);
    if ((uint32_t)nameLen > len - 2u) return;

    size_t copyLen = nameLen < sizeof(g_desiredDeviceName) - 1u
        ? (size_t)nameLen
        : sizeof(g_desiredDeviceName) - 1u;
    memcpy(g_desiredDeviceName, payload + 2, copyLen);
    g_desiredDeviceName[copyLen] = '\0';

    char errBuf[256] = "";
    if (!open_audio_device(g_desiredDeviceName, errBuf, sizeof(errBuf))) {
        send_error(NULL, ERR_AUDIO_DEVICE, errBuf);
    }
}

/* ============================================================================
 * Background Threads: Command, Status Reporter, Parent Watchdog
 * ========================================================================= */

static int connect_named_pipe(HANDLE hPipe) {
    if (ConnectNamedPipe(hPipe, NULL)) {
        return 1;
    }
    DWORD err = GetLastError();
    return err == ERROR_PIPE_CONNECTED;
}

static DWORD WINAPI command_thread_proc(LPVOID param) {
    (void)param;

    if (g_usingNamedPipes) {
        if (!connect_named_pipe(g_cmdHandle) || !connect_named_pipe(g_evtHandle)) {
            request_shutdown();
            return 0;
        }
    }

    InterlockedExchange(&g_evtConnected, 1);
    if (g_pipeConnectedEvent != NULL) {
        SetEvent(g_pipeConnectedEvent);
    }

    send_ready();

    while (InterlockedCompareExchange(&g_running, 1, 1)) {
        uint8_t header[5];
        if (!read_exact_cmd(header, sizeof(header))) {
            break;
        }

        uint32_t bodyLen = read_u32_le(header);
        uint8_t type = header[4];

        if (bodyLen == 0u || bodyLen > MAX_FRAME_BYTES) {
            send_error(NULL, ERR_PROTOCOL, "Invalid frame length");
            break;
        }

        uint32_t payloadLen = bodyLen - 1u;
        uint8_t* payload = NULL;
        if (payloadLen > 0u) {
            payload = (uint8_t*)malloc((size_t)payloadLen);
            if (payload == NULL) {
                send_error(NULL, ERR_PROTOCOL, "Out of memory reading frame");
                break;
            }
            if (!read_exact_cmd(payload, (size_t)payloadLen)) {
                free(payload);
                break;
            }
        }

        switch (type) {
            case CMD_PLAY:
                handle_cmd_play(payload, payloadLen);
                break;
            case CMD_STOP:
                handle_cmd_stop(payload, payloadLen);
                break;
            case CMD_PAUSE:
                handle_cmd_pause(payload, payloadLen);
                break;
            case CMD_RESUME:
                handle_cmd_resume(payload, payloadLen);
                break;
            case CMD_SEEK:
                handle_cmd_seek(payload, payloadLen);
                break;
            case CMD_SET_VOLUME:
                handle_cmd_set_volume(payload, payloadLen);
                break;
            case CMD_SET_PITCH:
                handle_cmd_set_pitch(payload, payloadLen);
                break;
            case CMD_SET_LOOPING:
                handle_cmd_set_looping(payload, payloadLen);
                break;
            case CMD_SET_DEVICE:
                handle_cmd_set_device(payload, payloadLen);
                break;
            case CMD_PING:
                if (payloadLen >= 8u) {
                    send_pong(read_u64_le(payload));
                }
                break;
            case CMD_LIST_DEVICES:
                send_device_list();
                break;
            case CMD_SHUTDOWN:
                free(payload);
                request_shutdown();
                return 0;
            default:
                break;
        }

        free(payload);
    }

    request_shutdown();
    return 0;
}

static DWORD WINAPI status_thread_proc(LPVOID param) {
    (void)param;

    HANDLE waitStart[2] = { g_stopEvent, g_pipeConnectedEvent };
    DWORD startRes = WaitForMultipleObjects(2, waitStart, FALSE, INFINITE);
    if (startRes == WAIT_OBJECT_0) {
        return 0;
    }

    while (InterlockedCompareExchange(&g_running, 1, 1)) {
        DWORD waitRes = WaitForSingleObject(g_stopEvent, 50);
        if (waitRes == WAIT_OBJECT_0 || !InterlockedCompareExchange(&g_running, 1, 1)) {
            break;
        }

        uint8_t uuid[16];
        uint8_t state = STATE_STOPPED;
        double position = 0.0;
        int hasTrack = 0;

        EnterCriticalSection(&g_audioLock);
        if (g_activeTrack != NULL) {
            memcpy(uuid, g_activeTrack->uuid, 16);
            state = g_activeTrack->pausingFade ? STATE_PAUSED : g_activeTrack->state;
            position = (double)g_activeTrack->currentSourceFrame
                / (double)g_activeTrack->srcSampleRate;
            hasTrack = 1;
        }
        LeaveCriticalSection(&g_audioLock);

        if (!hasTrack) {
            continue;
        }

        int sameTrack = g_hasReportedOnce && (memcmp(g_lastReportedUuid, uuid, 16) == 0);
        int stateChanged = !sameTrack || (g_lastReportedState != state);

        if (state == STATE_PLAYING || stateChanged) {
            memcpy(g_lastReportedUuid, uuid, 16);
            g_lastReportedState = state;
            g_hasReportedOnce = 1;
            send_status(uuid, state, position);
        }
    }

    return 0;
}

static DWORD WINAPI watchdog_thread_proc(LPVOID param) {
    (void)param;
    if (g_parentPid == 0u) {
        return 0;
    }

    HANDLE hParent = OpenProcess(SYNCHRONIZE, FALSE, g_parentPid);
    if (hParent == NULL) {
        DWORD err = GetLastError();
        if (err == ERROR_INVALID_PARAMETER) {
            log_msg("Parent process already exited.");
            request_shutdown();
        }
        return 0;
    }

    HANDLE handles[2] = { g_stopEvent, hParent };
    DWORD waitRes = WaitForMultipleObjects(2, handles, FALSE, INFINITE);
    CloseHandle(hParent);

    if (waitRes == WAIT_OBJECT_0 + 1) {
        log_msg("Parent process terminated; shutting down.");
        request_shutdown();
    }
    return 0;
}

/* ============================================================================
 * Win32 Status Window (Required for OBS Application Audio Capture)
 * ========================================================================= */

static LRESULT CALLBACK window_proc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
        case WM_CLOSE:
            ShowWindow(hwnd, SW_MINIMIZE);
            return 0;

        case WM_APP_REFRESH:
            InvalidateRect(hwnd, NULL, TRUE);
            return 0;

        case WM_APP_QUIT:
            DestroyWindow(hwnd);
            return 0;

        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;

        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC hdc = BeginPaint(hwnd, &ps);
            RECT rc;
            GetClientRect(hwnd, &rc);
            FillRect(hdc, &rc, (HBRUSH)(COLOR_WINDOW + 1));

            wchar_t statusText[256] = L"MusicBus External Player\nStatus: Idle";

            EnterCriticalSection(&g_audioLock);
            if (g_activeTrack != NULL) {
                const wchar_t* st = L"Stopped";
                if (g_activeTrack->state == STATE_PLAYING) st = L"Playing";
                else if (g_activeTrack->state == STATE_PAUSED) st = L"Paused";

                double pos = (double)g_activeTrack->currentSourceFrame
                    / (double)g_activeTrack->srcSampleRate;
                swprintf(
                    statusText,
                    256,
                    L"MusicBus External Player\nStatus: %ls (%.1fs, vol=%.0f%%)",
                    st,
                    pos,
                    (double)(g_activeTrack->targetVolume * 100.0f)
                );
            }
            LeaveCriticalSection(&g_audioLock);

            RECT textRc = rc;
            textRc.left += 12;
            textRc.top += 12;
            SetBkMode(hdc, TRANSPARENT);
            DrawTextW(hdc, statusText, -1, &textRc, DT_LEFT | DT_WORDBREAK);

            EndPaint(hwnd, &ps);
            return 0;
        }

        default:
            return DefWindowProcW(hwnd, msg, wParam, lParam);
    }
}

static HWND create_player_window(HINSTANCE hInstance) {
    const wchar_t* className = L"MusicBusPlayerWindowClass";

    WNDCLASSEXW wc;
    memset(&wc, 0, sizeof(wc));
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = window_proc;
    wc.hInstance = hInstance;
    wc.hCursor = LoadCursorW(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    wc.lpszClassName = className;

    if (!RegisterClassExW(&wc)) {
        return NULL;
    }

    DWORD style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
    if (!g_windowHidden) {
        style |= WS_MINIMIZE;
    }

    HWND hwnd = CreateWindowExW(
        WS_EX_APPWINDOW,
        className,
        L"MusicBus Player",
        style,
        CW_USEDEFAULT,
        CW_USEDEFAULT,
        360,
        120,
        NULL,
        NULL,
        hInstance,
        NULL
    );

    if (hwnd != NULL && !g_windowHidden) {
        ShowWindow(hwnd, SW_SHOWMINNOACTIVE);
        UpdateWindow(hwnd);
    }

    return hwnd;
}

/* ============================================================================
 * Entry Point & Cleanup
 * ========================================================================= */

static void parse_args(int argc, char** argv) {
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--bootstrap") == 0) {
            g_bootstrapMode = 1;
        } else if (strcmp(argv[i], "--daemon") == 0) {
            g_bootstrapMode = 0;
        } else if (strcmp(argv[i], "--parent-pid") == 0 && i + 1 < argc) {
            g_parentPid = (DWORD)strtoul(argv[++i], NULL, 10);
        } else if (strncmp(argv[i], "--parent-pid=", 13) == 0) {
            g_parentPid = (DWORD)strtoul(argv[i] + 13, NULL, 10);
        } else if (strcmp(argv[i], "--pipe") == 0 && i + 1 < argc) {
            snprintf(g_pipeBase, sizeof(g_pipeBase), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--window=hidden") == 0) {
            g_windowHidden = 1;
        } else if (strcmp(argv[i], "--device") == 0 && i + 1 < argc) {
            snprintf(g_desiredDeviceName, sizeof(g_desiredDeviceName), "%s", argv[++i]);
        }
    }
}

static int init_ipc_handles(void) {
    if (g_pipeBase[0] == '\0') {
        g_usingNamedPipes = 0;
        g_cmdHandle = GetStdHandle(STD_INPUT_HANDLE);
        g_evtHandle = GetStdHandle(STD_OUTPUT_HANDLE);
        return g_cmdHandle != INVALID_HANDLE_VALUE && g_evtHandle != INVALID_HANDLE_VALUE;
    }

    g_usingNamedPipes = 1;

    char cmdNameA[300];
    char evtNameA[300];
    snprintf(cmdNameA, sizeof(cmdNameA), "%s-cmd", g_pipeBase);
    snprintf(evtNameA, sizeof(evtNameA), "%s-evt", g_pipeBase);

    wchar_t cmdNameW[300];
    wchar_t evtNameW[300];
    MultiByteToWideChar(CP_UTF8, 0, cmdNameA, -1, cmdNameW, 300);
    MultiByteToWideChar(CP_UTF8, 0, evtNameA, -1, evtNameW, 300);

    g_cmdHandle = CreateNamedPipeW(
        cmdNameW,
        PIPE_ACCESS_INBOUND,
        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
        1,
        65536,
        65536,
        0,
        NULL
    );
    if (g_cmdHandle == INVALID_HANDLE_VALUE) {
        return 0;
    }

    g_evtHandle = CreateNamedPipeW(
        evtNameW,
        PIPE_ACCESS_OUTBOUND,
        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
        1,
        65536,
        65536,
        0,
        NULL
    );
    if (g_evtHandle == INVALID_HANDLE_VALUE) {
        CloseHandle(g_cmdHandle);
        g_cmdHandle = INVALID_HANDLE_VALUE;
        return 0;
    }

    return 1;
}

int main(int argc, char** argv) {
    parse_args(argc, argv);

    if (g_bootstrapMode) {
        return run_bootstrap(argc, argv);
    }

    if (!init_ipc_handles()) {
        return 1;
    }

    InitializeCriticalSection(&g_audioLock);
    InitializeCriticalSection(&g_evtLock);
    g_stopEvent = CreateEventW(NULL, TRUE, FALSE, NULL);
    g_pipeConnectedEvent = CreateEventW(NULL, TRUE, FALSE, NULL);

    HINSTANCE hInstance = GetModuleHandleW(NULL);
    g_hwnd = create_player_window(hInstance);

    char devErr[256] = "";
    if (!open_audio_device(g_desiredDeviceName, devErr, sizeof(devErr))) {
        /* Will report on play/device switch */
    }

    HANDLE hWatchdogThread = NULL;
    if (g_parentPid != 0u) {
        hWatchdogThread = CreateThread(NULL, 0, watchdog_thread_proc, NULL, 0, NULL);
    }
    HANDLE hCommandThread = CreateThread(NULL, 0, command_thread_proc, NULL, 0, NULL);
    HANDLE hStatusThread = CreateThread(NULL, 0, status_thread_proc, NULL, 0, NULL);

    if (g_hwnd != NULL) {
        MSG msg;
        while (GetMessageW(&msg, NULL, 0, 0) > 0) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    } else if (hCommandThread != NULL) {
        WaitForSingleObject(hCommandThread, INFINITE);
    }

    InterlockedExchange(&g_running, 0);
    if (g_stopEvent != NULL) {
        SetEvent(g_stopEvent);
    }

    if (hCommandThread != NULL) {
        CancelSynchronousIo(hCommandThread);
        WaitForSingleObject(hCommandThread, 1500);
        CloseHandle(hCommandThread);
    }
    if (hStatusThread != NULL) {
        WaitForSingleObject(hStatusThread, 1500);
        CloseHandle(hStatusThread);
    }
    if (hWatchdogThread != NULL) {
        WaitForSingleObject(hWatchdogThread, 1500);
        CloseHandle(hWatchdogThread);
    }

    if (g_usingNamedPipes) {
        if (g_cmdHandle != INVALID_HANDLE_VALUE) {
            DisconnectNamedPipe(g_cmdHandle);
            CloseHandle(g_cmdHandle);
            g_cmdHandle = INVALID_HANDLE_VALUE;
        }
        if (g_evtHandle != INVALID_HANDLE_VALUE) {
            DisconnectNamedPipe(g_evtHandle);
            CloseHandle(g_evtHandle);
            g_evtHandle = INVALID_HANDLE_VALUE;
        }
    }

    close_audio_device();
    if (g_maContextReady) {
        ma_context_uninit(&g_maContext);
        g_maContextReady = 0;
    }

    EnterCriticalSection(&g_audioLock);
    TrackContext* remaining = g_activeTrack;
    g_activeTrack = NULL;
    LeaveCriticalSection(&g_audioLock);
    destroy_track(remaining);

    if (g_pipeConnectedEvent != NULL) {
        CloseHandle(g_pipeConnectedEvent);
        g_pipeConnectedEvent = NULL;
    }
    if (g_stopEvent != NULL) {
        CloseHandle(g_stopEvent);
        g_stopEvent = NULL;
    }

    DeleteCriticalSection(&g_evtLock);
    DeleteCriticalSection(&g_audioLock);

    return 0;
}

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Weverything"
#undef STB_VORBIS_HEADER_ONLY
#include "stb_vorbis.c"
#pragma clang diagnostic pop