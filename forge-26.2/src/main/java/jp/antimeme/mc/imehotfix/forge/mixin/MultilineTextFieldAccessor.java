package jp.antimeme.mc.imehotfix.forge.mixin;

import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets the book's page text be swapped for "text with the composition spliced in" during a draw
 * call and put back afterwards.
 *
 * <p>{@code setValue} cannot be used for that: it notifies the value listener, which for a book
 * means marking the page as edited. Writing the field and re-flowing the lines directly does the
 * same layout work without telling anyone the book changed.</p>
 */
@Mixin(MultilineTextField.class)
public interface MultilineTextFieldAccessor {

    @Accessor("value")
    String imehotfix$getValue();

    @Accessor("value")
    void imehotfix$setValue(String value);

    @Accessor("cursor")
    int imehotfix$getCursor();

    @Accessor("cursor")
    void imehotfix$setCursor(int cursor);

    @Accessor("selectCursor")
    int imehotfix$getSelectCursor();

    @Accessor("selectCursor")
    void imehotfix$setSelectCursor(int selectCursor);

    /** Recomputes the wrapped lines without firing the value listener. */
    @Invoker("reflowDisplayLines")
    void imehotfix$reflow();

    /** @return {@code true} when this text would not fit the widget's line limit */
    @Invoker("overflowsLineLimit")
    boolean imehotfix$overflowsLineLimit(String value);

    /** Wrapping width, needed to reproduce the line breaks the field itself computes. */
    @Accessor("width")
    int imehotfix$getWrapWidth();

    @Accessor("lineLimit")
    int imehotfix$getLineLimit();
}
