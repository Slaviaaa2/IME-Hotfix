package jp.antimeme.mc.imehotfix.forge;

/**
 * A text area that can tell its owner when typing did not fit.
 *
 * <p>Exists because Minecraft 26.2 edits book pages through a general-purpose widget, which has no
 * idea it is inside a book and cannot turn the page itself. The widget reports the overflow; the
 * book editor decides what to do about it.</p>
 */
public interface ImeOverflowAware {

    /**
     * Sets what to run when a character is rejected for not fitting.
     *
     * <p>The handler is expected to make room — turning to the next page, say. The character is
     * re-inserted afterwards, so the handler must leave the widget ready to accept it.</p>
     */
    void imehotfix$setOverflowHandler(Runnable handler);
}
