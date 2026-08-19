package jp.antimeme.mc.imehotfix.forge.mixin;

import jp.antimeme.mc.imehotfix.core.ImeOptions;
import jp.antimeme.mc.imehotfix.core.ImePreedit;
import jp.antimeme.mc.imehotfix.core.ImeSupport;
import jp.antimeme.mc.imehotfix.core.PreeditStyle;
import jp.antimeme.mc.imehotfix.forge.ImeClientHandler;
import jp.antimeme.mc.imehotfix.forge.ImeTextTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.input.PreeditEvent;
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
 * Replaces vanilla's floating composition box with an in-line one, and lets searches filter on a
 * composition before it is confirmed.
 *
 * <p>Minecraft 26.2 already receives the composition — {@code preeditUpdated} is called with it —
 * but presents it in a separate box drawn beside the field ({@code IMEPreeditOverlay}), and never
 * tells the screen that the visible text changed. So the overlay is suppressed, the composition is
 * spliced into the field's value for the duration of the draw call, and the field reports the
 * composition as part of {@code getValue()} so searches can filter on it.</p>
 *
 * <p>The stored text is never modified, so confirming or abandoning a composition cannot duplicate
 * or lose characters.</p>
 */
@Mixin(EditBox.class)
public abstract class EditBoxMixin implements ImeTextTarget {

    @Shadow
    private String value;

    @Shadow
    private int displayPos;

    @Shadow
    private int cursorPos;

    @Shadow
    private int highlightPos;

    @Shadow
    private boolean canLoseFocus;

    @Shadow
    private Consumer<String> responder;

    @Shadow
    private IMEPreeditOverlay preeditOverlay;

    @Shadow
    private int textX;

    @Shadow
    private int textY;

    @Shadow
    @Final
    private Font font;

    @Shadow
    public abstract void setCursorPosition(int pos);

    @Shadow
    public abstract void setHighlightPos(int pos);

    @Shadow
    public abstract boolean canConsumeInput();

    @Shadow
    public abstract int getInnerWidth();

    @Shadow
    public abstract void insertText(String input);

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

    @Unique
    private ImePreedit imehotfix$activePreedit;

    // ------------------------------------------------------------------------------------
    // ImeTextTarget
    // ------------------------------------------------------------------------------------

    @Override
    public void imehotfix$insertCommitted(String text) {
        insertText(text);
    }

    @Override
    public boolean imehotfix$reportsCompositionInValue() {
        return true;
    }

    // ------------------------------------------------------------------------------------
    // composition intake
    // ------------------------------------------------------------------------------------

    /**
     * Takes the composition over from vanilla.
     *
     * <p>Cancelling here stops {@code IMEPreeditOverlay} from ever being created, which is what
     * removes the floating box; the text is drawn in-line instead.</p>
     */
    @Inject(method = "preeditUpdated", at = @At("HEAD"), cancellable = true)
    private void imehotfix$capturePreedit(PreeditEvent event,
                                          CallbackInfoReturnable<Boolean> callback) {
        if (!ImeSupport.options().inlinePreedit) {
            return;
        }

        this.preeditOverlay = null;
        ImeClientHandler.submitPreedit(this, event);

        // Screens observe a field either by polling getValue() or through a responder. The
        // composition is now part of getValue(), so anything that re-reads the field sees it;
        // fire the responder too, for the ones that only listen.
        if (ImeSupport.options().filterWithComposition && this.responder != null) {
            this.responder.accept(((EditBox) (Object) this).getValue());
        }
        callback.setReturnValue(true);
    }

    /**
     * Reports the composition as part of this field's value while one is in flight, so screens
     * that read {@code getValue()} can filter on it. The stored value is left untouched.
     */
    @Inject(method = "getValue", at = @At("HEAD"), cancellable = true)
    private void imehotfix$reportValueWithComposition(CallbackInfoReturnable<String> callback) {
        if (this.imehotfix$swapped || !ImeSupport.options().filterWithComposition) {
            return;
        }
        if (!ImeClientHandler.isActiveTarget(this)) {
            return;
        }
        String composition = ImeSupport.preedit().text();
        if (composition.isEmpty()) {
            return;
        }

        int selectionStart = Math.min(this.cursorPos, this.highlightPos);
        int selectionEnd = Math.max(this.cursorPos, this.highlightPos);
        callback.setReturnValue(this.value.substring(0, selectionStart)
                + composition
                + this.value.substring(selectionEnd));
    }

