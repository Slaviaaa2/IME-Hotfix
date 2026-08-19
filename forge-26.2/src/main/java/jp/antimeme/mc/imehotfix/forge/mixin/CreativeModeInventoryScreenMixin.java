package jp.antimeme.mc.imehotfix.forge.mixin;

import jp.antimeme.mc.imehotfix.core.ImeSupport;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.PreeditEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Re-runs the item search while the player is still converting.
 *
 * <p>The screen only refreshes its results from {@code charTyped}, comparing the search box's
 * value before and after. Input-method keystrokes never reach {@code charTyped}, so vanilla shows
 * stale results until the text is confirmed. The composition does arrive here though, and by this
 * point the search box already reports it as part of its value, so refreshing is all that is
 * missing.</p>
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenMixin {

    @Shadow
    private void refreshSearchResults() {
        throw new AssertionError("shadow");
    }

    @Inject(method = "preeditUpdated", at = @At("RETURN"))
    private void imehotfix$refreshOnComposition(PreeditEvent event,
                                                CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() && ImeSupport.options().filterWithComposition) {
            refreshSearchResults();
        }
    }
}
