package jp.antimeme.mc.imehotfix.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Client-only mod entry point. Everything interesting lives in {@link ImeClientHandler} and the
 * {@code EditBox} mixin; this class exists solely to get them registered.
 */
@Mod(IMEHotfixMod.MOD_ID)
public final class IMEHotfixMod {

    public static final String MOD_ID = "imehotfix";

    public IMEHotfixMod() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.register(ImeClientHandler.class);
        }
    }
}
