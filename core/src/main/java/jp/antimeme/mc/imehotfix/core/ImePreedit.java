package jp.antimeme.mc.imehotfix.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable snapshot of the IME composition string.
 *
 * <p>Instances are published from the window procedure (which runs on the game's main thread
 * inside {@code glfwPollEvents}) and read by the renderer, so they must never be mutated after
 * construction.</p>
 */
public final class ImePreedit {

    private static final byte[] NO_ATTRIBUTES = new byte[0];
    private static final int[] NO_CLAUSES = new int[0];

    public static final ImePreedit EMPTY = new ImePreedit("", 0, NO_ATTRIBUTES, NO_CLAUSES);

    private final String text;
    private final int caret;
    private final byte[] attributes;
    private final int[] clauses;

    public ImePreedit(String text, int caret, byte[] attributes, int[] clauses) {
        this.text = text == null ? "" : text;
        this.caret = clamp(caret, 0, this.text.length());
        this.attributes = attributes == null ? NO_ATTRIBUTES : attributes;
        this.clauses = clauses == null ? NO_CLAUSES : clauses;
    }

    public boolean isEmpty() {
        return this.text.isEmpty();
    }

    /** The composition string as the IME currently has it. */
    public String text() {
        return this.text;
    }

    /** Caret offset inside {@link #text()}, in {@code char} units. */
    public int caret() {
        return this.caret;
    }

    public int length() {
        return this.text.length();
    }

    /** Style of a single character, defaulting to {@link PreeditStyle#INPUT}. */
    public PreeditStyle styleAt(int charIndex) {
        if (charIndex < 0 || charIndex >= this.attributes.length) {
            return PreeditStyle.INPUT;
        }
        return PreeditStyle.fromAttribute(this.attributes[charIndex] & 0xFF);
    }

    /**
     * Splits the composition string into maximal runs of identical style, in text order.
     *
     * <p>When the IME supplied no attribute array the whole string comes back as a single
     * {@link PreeditStyle#INPUT} run.</p>
     */
    public List<Run> runs() {
        if (this.text.isEmpty()) {
            return Collections.emptyList();
        }

        List<Run> runs = new ArrayList<Run>();
        int runStart = 0;
        PreeditStyle runStyle = styleAt(0);

        for (int i = 1; i < this.text.length(); i++) {
            PreeditStyle style = styleAt(i);
            if (style != runStyle) {
                runs.add(new Run(runStart, i, runStyle));
                runStart = i;
                runStyle = style;
            }
        }
        runs.add(new Run(runStart, this.text.length(), runStyle));
        return runs;
    }

    /** Number of clauses the IME reported, or {@code 0} when it reported none. */
    public int clauseCount() {
        return this.clauses.length == 0 ? 0 : this.clauses.length - 1;
    }

    @Override
    public String toString() {
        return "ImePreedit[text=" + this.text + ", caret=" + this.caret
                + ", attrs=" + this.attributes.length + ", clauses=" + clauseCount() + "]";
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    /** A half-open {@code [start, end)} range of the composition string sharing one style. */
    public static final class Run {

        private final int start;
        private final int end;
        private final PreeditStyle style;

        Run(int start, int end, PreeditStyle style) {
            this.start = start;
            this.end = end;
            this.style = style;
        }

        public int start() {
            return this.start;
        }

        public int end() {
            return this.end;
        }

        public PreeditStyle style() {
            return this.style;
        }
    }
}
