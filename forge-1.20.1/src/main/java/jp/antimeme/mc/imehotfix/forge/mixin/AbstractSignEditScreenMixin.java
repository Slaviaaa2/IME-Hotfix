package jp.antimeme.mc.imehotfix.forge.mixin;

import jp.antimeme.mc.imehotfix.core.ImeOptions;
import jp.antimeme.mc.imehotfix.core.ImePreedit;
import jp.antimeme.mc.imehotfix.core.ImeSupport;
import jp.antimeme.mc.imehotfix.core.PreeditStyle;
import jp.antimeme.mc.imehotfix.forge.ImeClientHandler;
import jp.antimeme.mc.imehotfix.forge.ImeTextTarget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * IME support for the sign editor (both standing and hanging signs).
 *
 * <p>Sign editing does not use {@code EditBox}: the screen keeps four raw strings, drives a
 * {@code TextFieldHelper} over whichever line is active, and draws the text itself, centred and
 * inside a transformed pose. So the composition is spliced into the active line for the duration
 * of the draw call, with the helper's caret moved past it, and both are put back straight
 * afterwards — the stored line is never actually modified, which matters here because it is what
 * gets sent to the server when the screen closes.</p>
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin extends Screen implements ImeTextTarget {

    /** A sign has four lines, indexed 0-3. */
    @Unique
    private static final int IMEHOTFIX$LAST_LINE = 3;

    protected AbstractSignEditScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    @Final
    private SignBlockEntity sign;

    @Shadow
    @Final
    private String[] messages;

    @Shadow
    private int line;

    @Shadow
    private TextFieldHelper signField;

    @Unique
    private boolean imehotfix$swapped;

    @Unique
    private String imehotfix$savedLine;

    @Unique
    private int imehotfix$savedCursor;

    @Unique
    private int imehotfix$savedSelection;

    @Unique
    private int imehotfix$compositionStart;

    @Unique
    private ImePreedit imehotfix$activePreedit;

    @Override
    public void imehotfix$insertCommitted(String text) {
        if (this.signField == null) {
            return;
        }
        // A sign line is limited by rendered width, and TextFieldHelper rejects an insert that
        // does not fit *in its entirety*. Feeding the text one character at a time keeps whatever
        // does fit instead of silently dropping the whole conversion.
        for (int i = 0; i < text.length(); i++) {
            this.signField.insertText(text.substring(i, i + 1));
        }
    }

    @Override
    public boolean imehotfix$reportsCompositionInValue() {
        // This screen draws the composition itself, so it must never be sent the synthetic
        // character event: it would be typed into the sign for real.
        return false;
    }

    /** Belt and braces: refuse the synthetic composition character. */
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void imehotfix$ignoreSyntheticChar(char character, int modifiers,
                                               CallbackInfoReturnable<Boolean> callback) {
        if (ImeClientHandler.isPublishingComposition()) {
            callback.setReturnValue(true);
        }
    }

    /**
     * Auto-wrap: carries a character that does not fit onto the next line.
     *
     * <p>Vanilla drops anything exceeding the line's rendered width. Redirecting the insert rather
     * than taking over {@code charTyped} leaves the rest of the vanilla path untouched. Applies to
     * all typing, not just IME input.</p>
     */
    @Redirect(
            method = "charTyped",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/font/TextFieldHelper;charTyped(C)Z"))
    private boolean imehotfix$charTypedWithAutoWrap(TextFieldHelper helper, char character) {
        if (!ImeSupport.textboxOptions().signAutoWrap) {
            return helper.charTyped(character);
        }

        String before = this.messages[this.line];
        boolean handled = helper.charTyped(character);
        if (!java.util.Objects.equals(before, this.messages[this.line])) {
            return handled;
        }

        // Nothing changed, which for a sign line means "too wide to fit". Carry it over.
        if (this.line < IMEHOTFIX$LAST_LINE) {
            this.line++;
            helper.setCursorToEnd();
            return helper.charTyped(character);
        }
        return handled;
    }

    @Inject(method = "renderSignText", at = @At("HEAD"))
    private void imehotfix$spliceComposition(GuiGraphics graphics, CallbackInfo callback) {
        if (this.imehotfix$swapped) {
            imehotfix$restore();
        }
        if (this.signField == null) {
            return;
        }

        // The sign editor is always accepting text, so the IME should be live for as long as it
        // is open.
        ImeClientHandler.markTextInputActive(this);

        if (!ImeSupport.options().inlinePreedit) {
            return;
        }
        ImePreedit preedit = ImeSupport.preedit();
        if (preedit.isEmpty()) {
            return;
        }

        String current = this.messages[this.line];
        if (current == null) {
            return;
        }

        TextFieldHelperAccessor caret = (TextFieldHelperAccessor) this.signField;
        int cursor = Mth.clamp(caret.imehotfix$getCursorPos(), 0, current.length());
        int selection = Mth.clamp(caret.imehotfix$getSelectionPos(), 0, current.length());
        int start = Math.min(cursor, selection);
        int end = Math.max(cursor, selection);

        this.imehotfix$savedLine = current;
        this.imehotfix$savedCursor = caret.imehotfix$getCursorPos();
        this.imehotfix$savedSelection = caret.imehotfix$getSelectionPos();
        this.imehotfix$compositionStart = start;
        this.imehotfix$activePreedit = preedit;
        this.imehotfix$swapped = true;

        this.messages[this.line] = current.substring(0, start) + preedit.text() + current.substring(end);

        int composedCaret = start + preedit.caret();
        caret.imehotfix$setCursorPos(composedCaret);
        caret.imehotfix$setSelectionPos(composedCaret);
    }

    @Inject(method = "renderSignText", at = @At("RETURN"))
    private void imehotfix$drawComposition(GuiGraphics graphics, CallbackInfo callback) {
        try {
            if (this.signField != null) {
                if (this.imehotfix$swapped) {
                    imehotfix$drawCompositionMarkers(graphics);
                }
                imehotfix$reportCaret(graphics);
            }
        } finally {
            imehotfix$restore();
        }
    }

    /** Commits anything still being converted before the lines are sent to the server. */
    @Inject(method = "removed", at = @At("HEAD"))
    private void imehotfix$commitBeforeClose(CallbackInfo callback) {
        imehotfix$restore();
        ImeClientHandler.commitCompositionInto(this);
    }

    @Unique
    private void imehotfix$restore() {
        if (!this.imehotfix$swapped) {
            return;
        }
        this.messages[this.line] = this.imehotfix$savedLine;
        if (this.signField != null) {
            TextFieldHelperAccessor caret = (TextFieldHelperAccessor) this.signField;
            caret.imehotfix$setCursorPos(this.imehotfix$savedCursor);
            caret.imehotfix$setSelectionPos(this.imehotfix$savedSelection);
        }
        this.imehotfix$savedLine = null;
        this.imehotfix$activePreedit = null;
        this.imehotfix$swapped = false;
    }

    @Unique
    private void imehotfix$drawCompositionMarkers(GuiGraphics graphics) {
        ImePreedit preedit = this.imehotfix$activePreedit;
        String text = this.messages[this.line];
        if (preedit == null || text == null) {
            return;
        }

        int lineHeight = this.sign.getTextLineHeight();
        // Vanilla draws each line centred on the origin, so x starts half a line to the left.
        int origin = -this.font.width(text) / 2;
        int top = this.line * lineHeight - 4 * lineHeight / 2;
        int underline = top + 9;

        List<ImePreedit.Run> runs = preedit.runs();
        for (int i = 0; i < runs.size(); i++) {
            ImePreedit.Run run = runs.get(i);
            int from = Mth.clamp(this.imehotfix$compositionStart + run.start(), 0, text.length());
            int to = Mth.clamp(this.imehotfix$compositionStart + run.end(), 0, text.length());
            if (from >= to) {
                continue;
            }

            int left = origin + this.font.width(text.substring(0, from));
            int right = origin + this.font.width(text.substring(0, to));

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

        imehotfix$drawOverflow(graphics, text, origin, top, underline);
    }

    /**
     * Marks the part of the composition that will not fit the current line once it is confirmed.
     *
     * <p>A sign line is limited by rendered width, so what is on screen while converting can be
     * wider than what will actually be kept. The overflow is split in two by measuring how much
     * the remaining lines can really absorb: blue up to the point auto-wrap can still place it,
     * red from where the sign runs out of room.</p>
     */
    @Unique
    private void imehotfix$drawOverflow(GuiGraphics graphics, String text, int origin, int top,
                                        int underline) {
        ImeOptions options = ImeSupport.options();
        if (!options.highlightOverflow || this.imehotfix$activePreedit == null) {
            return;
        }

        int limit = this.font.plainSubstrByWidth(text, this.sign.getMaxTextLineWidth()).length();
        int compositionEnd = Math.min(
                this.imehotfix$compositionStart + this.imehotfix$activePreedit.length(), text.length());
        int from = Math.max(limit, this.imehotfix$compositionStart);
        if (from >= compositionEnd) {
            return;
        }

        int wrapEnd = Math.min(imehotfix$wrappableEnd(text, from), compositionEnd);

        if (wrapEnd > from) {
            graphics.fill(imehotfix$xOf(text, origin, from), top - 1,
                    imehotfix$xOf(text, origin, wrapEnd), underline + 1, options.overflowWrapTint);
        }
        if (compositionEnd > wrapEnd) {
            graphics.fill(imehotfix$xOf(text, origin, wrapEnd), top - 1,
                    imehotfix$xOf(text, origin, compositionEnd), underline + 1,
                    options.overflowDropTint);
        }
    }

    @Unique
    private int imehotfix$xOf(String text, int origin, int index) {
        return origin + this.font.width(text.substring(0, index));
    }

    /**
     * Walks the remaining lines the way auto-wrap will, and reports how far the overflow can
     * actually be placed.
     *
     * <p>Measuring by width rather than assuming "anything past the last line is lost" matters:
     * the lines below may already hold text, so the room left is not simply three lines' worth.
     * </p>
     *
     * @return index at which the sign runs out of room; equal to {@code overflowStart} when
     * nothing more fits at all
     */
    @Unique
    private int imehotfix$wrappableEnd(String text, int overflowStart) {
        if (!ImeSupport.textboxOptions().signAutoWrap) {
            return overflowStart;
        }

        int maxWidth = this.sign.getMaxTextLineWidth();
        int placed = overflowStart;

        for (int target = this.line + 1; target <= IMEHOTFIX$LAST_LINE && placed < text.length();
                target++) {
            String existing = this.messages[target] == null ? "" : this.messages[target];
            String candidate = existing + text.substring(placed);
            int accepted = this.font.plainSubstrByWidth(candidate, maxWidth).length()
                    - existing.length();
            if (accepted > 0) {
                placed += accepted;
            }
        }
        return placed;
    }

    /**
     * Tells the IME where the caret is, so the candidate list follows it.
     *
     * <p>Sign text is drawn inside a translated and scaled pose, so the caret's local position has
     * to be run through the current transform to land on actual screen coordinates.</p>
     */
    @Unique
    private void imehotfix$reportCaret(GuiGraphics graphics) {
        String text = this.messages[this.line];
        if (text == null) {
            text = "";
        }

        int cursor = Mth.clamp(
                ((TextFieldHelperAccessor) this.signField).imehotfix$getCursorPos(), 0, text.length());
        int lineHeight = this.sign.getTextLineHeight();
        int localX = this.font.width(text.substring(0, cursor)) - this.font.width(text) / 2;
        int localY = this.line * lineHeight - 4 * lineHeight / 2;

        org.joml.Matrix4f transform = graphics.pose().last().pose();
        Vector3f caret = transform.transformPosition(new Vector3f((float) localX, (float) localY, 0.0F));
        ImeClientHandler.reportCaret(
                Math.round(caret.x()), Math.round(caret.y()), 1, lineHeight);

        // Keep the candidate list off the whole sign face, so it cannot cover the other lines.
        int halfWidth = this.sign.getMaxTextLineWidth() / 2;
        int top = -4 * lineHeight / 2;
        Vector3f topLeft = transform.transformPosition(
                new Vector3f((float) -halfWidth, (float) top, 0.0F));
        Vector3f bottomRight = transform.transformPosition(
                new Vector3f((float) halfWidth, (float) (top + 4 * lineHeight), 0.0F));
        ImeClientHandler.reportExclusion(
                Math.round(topLeft.x()), Math.round(topLeft.y()),
                Math.round(bottomRight.x() - topLeft.x()),
                Math.round(bottomRight.y() - topLeft.y()));
    }
}
