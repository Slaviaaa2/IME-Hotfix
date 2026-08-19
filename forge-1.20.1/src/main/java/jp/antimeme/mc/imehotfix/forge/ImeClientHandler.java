package jp.antimeme.mc.imehotfix.forge;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import jp.antimeme.mc.imehotfix.core.ImeConfigFile;
import jp.antimeme.mc.imehotfix.core.ImeLogger;
import jp.antimeme.mc.imehotfix.core.ImeSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import jp.antimeme.mc.imehotfix.forge.mixin.KeyboardHandlerAccessor;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Glue between Minecraft's frame loop and the platform-agnostic core.
 *
 * <p>Two jobs:</p>
 * <ul>
 *   <li>decide, once per rendered frame, whether any text field wants IME input, so the backend
 *       can attach or detach the input context;</li>
 *   <li>translate caret positions from GUI space into native window client pixels.</li>
 * </ul>
 */
public final class ImeClientHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ImeLogger LOG_BRIDGE = new ImeLogger() {
        @Override
        public void info(String message) {
            LOGGER.info("[IME Hotfix] {}", message);
        }

        @Override
        public void warn(String message, Throwable error) {
            if (error == null) {
                LOGGER.warn("[IME Hotfix] {}", message);
            } else {
                LOGGER.warn("[IME Hotfix] {}", message, error);
            }
        }

