package jp.antimeme.mc.imehotfix.forge.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens up {@code KeyboardHandler.charTyped} so the mod can dispatch a synthetic character event
 * when the composition changes.
 *
 * <p>Going through the handler rather than calling {@code Screen.charTyped} directly matters:
 * this is the path that fires Forge's {@code ScreenEvent.CharacterTyped} hooks, which is how mods
 * such as JEI observe typing at all.</p>
 */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {

    @Invoker("charTyped")
    void imehotfix$charTyped(long window, int codepoint, int modifiers);
}
