package jp.antimeme.mc.imehotfix.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Client-only mod entry point.
 *
 * <p>Unlike the 1.20.1 port, nothing here has to talk to the operating system: Minecraft 26.2 runs
 * on a GLFW build with input-method support and hands the composition to the focused widget
 * already. What this port replaces is the presentation — vanilla draws the composition in a
 * floating box beside the field — and the parts vanilla leaves out, such as letting a search
 * filter on text that has not been confirmed yet.</p>
 */
@Mod(IMEHotfixMod.MOD_ID)
public final class IMEHotfixMod {

    public static final String MOD_ID = "imehotfix";

    public IMEHotfixMod() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ImeClientHandler.init();
        }
    }
}
