package jp.antimeme.mc.imehotfix.forge.mixin;

import jp.antimeme.mc.imehotfix.core.ImeOptions;
import jp.antimeme.mc.imehotfix.core.ImePreedit;
import jp.antimeme.mc.imehotfix.core.ImeSupport;
import jp.antimeme.mc.imehotfix.forge.ImeClientHandler;
import jp.antimeme.mc.imehotfix.forge.ImeOverflowAware;
import jp.antimeme.mc.imehotfix.forge.ImeTextTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * In-line composition for multi-line text areas — the book-and-quill above all.
 *
 * <p>Same approach as the single-line {@code EditBox}: vanilla's floating overlay is suppressed and
 * the composition is spliced into the text for the duration of the draw call, so it wraps and
 * scrolls with everything else. The value is written straight to the field and the lines re-flowed
 * by hand, deliberately bypassing {@code setValue}, which would notify the listener and make the
 * book count itself as edited.</p>
 *
 * <p>A page is capped at 1024 characters and 14 lines, so a long composition can be more than the
 * page will actually keep. That excess is tinted rather than left to spill silently past the
 * page — the same treatment signs get.</p>
 */
@Mixin(MultiLineEditBox.class)
public abstract class MultiLineEditBoxMixin implements ImeTextTarget, ImeOverflowAware {

    /** Vanilla lays this widget out on a 9px line grid. */
    @Unique
    private static final int IMEHOTFIX$LINE_HEIGHT = 9;

    /** Matches the limit the book editor sets on its page. */
    @Unique
    private static final int IMEHOTFIX$MAX_PAGE_CHARS = 1024;

    @Shadow
    @Final
    private MultilineTextField textField;

    @Shadow
    @Final
    private Font font;

    @Shadow
    private IMEPreeditOverlay preeditOverlay;

    @Shadow
    public abstract String getValue();

    /**
     * Padding between the widget edge and its text, matching {@code AbstractTextAreaWidget}.
     *
     * <p>Recomputed rather than shadowed: {@code getInnerLeft()} is declared on the parent class,
     * and {@code @Shadow} only resolves members declared on the target itself.</p>
     */
    @Unique
    private static final int IMEHOTFIX$INNER_PADDING = 4;

    @Unique
    private boolean imehotfix$swapped;

    @Unique
    private String imehotfix$savedValue;

    @Unique
    private int imehotfix$savedCursor;

    @Unique
    private int imehotfix$savedSelectCursor;

    @Unique
    private int imehotfix$compositionStart;

    @Unique
    private ImePreedit imehotfix$activePreedit;

    /** Last caret rectangle handed to the platform, to avoid re-sending an unchanged one. */
    @Unique
    private Runnable imehotfix$overflowHandler;

    @Unique
    private int imehotfix$lastCaretX = Integer.MIN_VALUE;

    @Unique
    private int imehotfix$lastCaretY = Integer.MIN_VALUE;

    @Override
    public void imehotfix$insertCommitted(String text) {
        // The page rejects an insert that would overflow its line limit in its entirety, so feed
        // the text one character at a time to keep whatever fits.
        for (int i = 0; i < text.length(); i++) {
            this.textField.insertText(text.substring(i, i + 1));
        }
    }

    @Override
    public void imehotfix$setOverflowHandler(Runnable handler) {
        this.imehotfix$overflowHandler = handler;
    }

