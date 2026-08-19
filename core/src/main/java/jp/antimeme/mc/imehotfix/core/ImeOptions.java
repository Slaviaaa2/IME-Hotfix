package jp.antimeme.mc.imehotfix.core;

/**
 * Runtime switches shared by every port. Fields are written from the game thread when a config
 * is loaded or reloaded and read from the window procedure, hence {@code volatile}.
 */
public final class ImeOptions {

    /**
     * Keep the IME detached from the window while no text field wants input.
     *
     * <p>This is what stops a left-on Japanese IME from swallowing WASD: while the IME owns the
     * keyboard, Windows reports every key as {@code VK_PROCESSKEY}, which GLFW 3.3.1 discards
     * outright ({@code translateKey} in {@code win32_window.c} returns {@code _GLFW_KEY_INVALID}),
     * so the game never sees the keystroke at all.</p>
     */
    public volatile boolean disableImeOutsideTextFields = true;

    /**
     * Hide the system composition window by clearing {@code ISC_SHOWUICOMPOSITIONWINDOW} from
     * {@code WM_IME_SETCONTEXT}. The preedit is drawn inside the text field instead.
     */
    public volatile boolean suppressSystemCompositionWindow = true;

    /** Draw the composition string inline, at the caret, instead of only as a floating overlay. */
    public volatile boolean inlinePreedit = true;

    /** Keep the candidate list anchored under the caret via {@code ImmSetCandidateWindow}. */
    public volatile boolean pinCandidateWindowToCaret = true;

    /**
     * Let searches and suggestions react to the composition before it is confirmed, so typing
     * "ダーク" already narrows the list to dark oak instead of waiting for the conversion.
     *
     * <p>The field's stored value is never touched; it only *reports* the composition as part of
     * its value while one is in flight, and a synthetic character event is dispatched so screens
     * that poll for changes notice.</p>
     */
    public volatile boolean filterWithComposition = true;

    /**
     * When focus leaves a text field mid-composition — the player clicks an item, tabs away, or
     * clicks outside the field — commit what is on screen into the field being left.
     *
     * <p>With this off the composition simply stays alive, which lets it follow the caret into
     * whatever gains focus next: type into JEI's search box, click away, and the half-finished
     * text reappears in the creative inventory's search field.</p>
     */
    public volatile boolean commitCompositionOnBlur = true;

    /**
     * Drop any composition still in flight once no text field wants input at all, e.g. because
     * the screen closed. Unlike {@link #commitCompositionOnBlur} there is nowhere to commit it
     * to, so the text is discarded.
     */
    public volatile boolean cancelCompositionOnFocusLoss = true;

    /**
     * Tint the part of the composition that will not survive being confirmed, so it is obvious
     * where the text is about to be cut.
     *
     * <p>The colour says what happens to it: {@link #overflowWrapTint} when it will move to the
     * next line or page, {@link #overflowDropTint} when it will simply be thrown away.</p>
     */
    public volatile boolean highlightOverflow = true;

    /** Extra window-message tracing. Very noisy; off unless something needs diagnosing. */
    public volatile boolean verboseLogging = false;

    // ---- colours, as 0xAARRGGBB -----------------------------------------------------------

    /** Behind the clause the IME is currently converting. */
    public volatile int targetTint = 0x40FFFFFF;

    /** Underline for the clause being converted. */
    public volatile int targetUnderline = 0xFFFFFFFF;

    /** Underline for settled clauses. */
    public volatile int clauseUnderline = 0xFFA0A0A0;

    /** Underline for input the IME rejected ({@code ATTR_INPUT_ERROR}). */
    public volatile int errorUnderline = 0xFFFF5555;

    /** Behind text that will wrap to the next line or page when confirmed. */
    public volatile int overflowWrapTint = 0x6033CCFF;

    /** Behind text that will be discarded when confirmed. */
    public volatile int overflowDropTint = 0x60FF3333;

    ImeOptions() {
    }
}
