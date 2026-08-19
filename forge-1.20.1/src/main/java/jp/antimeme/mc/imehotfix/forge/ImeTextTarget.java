package jp.antimeme.mc.imehotfix.forge;

/**
 * Something that can receive committed IME text.
 *
 * <p>Implemented by mixins onto the various things Minecraft uses to edit text — {@code EditBox}
 * for most screens, but the sign and book editors drive a {@code TextFieldHelper} directly — so
 * the shared logic never has to care which one is in front.</p>
 */
public interface ImeTextTarget {

    /** Inserts committed text at the caret, exactly as if the player had typed it. */
    void imehotfix$insertCommitted(String text);

    /**
     * Whether this target reports the composition as part of its value, and therefore needs the
     * synthetic character event that tells screens to re-read it.
     *
     * <p>Only text fields whose screens poll or observe a value need it. Editors that draw the
     * composition themselves — signs, books — must answer {@code false}: the synthetic character
     * would otherwise be typed into them for real, filling them with spaces.</p>
     */
    boolean imehotfix$reportsCompositionInValue();
}
