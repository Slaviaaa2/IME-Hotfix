package jp.antimeme.mc.imehotfix.core.win32;

import com.sun.jna.Memory;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import jp.antimeme.mc.imehotfix.core.ImeBackend;
import jp.antimeme.mc.imehotfix.core.ImeOptions;
import jp.antimeme.mc.imehotfix.core.ImePreedit;
import jp.antimeme.mc.imehotfix.core.ImeSupport;

/**
 * Windows IMM32 backend.
 *
 * <p>GLFW 3.3.1 — the version Minecraft 1.20.1 ships — has no input-method handling whatsoever:
 * its window procedure ignores every {@code WM_IME_*} message and hands them to
 * {@code DefWindowProc}, so the composition string never reaches the application and Windows
 * paints its own composition window over the framebuffer. This backend subclasses that window
 * procedure to read the composition string directly from IMM32, suppress the system-drawn
 * composition window, and keep the candidate list anchored to the caret.</p>
 *
 * <p>Everything runs on the game's main thread: window procedures are dispatched synchronously
 * from inside {@code glfwPollEvents}, which Minecraft calls from its render loop.</p>
 */
public final class Win32ImeBackend implements ImeBackend {

    private WinDef.HWND hwnd;
    private Pointer previousWndProc;

    /**
     * Strong reference to the callback. If this were collected the native window procedure
     * pointer would dangle and the process would crash on the next message.
     */
    private WinUser.WindowProc hook;

    /** Input context parked while the IME is detached from the window. */
    private WinNT.HANDLE parkedContext;

    private volatile boolean attached;
    private volatile boolean inputActive;
    private volatile boolean composing;

    /** Re-entry guard for {@link #applyCaretToIme()}; see the note on that method. */
    private boolean applyingCaret;

    private volatile ImePreedit preedit = ImePreedit.EMPTY;

    private volatile int caretX;
    private volatile int caretY;
    private volatile int caretWidth = 1;
    private volatile int caretHeight = 12;

    @Override
    public synchronized boolean attach(long nativeWindowHandle) {
        if (this.attached) {
            return true;
        }
        if (!Platform.isWindows()) {
            ImeSupport.logger().warn("Win32 IME backend requested on a non-Windows platform", null);
            return false;
        }
        if (!Platform.is64Bit()) {
            // SetWindowLongPtrW does not exist in 32-bit user32; a 32-bit port would need
            // SetWindowLongW instead. Minecraft 1.20.1 requires a 64-bit JVM anyway.
            ImeSupport.logger().warn("Win32 IME backend needs a 64-bit JVM; staying inactive", null);
            return false;
        }

        this.hwnd = new WinDef.HWND(new Pointer(nativeWindowHandle));
        this.hook = new WinUser.WindowProc() {
            @Override
            public WinDef.LRESULT callback(WinDef.HWND wnd, int msg, WinDef.WPARAM wParam,
                                           WinDef.LPARAM lParam) {
                return windowProc(wnd, msg, wParam, lParam);
            }
        };

        Pointer previous = User32Ex.INSTANCE
                .SetWindowLongPtrW(this.hwnd, WinIme.GWLP_WNDPROC, this.hook);
        if (previous == null) {
            ImeSupport.logger().warn("SetWindowLongPtrW did not return the previous window "
                    + "procedure; refusing to hook", null);
            this.hook = null;
            this.hwnd = null;
            return false;
        }

        this.previousWndProc = previous;
        this.attached = true;

        // Start detached: while no text field is focused the IME must not swallow gameplay keys.
        this.inputActive = true;
        setInputActive(false);
        return true;
    }

    @Override
    public synchronized void detach() {
        if (!this.attached) {
            return;
        }
        this.attached = false;

        // Give the window its input context back before unhooking, otherwise the IME would stay
        // dead for whatever runs next in this process.
        restoreInputContext();

        if (this.previousWndProc != null && this.hwnd != null) {
            User32Ex.INSTANCE.SetWindowLongPtrW(this.hwnd, WinIme.GWLP_WNDPROC, this.previousWndProc);
        }

        this.previousWndProc = null;
        this.hook = null;
        this.hwnd = null;
        this.parkedContext = null;
        this.composing = false;
        this.preedit = ImePreedit.EMPTY;
        this.inputActive = false;
    }

