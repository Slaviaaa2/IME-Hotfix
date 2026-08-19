package jp.antimeme.mc.imehotfix.core.win32;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Direct bindings for the handful of {@code imm32.dll} entry points this mod needs.
 *
 * <p>No function mapper is installed on purpose: {@code ImmSetCompositionWindow} and
 * {@code ImmSetCandidateWindow} have no {@code W} variant, so the usual
 * {@code W32APIOptions.UNICODE_OPTIONS} mapper would look up names that do not exist. Each method
 * below is spelled exactly as the DLL exports it; all of them were verified present on this
 * machine before being declared.</p>
 */
interface Imm32 extends StdCallLibrary {

    Imm32 INSTANCE = Native.load("imm32", Imm32.class);

    /** @return the input context for the window, or {@code null} when it has none */
    WinNT.HANDLE ImmGetContext(WinDef.HWND hWnd);

    boolean ImmReleaseContext(WinDef.HWND hWnd, WinNT.HANDLE hIMC);

    /** @return the input context that was associated before this call */
    WinNT.HANDLE ImmAssociateContext(WinDef.HWND hWnd, WinNT.HANDLE hIMC);

    boolean ImmAssociateContextEx(WinDef.HWND hWnd, WinNT.HANDLE hIMC, int dwFlags);

    /**
     * @param lpBuf    destination, or {@code null} to query the required size
     * @param dwBufLen size of {@code lpBuf} in bytes
     * @return byte count (or, for {@code GCS_CURSORPOS}, the caret offset); negative on error
     */
    int ImmGetCompositionStringW(WinNT.HANDLE hIMC, int dwIndex, Pointer lpBuf, int dwBufLen);

    boolean ImmSetCompositionWindow(WinNT.HANDLE hIMC, CompositionForm lpCompForm);

    boolean ImmSetCandidateWindow(WinNT.HANDLE hIMC, CandidateForm lpCandidate);

    boolean ImmNotifyIME(WinNT.HANDLE hIMC, int dwAction, int dwIndex, int dwValue);
}
