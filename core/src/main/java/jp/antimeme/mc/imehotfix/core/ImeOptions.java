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

    /** Extra window-message tracing. Very noisy; off unless something needs diagnosing. */
    public volatile boolean verboseLogging = false;

    ImeOptions() {
    }
}
