package jp.antimeme.mc.imehotfix.forge;

/**
 * Something that can receive committed IME text and report a composition to its screen.
 *
 * <p>Implemented by mixins onto the text editors, so the shared logic never has to care which one
 * is in front.</p>
 */
public interface ImeTextTarget {

    /** Inserts committed text at the caret, exactly as if the player had typed it. */
    void imehotfix$insertCommitted(String text);

    /**
     * Whether this target reports the composition as part of its value, and therefore needs the
     * synthetic character event that tells screens to re-read it.
     *
     * <p>Editors that draw the composition themselves must answer {@code false}: the synthetic
     * character would otherwise be typed into them for real.</p>
     */
    boolean imehotfix$reportsCompositionInValue();
}
