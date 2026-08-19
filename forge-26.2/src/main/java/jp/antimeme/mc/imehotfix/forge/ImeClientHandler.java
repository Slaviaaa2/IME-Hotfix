package jp.antimeme.mc.imehotfix.forge;

import com.mojang.logging.LogUtils;
import jp.antimeme.mc.imehotfix.core.ExternalImeBackend;
import jp.antimeme.mc.imehotfix.core.ImeConfigFile;
import jp.antimeme.mc.imehotfix.core.ImeLogger;
import jp.antimeme.mc.imehotfix.core.ImePreedit;
import jp.antimeme.mc.imehotfix.core.ImeSupport;
import net.minecraft.client.input.PreeditEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.util.List;

/**
 * Glue between Minecraft's own input-method plumbing and the shared core.
 *
 * <p>This port needs no window hooks and no frame loop. Minecraft 26.2 runs on a GLFW build with
 * input-method support and delivers the composition straight to the focused widget, so everything
 * hangs off {@code EditBox.preeditUpdated}: the composition is converted into the core's model
 * there, and that is also the moment screens need telling that the text they can see has
 * changed.</p>
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

    private static final ExternalImeBackend BACKEND = new ExternalImeBackend();

    /** The editor the composition currently belongs to. */
    private static ImeTextTarget activeTarget;

    private ImeClientHandler() {
    }

    public static void init() {
        try {
            ImeConfigFile.load(FMLPaths.CONFIGDIR.get());
        } catch (Throwable error) {
            LOGGER.warn("[IME Hotfix] Could not load the config; continuing with defaults", error);
        }
        ImeSupport.installBackend(BACKEND, LOG_BRIDGE);
    }

    /**
     * Converts a composition the game received into the core's model and publishes it.
     *
     * @param target the editor the composition belongs to, so a commit lands in the right place
     */
    public static void submitPreedit(ImeTextTarget target, PreeditEvent event) {
        activeTarget = event == null ? null : target;
        BACKEND.setInputActive(event != null);
        BACKEND.submitPreedit(convert(event));
    }

    /**
     * Maps a {@link PreeditEvent} onto {@link ImePreedit}.
     *
     * <p>The platform reports clauses and which one has focus, but not the per-character
     * attributes the Windows IMM API exposes. A single clause is therefore treated as raw input
     * (a thin underline), and with several clauses the focused one is the conversion target.</p>
     */
    private static ImePreedit convert(PreeditEvent event) {
        if (event == null || event.fullText().isEmpty()) {
            return ImePreedit.EMPTY;
        }

        String text = event.fullText();
        List<String> blocks = event.blocks();
        byte[] attributes = new byte[text.length()];
        int[] clauses = new int[blocks.size() + 1];

        int offset = 0;
        for (int i = 0; i < blocks.size(); i++) {
            clauses[i] = offset;
            byte attribute;
            if (blocks.size() == 1) {
                attribute = 0; // ATTR_INPUT
            } else if (i == event.focusedBlock()) {
                attribute = 1; // ATTR_TARGET_CONVERTED
            } else {
                attribute = 2; // ATTR_CONVERTED
            }

            int length = blocks.get(i).length();
            for (int j = 0; j < length && offset < attributes.length; j++) {
                attributes[offset++] = attribute;
            }
        }
        clauses[blocks.size()] = offset;

        return new ImePreedit(text, event.caretPosition(), attributes, clauses);
    }

    // ------------------------------------------------------------------------------------
    // commit and change notification
    // ------------------------------------------------------------------------------------

    /**
     * Commits an in-flight composition into the editor it was typed in, then clears it.
     *
     * <p>Asking the platform to confirm it would deliver the characters a frame later, by which
     * point focus may sit elsewhere, so the visible text is inserted directly instead.</p>
     */
    public static void commitCompositionInto(ImeTextTarget target) {
        if (!ImeSupport.isComposing() || !ImeSupport.options().commitCompositionOnBlur) {
            return;
        }

        String composed = ImeSupport.preedit().text();
        ImeSupport.cancelComposition();

        if (target != null && !composed.isEmpty()) {
            target.imehotfix$insertCommitted(composed);
        }
    }

    public static void commitCompositionIntoActiveTarget() {
        commitCompositionInto(activeTarget);
    }

    public static boolean isActiveTarget(ImeTextTarget target) {
        return activeTarget == target;
    }

}