    /**
     * Auto-paging: when a character does not fit, ask the owner to make room and try again.
     *
     * <p>Vanilla drops anything that would push the page past its line limit. Redirecting the
     * insert rather than taking over {@code charTyped} leaves the rest of the vanilla path
     * untouched, and the value comparison is exactly how "it did not fit" is detected. Applies to
     * all typing, not just IME input.</p>
     */
    @Redirect(
            method = "charTyped",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/MultilineTextField;insertText(Ljava/lang/String;)V"))
    private void imehotfix$insertWithOverflow(MultilineTextField field, String text) {
        String before = field.value();
        field.insertText(text);

        if (this.imehotfix$overflowHandler == null
                || !ImeSupport.textboxOptions().bookAutoPage
                || !before.equals(field.value())) {
            return;
        }

        // Nothing changed: the page is full. Let the owner turn the page, then place it there.
        this.imehotfix$overflowHandler.run();
        field.insertText(text);
    }

    @Override
    public boolean imehotfix$reportsCompositionInValue() {
        // Drawn in-line by this mixin; no search to update.
        return false;
    }

    /** Takes the composition over from vanilla, suppressing its floating overlay. */
    @Inject(method = "preeditUpdated", at = @At("HEAD"), cancellable = true)
    private void imehotfix$capturePreedit(PreeditEvent event,
                                          CallbackInfoReturnable<Boolean> callback) {
        if (!ImeSupport.options().inlinePreedit) {
            return;
        }
        this.preeditOverlay = null;
        ImeClientHandler.submitPreedit(this, event);
        callback.setReturnValue(true);
    }

    /** Commits an unfinished composition as focus leaves. */
    @Inject(method = "setFocused", at = @At("HEAD"))
    private void imehotfix$commitBeforeBlur(boolean focused, CallbackInfo callback) {
        // isFocused() comes from AbstractWidget, so it is reached through the target rather than
        // shadowed: @Shadow resolves fields only on the declaring class.
        if (focused || !((MultiLineEditBox) (Object) this).isFocused()) {
            return;
        }
        ImeClientHandler.commitCompositionInto(this);
    }

    @Inject(method = "extractContents", at = @At("HEAD"))
    private void imehotfix$spliceComposition(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo callback) {
        if (this.imehotfix$swapped) {
            imehotfix$restore();
        }
        if (!ImeSupport.options().inlinePreedit) {
            return;
        }

        ImePreedit preedit = ImeSupport.preedit();
        if (preedit.isEmpty() || !ImeClientHandler.isActiveTarget(this)) {
            return;
        }

        MultilineTextFieldAccessor field = (MultilineTextFieldAccessor) this.textField;
        String current = field.imehotfix$getValue();
        if (current == null) {
            return;
        }

        int cursor = Mth.clamp(field.imehotfix$getCursor(), 0, current.length());
        int select = Mth.clamp(field.imehotfix$getSelectCursor(), 0, current.length());
        int start = Math.min(cursor, select);
        int end = Math.max(cursor, select);

        this.imehotfix$savedValue = current;
        this.imehotfix$savedCursor = field.imehotfix$getCursor();
        this.imehotfix$savedSelectCursor = field.imehotfix$getSelectCursor();
        this.imehotfix$compositionStart = start;
        this.imehotfix$activePreedit = preedit;
        this.imehotfix$swapped = true;

        String composed = current.substring(0, start) + preedit.text() + current.substring(end);
        int composedCaret = start + preedit.caret();

        field.imehotfix$setValue(composed);
        field.imehotfix$setCursor(composedCaret);
        field.imehotfix$setSelectCursor(composedCaret);
        // Re-wrap so the composition flows onto the following lines like ordinary text.
        field.imehotfix$reflow();

        imehotfix$reportTextInputArea();
    }

    @Inject(method = "extractContents", at = @At("RETURN"))
    private void imehotfix$restoreAfterRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                              float partialTick, CallbackInfo callback) {
        try {
            if (this.imehotfix$swapped) {
                imehotfix$drawCompositionMarkers(graphics);
            }
        } finally {
            imehotfix$restore();
        }
    }

    @Unique
    private void imehotfix$restore() {
        if (!this.imehotfix$swapped) {
            return;
        }
        this.imehotfix$swapped = false;

        MultilineTextFieldAccessor field = (MultilineTextFieldAccessor) this.textField;
        field.imehotfix$setValue(this.imehotfix$savedValue);
        field.imehotfix$setCursor(this.imehotfix$savedCursor);
        field.imehotfix$setSelectCursor(this.imehotfix$savedSelectCursor);
        field.imehotfix$reflow();
        this.imehotfix$savedValue = null;
        this.imehotfix$activePreedit = null;
    }

