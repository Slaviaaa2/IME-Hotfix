package jp.antimeme.mc.imehotfix.forge.mixin;

import jp.antimeme.mc.imehotfix.core.ImeOptions;
import jp.antimeme.mc.imehotfix.core.ImePreedit;
import jp.antimeme.mc.imehotfix.core.ImeSupport;
import jp.antimeme.mc.imehotfix.core.PreeditStyle;
import jp.antimeme.mc.imehotfix.forge.ImeClientHandler;
import jp.antimeme.mc.imehotfix.forge.ImeTextTarget;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
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
import java.util.Objects;

/**
 * IME support and auto-paging for the book-and-quill editor, covering both the page text and the
 * title entered when signing.
 *
 * <p>Like the sign editor this screen does not use {@code EditBox}: it edits a list of page
 * strings through a {@code TextFieldHelper} and lays the text out into a cached display model.
 * The composition is therefore spliced into the current page for the duration of the draw call,
 * with the layout cache invalidated around it so the text re-wraps and the caret lands in the
 * right place, and everything is put back immediately afterwards.</p>
 *
 * <p>The screen's own layout model ({@code DisplayCache}, {@code Pos2i}) is package-private and
 * cannot be reached from here, so the line breaks are recomputed with the same {@code splitLines}
 * call vanilla makes. That is what allows the clause underlines and the overflow marker to be
 * drawn on exactly the lines the text landed on.</p>
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenMixin extends Screen implements ImeTextTarget {

    /** Text area of a page, matching vanilla's own layout constants. */
    @Unique
    private static final int IMEHOTFIX$PAGE_WIDTH = 114;

    @Unique
    private static final int IMEHOTFIX$PAGE_HEIGHT = 128;

    /** Vanilla lays book text out on a 9px line grid. */
    @Unique
    private static final int IMEHOTFIX$LINE_HEIGHT = 9;

    /** A page holds fewer than 1024 characters. */
    @Unique
    private static final int IMEHOTFIX$MAX_PAGE_CHARS = 1024;

    /** Caret height in GUI units. */
    @Unique
    private static final int IMEHOTFIX$CARET_HEIGHT = 9;

    protected BookEditScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    private boolean isSigning;

    @Shadow
    private String title;

    @Shadow
    private int currentPage;

    @Shadow
    @Final
    private List<String> pages;

    @Shadow
    @Final
    private TextFieldHelper pageEdit;

    @Shadow
    @Final
    private TextFieldHelper titleEdit;

    @Shadow
    private void clearDisplayCache() {
        throw new AssertionError("shadow");
    }

    @Shadow
    private void pageForward() {
        throw new AssertionError("shadow");
    }

    @Shadow
    private String getCurrentPageText() {
        throw new AssertionError("shadow");
    }

    @Unique
    private boolean imehotfix$swapped;

    @Unique
    private boolean imehotfix$swappedTitle;

    @Unique
    private String imehotfix$savedText;

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
        TextFieldHelper editor = this.isSigning ? this.titleEdit : this.pageEdit;
        // Both the title (16 chars) and a page (1024 chars, and it must still fit the page
        // height) reject an insert that does not fit in its entirety. Feeding the text one
        // character at a time keeps whatever does fit — and, with auto-paging on, the redirect
        // below carries the rest onto the next page.
        for (int i = 0; i < text.length(); i++) {
            editor.insertText(text.substring(i, i + 1));
        }
    }

    @Override
    public boolean imehotfix$reportsCompositionInValue() {
        // This screen draws the composition itself, so it must never be sent the synthetic
        // character event: it would be typed into the page for real.
        return false;
    }

    /** Belt and braces: refuse the synthetic character even if one somehow arrives. */
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void imehotfix$ignoreSyntheticChar(char character, int modifiers,
                                               CallbackInfoReturnable<Boolean> callback) {
        if (ImeClientHandler.isPublishingComposition()) {
            callback.setReturnValue(true);
        }
    }

    /**
     * Auto-page: carries a character that does not fit onto the next page, adding one if needed.
     *
     * <p>Vanilla drops anything that would push the page past its height or character limit.
     * Redirecting the insert rather than taking over {@code charTyped} leaves the rest of the
     * vanilla path untouched. Applies to all typing, not just IME input.</p>
     */
    @Redirect(
            method = "charTyped",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/font/TextFieldHelper;insertText(Ljava/lang/String;)V"))
    private void imehotfix$insertWithAutoPage(TextFieldHelper helper, String text) {
        if (this.isSigning || !ImeSupport.textboxOptions().bookAutoPage) {
            helper.insertText(text);
            return;
        }

        String before = getCurrentPageText();
        helper.insertText(text);
        if (!Objects.equals(before, getCurrentPageText())) {
            return;
        }

        // The page rejected it: full, or too tall. Turn the page and put it there instead.
        pageForward();
        helper.insertText(text);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void imehotfix$spliceComposition(GuiGraphics graphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo callback) {
        if (this.imehotfix$swapped) {
            imehotfix$restore();
        }

        // The book editor always accepts text, so the IME should stay live while it is open.
        ImeClientHandler.markTextInputActive(this);

        if (!ImeSupport.options().inlinePreedit) {
            return;
        }
        ImePreedit preedit = ImeSupport.preedit();
        if (preedit.isEmpty()) {
            return;
        }

        boolean signing = this.isSigning;
        TextFieldHelper editor = signing ? this.titleEdit : this.pageEdit;
        String current;
        if (signing) {
            current = this.title;
        } else {
            if (this.currentPage < 0 || this.currentPage >= this.pages.size()) {
                return;
            }
            current = this.pages.get(this.currentPage);
        }
        if (current == null) {
            return;
        }

        TextFieldHelperAccessor caret = (TextFieldHelperAccessor) editor;
        int cursor = Mth.clamp(caret.imehotfix$getCursorPos(), 0, current.length());
        int selection = Mth.clamp(caret.imehotfix$getSelectionPos(), 0, current.length());
        int start = Math.min(cursor, selection);
        int end = Math.max(cursor, selection);

        this.imehotfix$savedText = current;
        this.imehotfix$savedCursor = caret.imehotfix$getCursorPos();
        this.imehotfix$savedSelection = caret.imehotfix$getSelectionPos();
        this.imehotfix$swappedTitle = signing;
        this.imehotfix$compositionStart = start;
        this.imehotfix$activePreedit = preedit;
        this.imehotfix$swapped = true;

        String composed = current.substring(0, start) + preedit.text() + current.substring(end);
        int composedCaret = start + preedit.caret();

        if (signing) {
            this.title = composed;
        } else {
            this.pages.set(this.currentPage, composed);
            // Force the layout to be rebuilt so the composition wraps and the caret follows it.
            clearDisplayCache();
        }

        caret.imehotfix$setCursorPos(composedCaret);
        caret.imehotfix$setSelectionPos(composedCaret);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void imehotfix$restoreAfterRender(GuiGraphics graphics, int mouseX, int mouseY,
                                              float partialTick, CallbackInfo callback) {
        // Keep the candidate list off the page. Without this a multi-row list drops straight over
        // the following lines; vanilla lays a page out at this offset (convertLocalToScreen).
        ImeClientHandler.reportExclusion(
                (this.width - 192) / 2 + 36, 32, IMEHOTFIX$PAGE_WIDTH, IMEHOTFIX$PAGE_HEIGHT);

        if (this.imehotfix$swapped) {
            imehotfix$drawCompositionMarkers(graphics);
        }
        imehotfix$restore();
    }

    @Unique
    private void imehotfix$restore() {
        if (!this.imehotfix$swapped) {
            return;
        }
        this.imehotfix$swapped = false;

        TextFieldHelper editor = this.imehotfix$swappedTitle ? this.titleEdit : this.pageEdit;
        if (this.imehotfix$swappedTitle) {
            this.title = this.imehotfix$savedText;
        } else if (this.currentPage >= 0 && this.currentPage < this.pages.size()) {
            this.pages.set(this.currentPage, this.imehotfix$savedText);
            clearDisplayCache();
        }

        TextFieldHelperAccessor caret = (TextFieldHelperAccessor) editor;
        caret.imehotfix$setCursorPos(this.imehotfix$savedCursor);
        caret.imehotfix$setSelectionPos(this.imehotfix$savedSelection);
        this.imehotfix$savedText = null;
        this.imehotfix$activePreedit = null;
    }

    /**
     * Draws the clause underlines, the converting-clause highlight, and the overflow marker.
     *
     * <p>Only for page text: the signing title is a single centred line drawn by a different path.
     * </p>
     */
    @Unique
    private void imehotfix$drawCompositionMarkers(GuiGraphics graphics) {
        ImePreedit preedit = this.imehotfix$activePreedit;
        if (preedit == null || this.imehotfix$swappedTitle) {
            return;
        }
        if (this.currentPage < 0 || this.currentPage >= this.pages.size()) {
            return;
        }
        String text = this.pages.get(this.currentPage);
        if (text == null || text.isEmpty()) {
            return;
        }

        List<int[]> lines = imehotfix$splitLines(text);
        int originX = (this.width - 192) / 2 + 36;
        int originY = 32;

        int compositionStart = this.imehotfix$compositionStart;
        int compositionEnd = Math.min(compositionStart + preedit.length(), text.length());
        int overflowStart = imehotfix$overflowStart(text, lines);

        ImeOptions options = ImeSupport.options();
        List<ImePreedit.Run> runs = preedit.runs();

        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            int lineStart = lines.get(lineIndex)[0];
            int lineEnd = lines.get(lineIndex)[1];
            int y = originY + lineIndex * IMEHOTFIX$LINE_HEIGHT;

            for (int i = 0; i < runs.size(); i++) {
                ImePreedit.Run run = runs.get(i);
                int from = Math.max(compositionStart + run.start(), lineStart);
                int to = Math.min(Math.min(compositionStart + run.end(), compositionEnd), lineEnd);
                if (from >= to) {
                    continue;
                }
                int left = originX + this.font.width(text.substring(lineStart, from));
                int right = originX + this.font.width(text.substring(lineStart, to));

                if (run.style().isTarget()) {
                    graphics.fill(left, y - 1, right, y + 8, options.targetTint);
                    graphics.fill(left, y + 8, right, y + 10, options.targetUnderline);
                } else if (run.style() == PreeditStyle.INPUT_ERROR) {
                    graphics.fill(left, y + 8, right, y + 9, options.errorUnderline);
                } else {
                    graphics.fill(left, y + 8, right, y + 9, options.clauseUnderline);
                }
            }

            if (options.highlightOverflow && overflowStart >= 0) {
                int from = Math.max(Math.max(overflowStart, compositionStart), lineStart);
                int to = Math.min(compositionEnd, lineEnd);
                if (from < to) {
                    int left = originX + this.font.width(text.substring(lineStart, from));
                    int right = originX + this.font.width(text.substring(lineStart, to));
                    boolean wraps = ImeSupport.textboxOptions().bookAutoPage;
                    graphics.fill(left, y - 1, right, y + 9,
                            wraps ? options.overflowWrapTint : options.overflowDropTint);
                }
            }
        }
    }

    /** The same line breaking vanilla uses to lay a page out, as {@code {start, end}} pairs. */
    @Unique
    private List<int[]> imehotfix$splitLines(String text) {
        final List<int[]> lines = new ArrayList<int[]>();
        this.font.getSplitter().splitLines(text, IMEHOTFIX$PAGE_WIDTH, Style.EMPTY, true,
                (style, start, end) -> lines.add(new int[]{start, end}));
        return lines;
    }

    /**
     * @return index at which the page stops accepting text, or {@code -1} while it all still fits
     */
    @Unique
    private int imehotfix$overflowStart(String text, List<int[]> lines) {
        int maxLines = IMEHOTFIX$PAGE_HEIGHT / IMEHOTFIX$LINE_HEIGHT;
        int overflow = -1;

        if (lines.size() > maxLines) {
            overflow = lines.get(maxLines)[0];
        }
        if (text.length() >= IMEHOTFIX$MAX_PAGE_CHARS) {
            int byLength = IMEHOTFIX$MAX_PAGE_CHARS - 1;
            overflow = overflow < 0 ? byLength : Math.min(overflow, byLength);
        }
        return overflow;
    }

    /**
     * Picks the caret position out of the block-cursor draw call.
     *
     * <p>The layout model that knows where the caret is cannot be referenced from here, so the
     * position is taken from the vanilla draw itself. The cursor only paints on alternate frames,
     * which is fine: the IME is only told when the position actually changes.</p>
     */
    @Redirect(
            method = "renderCursor",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private void imehotfix$captureBlockCaret(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                             int color) {
        graphics.fill(x1, y1, x2, y2, color);
        ImeClientHandler.reportCaret(x1, y1 + 1, 1, IMEHOTFIX$CARET_HEIGHT);
    }

    /** Same, for the underscore drawn when the caret sits at the end of the text. */
    @Redirect(
            method = "renderCursor",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I"))
    private int imehotfix$captureEndCaret(GuiGraphics graphics, Font font, String text, int x, int y,
                                          int color, boolean dropShadow) {
        ImeClientHandler.reportCaret(x, y, 1, IMEHOTFIX$CARET_HEIGHT);
        return graphics.drawString(font, text, x, y, color, dropShadow);
    }
}