    /** Commits an unfinished composition into this field as it loses focus. */
    @Inject(method = "setFocused", at = @At("HEAD"))
    private void imehotfix$commitBeforeBlur(boolean focused, CallbackInfo callback) {
        EditBox self = (EditBox) (Object) this;
        if (focused || !this.canLoseFocus || !self.isFocused()) {
            return;
        }
        ImeClientHandler.commitCompositionInto(this);
    }

    // ------------------------------------------------------------------------------------
    // rendering
    // ------------------------------------------------------------------------------------

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"))
    private void imehotfix$spliceComposition(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo callback) {
        if (this.imehotfix$swapped) {
            imehotfix$restore();
        }
        if (!this.canConsumeInput() || !ImeSupport.options().inlinePreedit) {
            return;
        }

        ImePreedit preedit = ImeSupport.preedit();
        if (preedit.isEmpty() || !ImeClientHandler.isActiveTarget(this)) {
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

        this.value = this.imehotfix$savedValue.substring(0, selectionStart)
                + preedit.text()
                + this.imehotfix$savedValue.substring(selectionEnd);

        int caret = selectionStart + preedit.caret();
        this.setCursorPosition(caret);
        // Also re-derives displayPos, so the field scrolls to follow a long composition.
        this.setHighlightPos(caret);
    }

    @Inject(method = "extractWidgetRenderState", at = @At("RETURN"))
    private void imehotfix$drawComposition(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                           float partialTick, CallbackInfo callback) {
        try {
            if (this.imehotfix$swapped) {
                imehotfix$drawCompositionMarkers(graphics);
            }
            imehotfix$reportTextInputArea();
        } finally {
            imehotfix$restore();
        }
    }

    /**
     * Tells the platform where the caret is.
     *
     * <p>Vanilla does this from inside {@code IMEPreeditOverlay}, which this mixin suppresses, so
     * it has to be done here instead — without it the candidate window has no idea where to go and
     * drifts to a screen edge.</p>
     *
     * <p>This has to stay a caret-sized rectangle. {@code setTextInputArea} feeds
     * {@code glfwSetPreeditCursorRectangle}, which describes the caret itself, not an area to keep
     * clear; handing it the whole widget makes the IME give up on placing its window at all.</p>
     */
    @Unique
    private void imehotfix$reportTextInputArea() {
        if (!this.canConsumeInput()) {
            return;
        }
        int caretX = imehotfix$xAt(this.cursorPos);
        Minecraft.getInstance().textInputManager()
                .setTextInputArea(caretX, this.textY, caretX + 1, this.textY + 9);
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
     * clauses, a filled block under the one being converted.
     */
    @Unique
    private void imehotfix$drawCompositionMarkers(GuiGraphicsExtractor graphics) {
        ImePreedit preedit = this.imehotfix$activePreedit;
        if (preedit == null) {
            return;
        }

        int underline = this.textY + 9;
        ImeOptions options = ImeSupport.options();
        List<ImePreedit.Run> runs = preedit.runs();

        for (int i = 0; i < runs.size(); i++) {
            ImePreedit.Run run = runs.get(i);
            int left = imehotfix$xAt(this.imehotfix$compositionStart + run.start());
            int right = imehotfix$xAt(this.imehotfix$compositionStart + run.end());
            if (right <= left) {
                continue;
            }

            if (run.style().isTarget()) {
                graphics.fill(left, this.textY - 1, right, underline, options.targetTint);
                graphics.fill(left, underline, right, underline + 2, options.targetUnderline);
            } else if (run.style() == PreeditStyle.INPUT_ERROR) {
                graphics.fill(left, underline, right, underline + 1, options.errorUnderline);
            } else {
                graphics.fill(left, underline, right, underline + 1, options.clauseUnderline);
            }
        }
    }

    /** X of a character offset in the widget's own space, clamped to what is visible. */
    @Unique
    private int imehotfix$xAt(int absoluteIndex) {
        String visible = imehotfix$visibleText();
        int relative = absoluteIndex - this.displayPos;
        if (relative < 0) {
            relative = 0;
        } else if (relative > visible.length()) {
            relative = visible.length();
        }
        return this.textX + this.font.width(visible.substring(0, relative));
    }

    @Unique
    private String imehotfix$visibleText() {
        int start = Math.min(this.displayPos, this.value.length());
        return this.font.plainSubstrByWidth(this.value.substring(start), this.getInnerWidth());
    }
}
