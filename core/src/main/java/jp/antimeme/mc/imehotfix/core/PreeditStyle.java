package jp.antimeme.mc.imehotfix.core;

/**
 * How one stretch of composition (preedit) text should be presented.
 *
 * <p>The ordinals mirror the {@code ATTR_*} values returned by {@code GCS_COMPATTR}
 * (Windows SDK 10.0.26100.0, {@code um/imm.h}):</p>
 *
 * <pre>
 * ATTR_INPUT                0x00
 * ATTR_TARGET_CONVERTED     0x01
 * ATTR_CONVERTED            0x02
 * ATTR_TARGET_NOTCONVERTED  0x03
 * ATTR_INPUT_ERROR          0x04
 * ATTR_FIXEDCONVERTED       0x05
 * </pre>
 */
public enum PreeditStyle {

    /** Raw, not yet converted input. Thin underline. */
    INPUT(false),

    /** The clause the IME is currently converting. Highlighted block. */
    TARGET_CONVERTED(true),

    /** An already converted clause that is not the active one. Thin underline. */
    CONVERTED(false),

    /** The active clause while it is still unconverted. Highlighted block. */
    TARGET_NOT_CONVERTED(true),

    /** The IME rejected this input. Thin underline (rendered in the error colour). */
    INPUT_ERROR(false),

    /** Locked-in text that can no longer be edited. Thin underline. */
    FIXED_CONVERTED(false);

    private static final PreeditStyle[] BY_ATTRIBUTE = values();

    private final boolean target;

    PreeditStyle(boolean target) {
        this.target = target;
    }

    /**
     * @return {@code true} when this stretch is the clause the IME is acting on, which
     * conventionally gets a filled highlight rather than a plain underline.
     */
    public boolean isTarget() {
        return this.target;
    }

    /** Maps a raw {@code GCS_COMPATTR} byte onto a style, falling back to {@link #INPUT}. */
    public static PreeditStyle fromAttribute(int attribute) {
        if (attribute < 0 || attribute >= BY_ATTRIBUTE.length) {
            return INPUT;
        }
        return BY_ATTRIBUTE[attribute];
    }
}
