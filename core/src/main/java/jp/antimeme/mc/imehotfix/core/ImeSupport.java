package jp.antimeme.mc.imehotfix.core;

import java.util.Locale;

/**
 * Entry point shared by every loader/version port.
 *
 * <p>A port only has to do four things:</p>
 * <ol>
 *   <li>{@link #install(long, ImeLogger)} once the game window exists,</li>
 *   <li>call {@link #setInputActive(boolean)} as text fields gain and lose focus,</li>
 *   <li>call {@link #setCaretRect(int, int, int, int)} with the caret in native client pixels,</li>
 *   <li>draw {@link #preedit()} wherever the caret is.</li>
 * </ol>
 */
public final class ImeSupport {

    private static final ImeOptions OPTIONS = new ImeOptions();
    private static final TextboxOptions TEXTBOX_OPTIONS = new TextboxOptions();

    private static volatile ImeBackend backend = NoopImeBackend.INSTANCE;
    private static volatile ImeLogger logger = ImeLogger.NOOP;

    private ImeSupport() {
    }

    public static ImeOptions options() {
        return OPTIONS;
    }

    /** Settings for the "Textbox Improvements" features, which work independently of the IME. */
    public static TextboxOptions textboxOptions() {
        return TEXTBOX_OPTIONS;
    }

    public static ImeLogger logger() {
        return logger;
    }

    /**
     * Installs the platform backend on the given native window handle.
     *
     * @param nativeWindowHandle {@code GLFWNativeWin32.glfwGetWin32Window(window)} on Windows
     * @return {@code true} when a real backend took over
     */
    public static synchronized boolean install(long nativeWindowHandle, ImeLogger log) {
        logger = log == null ? ImeLogger.NOOP : log;

        if (backend.isAttached()) {
            logger.debug("IME backend already installed; ignoring duplicate install()");
            return true;
        }
        if (nativeWindowHandle == 0L) {
            logger.warn("Refusing to install the IME backend: native window handle is 0", null);
            return false;
        }

        ImeBackend candidate = createBackend();
        if (candidate.attach(nativeWindowHandle)) {
            backend = candidate;
            logger.info("IME support active (" + candidate.describe() + ")");
            return true;
        }

        backend = NoopImeBackend.INSTANCE;
        logger.warn("Could not install the IME backend; leaving vanilla input alone", null);
        return false;
    }

    public static synchronized void uninstall() {
        ImeBackend current = backend;
        backend = NoopImeBackend.INSTANCE;
        current.detach();
    }

    public static boolean isActive() {
        return backend.isAttached();
    }

    public static String describeBackend() {
        return backend.describe();
    }

    public static void setInputActive(boolean active) {
        backend.setInputActive(active);
    }

    public static boolean isInputActive() {
        return backend.isInputActive();
    }

    public static ImePreedit preedit() {
        return backend.preedit();
    }

    public static boolean isComposing() {
        return backend.isComposing();
    }

    public static void setCaretRect(int x, int y, int width, int height) {
        backend.setCaretRect(x, y, width, height);
    }

    /** Area the candidate list should avoid covering, in native window client pixels. */
    public static void setExclusionRect(int x, int y, int width, int height) {
        backend.setExclusionRect(x, y, width, height);
    }

    public static void cancelComposition() {
        backend.cancelComposition();
    }

    private static ImeBackend createBackend() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            try {
                return (ImeBackend) Class
                        .forName("jp.antimeme.mc.imehotfix.core.win32.Win32ImeBackend")
                        .getConstructor()
                        .newInstance();
            } catch (Throwable error) {
                // Reflection keeps the JNA-dependent classes off the verification path on
                // platforms that will never use them.
                logger.warn("Windows IME backend unavailable", error);
                return NoopImeBackend.INSTANCE;
            }
        }

        logger.info("No IME backend for this platform yet (os.name=" + os + ")");
        return NoopImeBackend.INSTANCE;
    }
}