    @Override
    public boolean isAttached() {
        return this.attached;
    }

    @Override
    public synchronized void setInputActive(boolean active) {
        if (!this.attached || active == this.inputActive) {
            return;
        }
        this.inputActive = active;

        // Independent of whether the IME gets detached: an abandoned composition must not be
        // allowed to commit into whatever gains focus next.
        if (!active && this.composing && options().cancelCompositionOnFocusLoss) {
            cancelComposition();
        }

        if (!options().disableImeOutsideTextFields) {
            return;
        }

        if (active) {
            restoreInputContext();
        } else {
            WinNT.HANDLE previous = Imm32.INSTANCE.ImmAssociateContext(this.hwnd, null);
            if (previous != null) {
                this.parkedContext = previous;
            }
            this.composing = false;
            this.preedit = ImePreedit.EMPTY;
        }
    }

    @Override
    public boolean isInputActive() {
        return this.inputActive;
    }

    @Override
    public ImePreedit preedit() {
        return this.preedit;
    }

    @Override
    public boolean isComposing() {
        return this.composing;
    }

    @Override
    public void setCaretRect(int x, int y, int width, int height) {
        if (x == this.caretX && y == this.caretY
                && width == this.caretWidth && height == this.caretHeight) {
            return;
        }
        this.caretX = x;
        this.caretY = y;
        this.caretWidth = Math.max(width, 1);
        this.caretHeight = Math.max(height, 1);

        if (this.attached && this.composing) {
            applyCaretToIme();
        }
    }

    @Override
    public void cancelComposition() {
        if (!this.attached) {
            return;
        }
        WinNT.HANDLE context = Imm32.INSTANCE.ImmGetContext(this.hwnd);
        if (context != null) {
            try {
                Imm32.INSTANCE.ImmNotifyIME(context, WinIme.NI_COMPOSITIONSTR, WinIme.CPS_CANCEL, 0);
            } finally {
                Imm32.INSTANCE.ImmReleaseContext(this.hwnd, context);
            }
        }
        this.composing = false;
        this.preedit = ImePreedit.EMPTY;
    }

    @Override
    public String describe() {
        return "Windows IMM32";
    }

    // ------------------------------------------------------------------------------------
    // window procedure
    // ------------------------------------------------------------------------------------

