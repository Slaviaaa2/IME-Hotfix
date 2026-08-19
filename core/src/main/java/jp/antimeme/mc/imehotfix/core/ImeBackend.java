package jp.antimeme.mc.imehotfix.core;

/**
 * Platform-specific half of the mod: owns the native input-method plumbing for one window.
 *
 * <p>Every method is called from the game's main thread except the internal message pump, which
 * also runs on that thread (window procedures are dispatched inside {@code glfwPollEvents}).</p>
 */
public interface ImeBackend {

    /**
     * Hooks the native window.
     *
     * @param nativeWindowHandle platform window handle (an {@code HWND} on Windows)
     * @return {@code true} when the hook is live
     */
    boolean attach(long nativeWindowHandle);

    /** Unhooks and restores whatever was there before. Safe to call when not attached. */
    void detach();

    boolean isAttached();

    /**
     * Declares whether a text field currently wants IME input. Implementations use this to
     * attach/detach the input context so the IME cannot eat gameplay keys.
     */
    void setInputActive(boolean active);

    boolean isInputActive();

    /** The live composition string, never {@code null}. */
    ImePreedit preedit();

    boolean isComposing();

    /**
     * Reports where the caret is, so the candidate list can follow it.
     *
     * @param x      left edge, in native window client pixels
     * @param y      top edge, in native window client pixels
     * @param width  caret width in pixels
     * @param height caret height in pixels
     */
    void setCaretRect(int x, int y, int width, int height);

    /**
     * Reports the area the candidate list should stay clear of, in native window client pixels.
     *
     * <p>Without this the IME only knows to avoid the caret itself — a rectangle one pixel wide —
     * so a multi-row candidate list happily covers the text right below it. Handing it the whole
     * area the text occupies makes it place the list outside instead.</p>
     */
    void setExclusionRect(int x, int y, int width, int height);

    /** Asks the IME to drop any in-flight composition without committing it. */
    void cancelComposition();

    /** Human-readable name for logs and the config screen. */
    String describe();
}
