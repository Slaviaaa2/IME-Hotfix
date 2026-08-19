package jp.antimeme.mc.imehotfix.forge.mixin;

import net.minecraft.client.gui.font.TextFieldHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the caret of the sign and book editors, so the composition can be shown where it will
 * land: the caret has to be moved past it for the duration of the draw call and put back after.
 */
@Mixin(TextFieldHelper.class)
public interface TextFieldHelperAccessor {

    @Accessor("cursorPos")
    int imehotfix$getCursorPos();

    @Accessor("cursorPos")
    void imehotfix$setCursorPos(int cursorPos);

    @Accessor("selectionPos")
    int imehotfix$getSelectionPos();

    @Accessor("selectionPos")
    void imehotfix$setSelectionPos(int selectionPos);
}
