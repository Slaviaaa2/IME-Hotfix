package jp.antimeme.mc.imehotfix.forge.mixin;

import jp.antimeme.mc.imehotfix.forge.ImeOverflowAware;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Auto-paging for the book-and-quill: text that will not fit the current page moves to the next
 * one, adding a page if needed, instead of being dropped.
 *
 * <p>The page is a general-purpose text area that knows nothing about books, so it reports the
 * overflow and this screen — which does have {@code pageForward()} — decides what happens.</p>
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenMixin {

    @Shadow
    private MultiLineEditBox page;

    @Shadow
    private void pageForward() {
        throw new AssertionError("shadow");
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void imehotfix$installOverflowHandler(CallbackInfo callback) {
        if (this.page instanceof ImeOverflowAware overflowAware) {
            overflowAware.imehotfix$setOverflowHandler(this::pageForward);
        }
    }
}
