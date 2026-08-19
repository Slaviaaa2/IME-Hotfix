package jp.antimeme.mc.imehotfix.core;

/**
 * "Textbox Improvements": quality-of-life fixes for Minecraft's text editors that have nothing to
 * do with input methods, but ship alongside them because they touch the same screens.
 *
 * <p>These apply to every character, not just IME input — typing plain ASCII into a sign wraps
 * just the same.</p>
 */
public final class TextboxOptions {

    /**
     * When a character will not fit on the current sign line, move to the next line and put it
     * there instead of dropping it.
     *
     * <p>Vanilla silently discards anything that does not fit the line's rendered width.</p>
     */
    public volatile boolean signAutoWrap = true;

    /**
     * When a character will not fit on the current book page, turn to the next page — adding one
     * if needed — and put it there instead of dropping it.
     */
    public volatile boolean bookAutoPage = true;

    TextboxOptions() {
    }
}
