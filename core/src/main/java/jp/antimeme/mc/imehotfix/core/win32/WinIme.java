package jp.antimeme.mc.imehotfix.core.win32;

/**
 * Win32 constants used by the IME hook.
 *
 * <p>Every value here was read out of the installed Windows SDK headers
 * (10.0.26100.0: {@code um/imm.h} and {@code um/winuser.h}) rather than written from memory.</p>
 */
final class WinIme {

    private WinIme() {
    }

    // ---- window messages (winuser.h) ----------------------------------------------------

    static final int WM_IME_STARTCOMPOSITION = 0x010D;
    static final int WM_IME_ENDCOMPOSITION = 0x010E;
    static final int WM_IME_COMPOSITION = 0x010F;
    static final int WM_IME_SETCONTEXT = 0x0281;
    static final int WM_IME_NOTIFY = 0x0282;

    /** {@code SetWindowLongPtr} index for the window procedure. */
    static final int GWLP_WNDPROC = -4;

    // ---- WM_IME_SETCONTEXT lParam flags (imm.h) -----------------------------------------

    /**
     * Clearing this bit tells Windows not to draw the default composition window; the mod draws
     * the preedit inside the text field instead. 0x80000000 does not fit a positive int, so it
     * is kept as a long for masking against the 64-bit lParam.
     */
    static final long ISC_SHOWUICOMPOSITIONWINDOW = 0x80000000L;

    // ---- IMN_* notifications, WM_IME_NOTIFY wParam (imm.h) ------------------------------

    static final int IMN_CHANGECANDIDATE = 0x0003;
    static final int IMN_OPENCANDIDATE = 0x0005;

    /**
     * Listed for documentation only — never subscribe to these. {@code ImmSetCandidateWindow}
     * raises IMN_SETCANDIDATEPOS and {@code ImmSetCompositionWindow} raises
     * IMN_SETCOMPOSITIONWINDOW, so handling them by calling those functions again loops forever
     * and hangs the game.
     */
    static final int IMN_SETCANDIDATEPOS = 0x0009;
    static final int IMN_SETCOMPOSITIONWINDOW = 0x000B;

    // ---- ImmGetCompositionString indices (imm.h) ----------------------------------------

    static final int GCS_COMPREADSTR = 0x0001;
    static final int GCS_COMPREADATTR = 0x0002;
    static final int GCS_COMPREADCLAUSE = 0x0004;
    static final int GCS_COMPSTR = 0x0008;
    static final int GCS_COMPATTR = 0x0010;
    static final int GCS_COMPCLAUSE = 0x0020;
    static final int GCS_CURSORPOS = 0x0080;
    static final int GCS_DELTASTART = 0x0100;
    static final int GCS_RESULTSTR = 0x0800;

    /**
     * The {@code WM_IME_COMPOSITION} lParam bits that concern drawing the composition string.
     * Stripping them before the message reaches the IME window is what stops Windows from
     * painting its own copy of the preedit next to ours; the result-string bits are left alone so
     * the commit path is untouched.
     */
    static final long GCS_DISPLAY_BITS = GCS_COMPREADSTR | GCS_COMPREADATTR | GCS_COMPREADCLAUSE
            | GCS_COMPSTR | GCS_COMPATTR | GCS_COMPCLAUSE | GCS_CURSORPOS | GCS_DELTASTART;

    // ---- composition / candidate window placement styles (imm.h) ------------------------

    static final int CFS_POINT = 0x0002;
    static final int CFS_EXCLUDE = 0x0080;

    // ---- ImmNotifyIME actions (imm.h) ---------------------------------------------------

    static final int NI_COMPOSITIONSTR = 0x0015;
    static final int CPS_CANCEL = 0x0004;

    // ---- ImmAssociateContextEx flags (imm.h) --------------------------------------------

    static final int IACE_DEFAULT = 0x0010;
}