    private WinDef.LRESULT windowProc(WinDef.HWND wnd, int msg, WinDef.WPARAM wParam,
                                      WinDef.LPARAM lParam) {
        WinDef.LPARAM forwarded = lParam;
        try {
            switch (msg) {
                case WinIme.WM_IME_SETCONTEXT:
                    if (drawsPreeditOurselves()) {
                        // Asks the IME not to show its own composition window. On its own this is
                        // not enough — MS-IME ignores it — which is why WM_IME_STARTCOMPOSITION is
                        // also withheld below. Kept because it is the documented way to say
                        // "the application draws the composition itself".
                        long masked = lParam.longValue() & ~WinIme.ISC_SHOWUICOMPOSITIONWINDOW;
                        forwarded = new WinDef.LPARAM(masked);
                    }
                    break;

                case WinIme.WM_IME_STARTCOMPOSITION:
                    this.composing = true;
                    this.preedit = ImePreedit.EMPTY;
                    applyCaretToIme();
                    trace("WM_IME_STARTCOMPOSITION");
                    if (drawsPreeditOurselves()) {
                        // Per the WM_IME_STARTCOMPOSITION contract: an application that draws the
                        // composition characters itself must handle this message rather than pass
                        // it to the IME window. Forwarding it is what made Windows paint its own
                        // copy of the preedit alongside ours.
                        return new WinDef.LRESULT(0);
                    }
                    break;

                case WinIme.WM_IME_COMPOSITION: {
                    readComposition();
                    applyCaretToIme();
                    trace("WM_IME_COMPOSITION " + this.preedit);
                    if (drawsPreeditOurselves()) {
                        long remaining = lParam.longValue() & ~WinIme.GCS_DISPLAY_BITS;
                        if (remaining == 0L) {
                            // Nothing but preedit drawing was requested, and we draw it.
                            return new WinDef.LRESULT(0);
                        }
                        // Something else is in flight (a result string, typically). Let it
                        // through, minus the bits that would redraw the composition.
                        forwarded = new WinDef.LPARAM(remaining);
                    }
                    break;
                }

                case WinIme.WM_IME_ENDCOMPOSITION:
                    this.composing = false;
                    this.preedit = ImePreedit.EMPTY;
                    trace("WM_IME_ENDCOMPOSITION");
                    if (drawsPreeditOurselves()) {
                        return new WinDef.LRESULT(0);
                    }
                    break;

                case WinIme.WM_IME_NOTIFY: {
                    int command = wParam.intValue();
                    // Only react to the IME opening or changing its candidate list. Deliberately
                    // NOT to IMN_SETCOMPOSITIONWINDOW / IMN_SETCANDIDATEPOS: those are the
                    // notifications ImmSetCompositionWindow and ImmSetCandidateWindow raise
                    // themselves, so answering them by calling those functions again is an
                    // infinite message loop that hangs the game.
                    if (command == WinIme.IMN_OPENCANDIDATE
                            || command == WinIme.IMN_CHANGECANDIDATE) {
                        applyCaretToIme();
                    }
                    break;
                }

                default:
                    break;
            }
        } catch (Throwable error) {
            // A window procedure must never let an exception unwind into native code.
            ImeSupport.logger().warn(
                    "IME window procedure failed on message 0x" + Integer.toHexString(msg), error);
        }

        // Anything not returned above still reaches GLFW's original procedure. Only the bits that
        // would make the IME redraw the composition are ever stripped; the result-string bits are
        // left alone, so the commit path (WM_IME_CHAR -> WM_CHAR -> GLFW char callback ->
        // EditBox.charTyped) is exactly the vanilla one.
        return callPrevious(wnd, msg, wParam, forwarded);
    }

    private WinDef.LRESULT callPrevious(WinDef.HWND wnd, int msg, WinDef.WPARAM wParam,
                                        WinDef.LPARAM lParam) {
        Pointer previous = this.previousWndProc;
        if (previous == null) {
            return new WinDef.LRESULT(0);
        }
        return User32Ex.INSTANCE.CallWindowProcW(previous, wnd, msg, wParam, lParam);
    }

    // ------------------------------------------------------------------------------------
    // IMM32 plumbing
    // ------------------------------------------------------------------------------------

    private void readComposition() {
        WinNT.HANDLE context = Imm32.INSTANCE.ImmGetContext(this.hwnd);
        if (context == null) {
            this.preedit = ImePreedit.EMPTY;
            return;
        }
        try {
            String text = readString(context, WinIme.GCS_COMPSTR);
            if (text.isEmpty()) {
                this.preedit = ImePreedit.EMPTY;
                return;
            }
            // For GCS_CURSORPOS the return value *is* the caret offset, so no buffer is needed.
            int caret = Imm32.INSTANCE
                    .ImmGetCompositionStringW(context, WinIme.GCS_CURSORPOS, null, 0);
            byte[] attributes = readBytes(context, WinIme.GCS_COMPATTR);
            int[] clauses = readInts(context, WinIme.GCS_COMPCLAUSE);
            this.preedit = new ImePreedit(text, Math.max(caret, 0), attributes, clauses);
        } finally {
            Imm32.INSTANCE.ImmReleaseContext(this.hwnd, context);
        }
    }