        @Override
        public void debug(String message) {
            LOGGER.debug("[IME Hotfix] {}", message);
        }
    };

    private static boolean installAttempted;

    /** Frame counter; a text field claiming focus stamps {@link #activeFrame} with it. */
    private static int frame;
    private static int activeFrame = -1;

    /**
     * The editor that claimed IME focus this frame, so a composition can be committed into the
     * place it was actually typed. Cleared as soon as nothing claims focus, which keeps this from
     * pinning a screen's widgets in memory.
     */
    @Nullable
    private static ImeTextTarget activeTarget;

    /**
     * Composition text as the screens currently see it through {@code EditBox.getValue()}. It
     * lags one step behind the IME on purpose — see {@link #publishCompositionChange()}.
     */
    private static String publishedComposition = "";

    /** The value {@link #publishedComposition} moves to once the screen has read the old one. */
    private static String pendingComposition = "";

    /** True only while the synthetic character event below is being dispatched. */
    private static boolean publishingComposition;

    private ImeClientHandler() {
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (!installAttempted) {
            installAttempted = true;
            install();
        }

        if (!ImeSupport.isActive()) {
            return;
        }

        // Widgets stamp activeFrame from their render call, which happens between the START and
        // END phases of this same frame.
        boolean active = activeFrame == frame;
        if (!active) {
            activeTarget = null;
        }
        ImeSupport.setInputActive(active);
        publishCompositionChange();
        frame++;
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        ImeSupport.uninstall();
    }

    /** Called from the mixins while a focused, editable text target is being drawn. */
    public static void markTextInputActive(ImeTextTarget target) {
        activeTarget = target;
        activeFrame = frame;
    }

    /**
     * Commits an in-flight composition into the field it was typed in, then clears it.
     *
     * <p>The composition is cancelled rather than completed on the IME side, and the text is
     * inserted directly. Letting the IME commit it would deliver the characters as WM_CHAR a
     * frame later — by which point focus may sit on a different text box, which is exactly how
     * half-typed text used to walk from JEI's search field into the creative inventory's.</p>
     */
    public static void commitCompositionInto(@Nullable ImeTextTarget target) {
        if (!ImeSupport.isActive() || !ImeSupport.isComposing()) {
            return;
        }
        if (!ImeSupport.options().commitCompositionOnBlur) {
            return;
        }

        String composed = ImeSupport.preedit().text();
        ImeSupport.cancelComposition();

        // Stop reporting the composition as part of the field's value before inserting it for
        // real. Otherwise the responder that insertText fires would be handed the committed text
        // with the now-stale composition still appended to it.
        publishedComposition = "";
        pendingComposition = "";

        if (target != null && !composed.isEmpty()) {
            target.imehotfix$insertCommitted(composed);
        }
    }

    /** Commits into whichever editor currently holds IME focus. Used on mouse press. */
    public static void commitCompositionIntoActiveTarget() {
        commitCompositionInto(activeTarget);
    }

    /** @return {@code true} if this is the editor the IME is currently feeding. */
    public static boolean isActiveTarget(ImeTextTarget target) {
        return activeTarget == target;
    }

    /**
     * @return {@code true} while dispatching the synthetic character event, i.e. the text field
     * should report success without actually inserting anything.
     */
    public static boolean isPublishingComposition() {
        return publishingComposition;
    }

    /** Composition text that text fields should currently report as part of their value. */
    public static String publishedComposition() {
        return publishedComposition;
    }

    /**
     * Moves the published composition forward, called from inside the synthetic character event.
     *
     * <p>The screen samples the field's value before and after {@code charTyped} and only acts
     * when the two differ, so the new composition has to become visible exactly here: between
     * those two reads.</p>
     */
    public static void advancePublishedComposition() {
        publishedComposition = pendingComposition;
    }

    /**
     * Tells the open screen that the text changed whenever the composition does.
     *
     * <p>Screens do not watch text fields for changes; the creative inventory, for one, only
     * compares {@code searchBox.getValue()} before and after {@code charTyped} and refreshes if
     * they differ. Since IME keystrokes never reach {@code charTyped} — Windows reports them as
     * {@code VK_PROCESSKEY} and GLFW drops them — nothing would ever re-run the search while the
     * player is still converting. Dispatching one synthetic character event per composition
     * change puts the screen back on its normal code path; the field reports the composition as
     * part of its value (see the EditBox mixin) so the before/after comparison sees a difference,
     * and swallows the synthetic character so nothing is actually typed.</p>
     */
    private static void publishCompositionChange() {
        if (!ImeSupport.options().filterWithComposition) {
            return;
        }

        String composition = ImeSupport.isComposing() ? ImeSupport.preedit().text() : "";
        if (composition.equals(publishedComposition)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft.screen;
        if (activeTarget == null || screen == null
                || !activeTarget.imehotfix$reportsCompositionInValue()) {
            // Nobody to tell, or an editor that draws the composition itself and would take the
            // synthetic character as literal input. Adopt the new text so the next real change is
            // still detected.
            publishedComposition = composition;
            return;
        }

        pendingComposition = composition;
        publishingComposition = true;
        try {
            ((KeyboardHandlerAccessor) minecraft.keyboardHandler)
                    .imehotfix$charTyped(minecraft.getWindow().getWindow(), ' ', 0);
        } catch (Throwable error) {
            LOGGER.warn("[IME Hotfix] Could not publish the composition to the screen", error);
        } finally {
            publishingComposition = false;
            // If the event never reached a text field, adopt it anyway rather than retrying
            // every frame.
            publishedComposition = pendingComposition;
        }
    }

    /**
     * Reports the caret rectangle in GUI coordinates; converts to the native client pixels the
     * IME expects.
     */
    public static void reportCaret(int guiX, int guiY, int guiWidth, int guiHeight) {
        if (!ImeSupport.isActive()) {
            return;
        }
        int[] rect = toClientPixels(guiX, guiY, guiWidth, guiHeight);
        ImeSupport.setCaretRect(rect[0], rect[1], rect[2], rect[3]);
    }

    /**
     * Reports the area the candidate list should not cover, in GUI coordinates.
     *
     * <p>Telling the IME only where the caret is leaves it free to drop a multi-row candidate list
     * straight over the following lines of text. Screens that lay text out over an area — the book
     * editor above all — report that whole area here so the list is placed outside it.</p>
     */
    public static void reportExclusion(int guiX, int guiY, int guiWidth, int guiHeight) {
        if (!ImeSupport.isActive()) {
            return;
        }
        int[] rect = toClientPixels(guiX, guiY, guiWidth, guiHeight);
        ImeSupport.setExclusionRect(rect[0], rect[1], rect[2], rect[3]);
    }

    /** GUI coordinates to native window client pixels: {x, y, width, height}. */
    private static int[] toClientPixels(int guiX, int guiY, int guiWidth, int guiHeight) {
        Window window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();

        // GUI units scale up to framebuffer pixels; on a HiDPI setup the framebuffer is larger
        // than the window's client area, and the IME wants client pixels.
        double ratioX = window.getWidth() > 0
                ? (double) window.getScreenWidth() / (double) window.getWidth()
                : 1.0D;
        double ratioY = window.getHeight() > 0
                ? (double) window.getScreenHeight() / (double) window.getHeight()
                : 1.0D;

        return new int[]{
                (int) Math.round(guiX * scale * ratioX),
                (int) Math.round(guiY * scale * ratioY),
                Math.max(1, (int) Math.round(guiWidth * scale * ratioX)),
                Math.max(1, (int) Math.round(guiHeight * scale * ratioY))
        };
    }

    private static void install() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            LOGGER.info("[IME Hotfix] No IME backend for this platform yet (os.name={})", os);
            return;
        }

        try {
            ImeConfigFile.load(FMLPaths.CONFIGDIR.get());
        } catch (Throwable error) {
            LOGGER.warn("[IME Hotfix] Could not load the config; continuing with defaults", error);
        }

        long glfwWindow = Minecraft.getInstance().getWindow().getWindow();
        long hwnd;
        try {
            hwnd = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
        } catch (Throwable error) {
            LOGGER.warn("[IME Hotfix] Could not resolve the native window handle", error);
            return;
        }

        ImeSupport.install(hwnd, LOG_BRIDGE);
    }
}
