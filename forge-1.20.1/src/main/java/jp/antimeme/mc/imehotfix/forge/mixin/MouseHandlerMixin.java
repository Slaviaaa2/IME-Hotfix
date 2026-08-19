package jp.antimeme.mc.imehotfix.forge.mixin;

import jp.antimeme.mc.imehotfix.forge.ImeClientHandler;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Settles an in-flight composition the moment the player clicks anything.
 *
 * <p>This is what desktop text fields do: clicking away from a field mid-conversion commits what
 * is on screen rather than throwing it away. In Minecraft the click may not even move focus —
 * clicking an item in the creative inventory leaves the search box focused — so hooking focus
 * changes alone is not enough.</p>
 *
 * <p>Runs before the click is dispatched to the screen, so the text lands in the box it was typed
 * in, before anything has a chance to move focus elsewhere.</p>
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onPress", at = @At("HEAD"))
    private void imehotfix$commitBeforeClick(long window, int button, int action, int modifiers,
                                             CallbackInfo callback) {
        if (action == GLFW.GLFW_PRESS) {
            ImeClientHandler.commitCompositionIntoActiveTarget();
        }
    }
}