    /**
     * Points the IME's composition and candidate windows at the caret.
     *
     * <p>Both IMM calls below raise notifications of their own, and an IME is free to answer them
     * with further messages, so this is guarded three ways: it is a no-op outside an active
     * composition, it refuses to re-enter itself, and the notifications it would otherwise
     * re-trigger on are not subscribed to.</p>
     */
    private void applyCaretToIme() {
        if (!this.attached || !this.composing || this.applyingCaret) {
            return;
        }
        if (!options().pinCandidateWindowToCaret) {
            return;
        }
        WinNT.HANDLE context = Imm32.INSTANCE.ImmGetContext(this.hwnd);
        if (context == null) {
            return;
        }
        this.applyingCaret = true;
        try {
            CompositionForm composition = new CompositionForm();
            composition.dwStyle = WinIme.CFS_POINT;
            composition.ptCurrentPos.x = this.caretX;
            composition.ptCurrentPos.y = this.caretY;
            Imm32.INSTANCE.ImmSetCompositionWindow(context, composition);

            // CFS_EXCLUDE lets the IME place the candidate list clear of the caret rectangle
            // instead of covering the text being typed.
            CandidateForm candidate = new CandidateForm();
            candidate.dwIndex = 0;
            candidate.dwStyle = WinIme.CFS_EXCLUDE;
            candidate.ptCurrentPos.x = this.caretX;
            candidate.ptCurrentPos.y = this.caretY + this.caretHeight;
            candidate.rcArea.left = this.caretX;
            candidate.rcArea.top = this.caretY;
            candidate.rcArea.right = this.caretX + this.caretWidth;
            candidate.rcArea.bottom = this.caretY + this.caretHeight;
            Imm32.INSTANCE.ImmSetCandidateWindow(context, candidate);
        } finally {
            this.applyingCaret = false;
            Imm32.INSTANCE.ImmReleaseContext(this.hwnd, context);
        }
    }

    private void restoreInputContext() {
        if (this.hwnd == null) {
            return;
        }
        if (this.parkedContext != null) {
            Imm32.INSTANCE.ImmAssociateContext(this.hwnd, this.parkedContext);
            this.parkedContext = null;
        } else {
            Imm32.INSTANCE.ImmAssociateContextEx(this.hwnd, null, WinIme.IACE_DEFAULT);
        }
    }

    private static String readString(WinNT.HANDLE context, int index) {
        int bytes = Imm32.INSTANCE.ImmGetCompositionStringW(context, index, null, 0);
        if (bytes <= 0) {
            return "";
        }
        Memory buffer = new Memory(bytes);
        try {
            int written = Imm32.INSTANCE.ImmGetCompositionStringW(context, index, buffer, bytes);
            if (written <= 0) {
                return "";
            }
            return new String(buffer.getCharArray(0, written / 2));
        } finally {
            buffer.close();
        }
    }

    private static byte[] readBytes(WinNT.HANDLE context, int index) {
        int bytes = Imm32.INSTANCE.ImmGetCompositionStringW(context, index, null, 0);
        if (bytes <= 0) {
            return new byte[0];
        }
        Memory buffer = new Memory(bytes);
        try {
            int written = Imm32.INSTANCE.ImmGetCompositionStringW(context, index, buffer, bytes);
            if (written <= 0) {
                return new byte[0];
            }
            return buffer.getByteArray(0, written);
        } finally {
            buffer.close();
        }
    }

    private static int[] readInts(WinNT.HANDLE context, int index) {
        int bytes = Imm32.INSTANCE.ImmGetCompositionStringW(context, index, null, 0);
        if (bytes < 4) {
            return new int[0];
        }
        Memory buffer = new Memory(bytes);
        try {
            int written = Imm32.INSTANCE.ImmGetCompositionStringW(context, index, buffer, bytes);
            if (written < 4) {
                return new int[0];
            }
            return buffer.getIntArray(0, written / 4);
        } finally {
            buffer.close();
        }
    }

    private static ImeOptions options() {
        return ImeSupport.options();
    }

    /**
     * Whether this mod, rather than Windows, is showing the composition string.
     *
     * <p>Both switches have to agree. Suppressing the system composition window while not drawing
     * the preedit ourselves would leave the player typing blind — worse than vanilla — so
     * {@code inlinePreedit=false} also hands the drawing back to Windows.</p>
     */
    private static boolean drawsPreeditOurselves() {
        ImeOptions options = options();
        return options.suppressSystemCompositionWindow && options.inlinePreedit;
    }

    private static void trace(String message) {
        if (ImeSupport.options().verboseLogging) {
            ImeSupport.logger().debug(message);
        }
    }
}
