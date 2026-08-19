package jp.antimeme.mc.imehotfix.forge.mixin;

import jp.antimeme.mc.imehotfix.core.ImeOptions;
import jp.antimeme.mc.imehotfix.core.ImePreedit;
import jp.antimeme.mc.imehotfix.core.ImeSupport;
import jp.antimeme.mc.imehotfix.core.PreeditStyle;
import jp.antimeme.mc.imehotfix.forge.ImeClientHandler;
import jp.antimeme.mc.imehotfix.forge.ImeTextTarget;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Draws the IME composition string inline, exactly where the typed text will land.
 *
 * <p>Rather than reimplementing {@code renderWidget}, this swaps the widget's backing value for
 * "value with the composition spliced in at the caret" for the duration of the vanilla draw call
 * and restores it immediately afterwards. Wrapping instead of overwriting means text scrolling,
 * suggestions, formatters and highlight handling all keep working, and any subclass that calls
 * {@code super.renderWidget} inherits the behaviour for free — which is how JEI's
 * {@code GuiTextFieldFilter} and the vanilla recipe-book search field are covered without any
 * mod-specific code.</p>
 *
 * <p>The swap writes {@code value} directly instead of going through {@code setValue}, so the
 * stored text is never actually modified — confirming or abandoning a composition therefore
 * cannot duplicate or lose characters.</p>
 *
 * <p>Screens are still told about the composition, so searches can narrow down before the
 * conversion is confirmed, but through a separate path: while one is in flight the field reports
 * the composition as part of {@code getValue()}, and a synthetic character event both notifies
 * pollers and fires the responder. See {@code ImeClientHandler#publishCompositionChange}.</p>
 */
@Mixin(EditBox.class)
public abstract class EditBoxMixin implements ImeTextTarget {

    /** Height of the caret rectangle handed to the IME, in GUI units. */
    @Unique
    private static final int IMEHOTFIX$CARET_HEIGHT = 10;

    @Shadow
    private String value;

    @Shadow
    private int displayPos;

    @Shadow
    private int cursorPos;

    @Shadow
    private int highlightPos;

    @Shadow
    private boolean bordered;

    @Shadow
    private boolean canLoseFocus;

    @Shadow
    private Consumer<String> responder;

    @Shadow
    @Final
    private Font font;

    @Shadow
    public abstract void setCursorPosition(int position);

    @Shadow
    public abstract void setHighlightPos(int position);

    @Shadow
    public abstract boolean canConsumeInput();

    @Shadow
    public abstract int getInnerWidth();

    @Shadow
    public abstract void insertText(String text);

    @Override
    public void imehotfix$insertCommitted(String text) {
        insertText(text);
    }

    @Override
    public boolean imehotfix$reportsCompositionInValue() {
        return true;
    }

    @Unique
    private boolean imehotfix$swapped;

    @Unique
    private String imehotfix$savedValue;

    @Unique
    private int imehotfix$savedCursor;

    @Unique
    private int imehotfix$savedHighlight;

    @Unique
    private int imehotfix$savedDisplayPos;

    @Unique
    private int imehotfix$compositionStart;

    /**
     * The composition snapshot this frame's draw is based on. Held so that the markers drawn
     * after the vanilla pass cannot disagree with the text that was spliced in before it.
     */
    @Unique
    private ImePreedit imehotfix$activePreedit;

    /**
     * Reports the composition as part of this field's value while one is in flight.
     *
     * <p>This is what lets a search narrow down before the conversion is confirmed: screens read
     * the field through {@code getValue()}, so they see "ダーク" while it is still being typed
     * and filter on it. The stored value is left untouched, so confirming or abandoning the
     * composition cannot double up or lose text.</p>
     *
     * <p>Skipped while the render swap is active, where the composition is already part of the
     * backing value and counting it twice would duplicate the text.</p>
     */
    @Inject(method = "getValue", at = @At("HEAD"), cancellable = true)
    private void imehotfix$reportValueWithComposition(CallbackInfoReturnable<String> callback) {
        if (this.imehotfix$swapped || !ImeSupport.options().filterWithComposition) {
            return;
        }
        if (!ImeClientHandler.isActiveTarget(this)) {
            return;
        }
        String composition = ImeClientHandler.publishedComposition();
        if (composition.isEmpty()) {
            return;
        }

        int selectionStart = Math.min(this.cursorPos, this.highlightPos);
        int selectionEnd = Math.max(this.cursorPos, this.highlightPos);
        callback.setReturnValue(this.value.substring(0, selectionStart)
                + composition
                + this.value.substring(selectionEnd));
    }

    /**
     * Swallows the synthetic character event that announces a composition change.
     *
     * <p>It exists only to put the screen back on its normal "did the text change?" code path;
     * the text itself is reported by {@link #imehotfix$reportValueWithComposition}. Returning
     * {@code true} without inserting anything is what makes the screen compare values and act,
     * while leaving the field's contents alone.</p>
     */
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void imehotfix$swallowSyntheticChar(char character, int modifiers,
                                                CallbackInfoReturnable<Boolean> callback) {
        if (!ImeClientHandler.isPublishingComposition()) {
            return;
        }
        // The screen has already sampled the old value; make the new composition visible now, so
        // that its post-call sample differs and it re-runs the search.
        ImeClientHandler.advancePublishedComposition();

        // Screens observe this field in one of two ways, and both have to be served: the creative
        // inventory compares getValue() around this call, while JEI registers a responder
        // (IngredientListOverlay wires one up in its constructor) and never polls. Firing the
        // responder with the composed value is what makes JEI's search follow along.
        EditBox self = (EditBox) (Object) this;
        if (this.responder != null) {
            this.responder.accept(self.getValue());
        }

        callback.setReturnValue(this.canConsumeInput());
    }

    /**
     * Commits an unfinished composition into this field as it loses focus.
     *
     * <p>Covers tabbing away and any programmatic unfocus — notably JEI, whose
     * {@code TextFieldInputHandler.unfocus()} calls straight through to
     * {@code EditBox.setFocused(false)}. Without this the composition survives the focus change
     * and reappears in whatever box gains focus next.</p>
     */
    @Inject(method = "setFocused", at = @At("HEAD"))
    private void imehotfix$commitBeforeBlur(boolean focused, CallbackInfo callback) {
        EditBox self = (EditBox) (Object) this;
        // canLoseFocus == false means the vanilla method is about to ignore this call (the chat
        // box works that way), so the field is not actually losing focus.
        if (focused || !this.canLoseFocus || !self.isFocused()) {
            return;
        }
        ImeClientHandler.commitCompositionInto(this);
    }

    @Inject(method = "renderWidget", at = @At("HEAD"))
    private void imehotfix$spliceComposition(GuiGraphics graphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo callback) {
        if (this.imehotfix$swapped) {
            // Something threw between HEAD and RETURN last frame; make sure the widget is sane
            // before touching it again.
            imehotfix$restore();
        }
        if (!this.canConsumeInput()) {
            return;
        }

        // This field is focused and editable, so the IME should be live this frame.
        ImeClientHandler.markTextInputActive(this);

        if (!ImeSupport.options().inlinePreedit) {
            return;
        }
        ImePreedit preedit = ImeSupport.preedit();
        if (preedit.isEmpty()) {
            return;
        }

        int selectionStart = Math.min(this.cursorPos, this.highlightPos);
        int selectionEnd = Math.max(this.cursorPos, this.highlightPos);

        this.imehotfix$savedValue = this.value;
        this.imehotfix$savedCursor = this.cursorPos;
        this.imehotfix$savedHighlight = this.highlightPos;
        this.imehotfix$savedDisplayPos = this.displayPos;
        this.imehotfix$compositionStart = selectionStart;
        this.imehotfix$activePreedit = preedit;
        this.imehotfix$swapped = true;

        // A selection is replaced on commit, so show it replaced while composing too.
        this.value = this.imehotfix$savedValue.substring(0, selectionStart)
                + preedit.text()
                + this.imehotfix$savedValue.substring(selectionEnd);

        int caret = selectionStart + preedit.caret();
        this.setCursorPosition(caret);
        // setHighlightPos is also what re-derives displayPos, so the field scrolls to follow a
        // composition that runs past its right edge.
        this.setHighlightPos(caret);
    }

    @Inject(method = "renderWidget", at = @At("RETURN"))
    private void imehotfix$drawComposition(GuiGraphics graphics, int mouseX, int mouseY,
                                           float partialTick, CallbackInfo callback) {
        try {
            if (this.imehotfix$swapped) {
                imehotfix$drawCompositionMarkers(graphics);
            }
            if (this.canConsumeInput()) {
                // While swapped, cursorPos already points at the caret inside the composition.
                // Outside a composition this keeps the IME informed anyway, so the very first
                // candidate window opens in the right place.
                imehotfix$reportCaret(this.cursorPos);
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
        this.value = this.imehotfix$savedValue;
        this.cursorPos = this.imehotfix$savedCursor;
        this.highlightPos = this.imehotfix$savedHighlight;
        this.displayPos = this.imehotfix$savedDisplayPos;
        this.imehotfix$savedValue = null;
        this.imehotfix$activePreedit = null;
        this.imehotfix$swapped = false;
    }

    /**
     * Underlines the composition the way desktop text fields do: a thin rule under settled
     * clauses, a filled block under the clause the IME is currently converting.
     */
    @Unique
    private void imehotfix$drawCompositionMarkers(GuiGraphics graphics) {
        ImePreedit preedit = this.imehotfix$activePreedit;
        int top = imehotfix$textTop();
        int underline = top + 9;

        List<ImePreedit.Run> runs = preedit.runs();
        for (int i = 0; i < runs.size(); i++) {
            ImePreedit.Run run = runs.get(i);
            int left = imehotfix$xAt(this.imehotfix$compositionStart + run.start());
            int right = imehotfix$xAt(this.imehotfix$compositionStart + run.end());
            if (right <= left) {
                continue;
            }

            ImeOptions options = ImeSupport.options();
            if (run.style().isTarget()) {
                graphics.fill(left, top - 1, right, underline, options.targetTint);
                graphics.fill(left, underline, right, underline + 2, options.targetUnderline);
            } else if (run.style() == PreeditStyle.INPUT_ERROR) {
                graphics.fill(left, underline, right, underline + 1, options.errorUnderline);
            } else {
                graphics.fill(left, underline, right, underline + 1, options.clauseUnderline);
            }
        }
    }

    @Unique
    private void imehotfix$reportCaret(int absoluteIndex) {
        ImeClientHandler.reportCaret(
                imehotfix$xAt(absoluteIndex), imehotfix$textTop(), 1, IMEHOTFIX$CARET_HEIGHT);

        // Keep the candidate list off the field itself, not just off the caret.
        EditBox self = (EditBox) (Object) this;
        ImeClientHandler.reportExclusion(
                self.getX(), self.getY(), self.getWidth(), self.getHeight());
    }

    /** X of a character offset in the widget's own coordinate space, clamped to what is visible. */
    @Unique
    private int imehotfix$xAt(int absoluteIndex) {
        String visible = imehotfix$visibleText();
        int relative = absoluteIndex - this.displayPos;
        if (relative < 0) {
            relative = 0;
        } else if (relative > visible.length()) {
            relative = visible.length();
        }
        return imehotfix$textLeft() + this.font.width(visible.substring(0, relative));
    }

    @Unique
    private String imehotfix$visibleText() {
        int start = Math.min(this.displayPos, this.value.length());
        return this.font.plainSubstrByWidth(this.value.substring(start), this.getInnerWidth());
    }

    @Unique
    private int imehotfix$textLeft() {
        EditBox self = (EditBox) (Object) this;
        return this.bordered ? self.getX() + 4 : self.getX();
    }

    @Unique
    private int imehotfix$textTop() {
        EditBox self = (EditBox) (Object) this;
        return this.bordered ? self.getY() + (self.getHeight() - 8) / 2 : self.getY();
    }
}