    /**
     * Underlines the composition, and marks the part that will not survive being confirmed.
     *
     * <p>The line breaks are recomputed with the same call the field makes, since its own layout
     * model cannot be reached from here.</p>
     */
    @Unique
    private void imehotfix$drawCompositionMarkers(GuiGraphicsExtractor graphics) {
        ImePreedit preedit = this.imehotfix$activePreedit;
        if (preedit == null) {
            return;
        }

        MultilineTextFieldAccessor field = (MultilineTextFieldAccessor) this.textField;
        String text = field.imehotfix$getValue();
        if (text == null || text.isEmpty()) {
            return;
        }

        Font font = this.font;
        List<int[]> lines = imehotfix$splitLines(font, text, field.imehotfix$getWrapWidth());

        int compositionStart = this.imehotfix$compositionStart;
        int compositionEnd = Math.min(compositionStart + preedit.length(), text.length());
        int overflowStart = imehotfix$overflowStart(text, lines, field.imehotfix$getLineLimit());

        ImeOptions options = ImeSupport.options();
        List<ImePreedit.Run> runs = preedit.runs();

        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            int lineStart = lines.get(lineIndex)[0];
            int lineEnd = lines.get(lineIndex)[1];
            int y = imehotfix$innerTop() + lineIndex * IMEHOTFIX$LINE_HEIGHT;

            for (int i = 0; i < runs.size(); i++) {
                ImePreedit.Run run = runs.get(i);
                int from = Math.max(compositionStart + run.start(), lineStart);
                int to = Math.min(Math.min(compositionStart + run.end(), compositionEnd), lineEnd);
                if (from >= to) {
                    continue;
                }
                int left = imehotfix$innerLeft() + font.width(text.substring(lineStart, from));
                int right = imehotfix$innerLeft() + font.width(text.substring(lineStart, to));

                if (run.style().isTarget()) {
                    graphics.fill(left, y - 1, right, y + 8, options.targetTint);
                    graphics.fill(left, y + 8, right, y + 10, options.targetUnderline);
                } else {
                    graphics.fill(left, y + 8, right, y + 9, options.clauseUnderline);
                }
            }

            if (options.highlightOverflow && overflowStart >= 0) {
                int from = Math.max(Math.max(overflowStart, compositionStart), lineStart);
                int to = Math.min(compositionEnd, lineEnd);
                if (from < to) {
                    int left = imehotfix$innerLeft() + font.width(text.substring(lineStart, from));
                    int right = imehotfix$innerLeft() + font.width(text.substring(lineStart, to));
                    boolean wraps = ImeSupport.textboxOptions().bookAutoPage;
                    graphics.fill(left, y - 1, right, y + IMEHOTFIX$LINE_HEIGHT,
                            wraps ? options.overflowWrapTint : options.overflowDropTint);
                }
            }
        }
    }

    @Unique
    private int imehotfix$innerLeft() {
        return ((MultiLineEditBox) (Object) this).getX() + IMEHOTFIX$INNER_PADDING;
    }

    @Unique
    private int imehotfix$innerTop() {
        return ((MultiLineEditBox) (Object) this).getY() + IMEHOTFIX$INNER_PADDING;
    }

    /** The same line breaking the field itself performs, as {@code {start, end}} pairs. */
    @Unique
    private List<int[]> imehotfix$splitLines(Font font, String text, int wrapWidth) {
        final List<int[]> lines = new ArrayList<>();
        font.getSplitter().splitLines(text, wrapWidth, Style.EMPTY, false,
                (style, start, end) -> lines.add(new int[]{start, end}));
        return lines;
    }

    /** @return index at which the page stops accepting text, or {@code -1} while it all fits */
    @Unique
    private int imehotfix$overflowStart(String text, List<int[]> lines, int lineLimit) {
        int overflow = -1;
        if (lineLimit > 0 && lines.size() > lineLimit) {
            overflow = lines.get(lineLimit)[0];
        }
        if (text.length() >= IMEHOTFIX$MAX_PAGE_CHARS) {
            int byLength = IMEHOTFIX$MAX_PAGE_CHARS - 1;
            overflow = overflow < 0 ? byLength : Math.min(overflow, byLength);
        }
        return overflow;
    }

    /**
     * Tells the platform roughly where the caret is, which vanilla does from the overlay this
     * mixin suppresses. Kept caret-sized — the underlying call describes the caret, not an area to
     * keep clear.
     */
    @Unique
    private void imehotfix$reportTextInputArea() {
        int caretX = imehotfix$innerLeft();
        int caretY = imehotfix$innerTop()
                + this.textField.getLineAtCursor() * IMEHOTFIX$LINE_HEIGHT;

        // Only when it actually moves. Re-sending the rectangle every frame makes some IMEs treat
        // it as the caret being moved by the application and commit what is being converted.
        if (caretX == this.imehotfix$lastCaretX && caretY == this.imehotfix$lastCaretY) {
            return;
        }
        this.imehotfix$lastCaretX = caretX;
        this.imehotfix$lastCaretY = caretY;

        Minecraft.getInstance().textInputManager()
                .setTextInputArea(caretX, caretY, caretX + 1, caretY + IMEHOTFIX$LINE_HEIGHT);
    }
}
