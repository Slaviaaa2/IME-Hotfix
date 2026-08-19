package jp.antimeme.mc.imehotfix.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Plain {@code .properties} config, deliberately independent of ForgeConfigSpec, Fabric's config
 * API and everything else loader-shaped, so that every port reads and writes the same file.
 */
public final class ImeConfigFile {

    private static final String FILE_NAME = "imehotfix.properties";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static Path file;

    private ImeConfigFile() {
    }

    /** Reads {@code <configDirectory>/imehotfix.properties}, writing defaults when absent. */
    public static synchronized void load(Path configDirectory) {
        file = configDirectory.resolve(FILE_NAME);

        if (!Files.exists(file)) {
            save();
            return;
        }

        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = Files.newInputStream(file);
            properties.load(in);
        } catch (IOException error) {
            ImeSupport.logger().warn("Could not read " + file + "; using defaults", error);
            return;
        } finally {
            closeQuietly(in);
        }

        ImeOptions options = ImeSupport.options();
        options.disableImeOutsideTextFields =
                bool(properties, "disableImeOutsideTextFields", options.disableImeOutsideTextFields);
        options.suppressSystemCompositionWindow =
                bool(properties, "suppressSystemCompositionWindow", options.suppressSystemCompositionWindow);
        options.inlinePreedit =
                bool(properties, "inlinePreedit", options.inlinePreedit);
        options.pinCandidateWindowToCaret =
                bool(properties, "pinCandidateWindowToCaret", options.pinCandidateWindowToCaret);
        options.filterWithComposition =
                bool(properties, "filterWithComposition", options.filterWithComposition);
        options.commitCompositionOnBlur =
                bool(properties, "commitCompositionOnBlur", options.commitCompositionOnBlur);
        options.cancelCompositionOnFocusLoss =
                bool(properties, "cancelCompositionOnFocusLoss", options.cancelCompositionOnFocusLoss);
        options.verboseLogging =
                bool(properties, "verboseLogging", options.verboseLogging);

        // Rewrite the file so that keys added by a newer version show up with their defaults and
        // their explanations, instead of silently not existing.
        save();
    }

    public static synchronized void save() {
        if (file == null) {
            return;
        }

        ImeOptions options = ImeSupport.options();
        BufferedWriter writer = null;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writer = Files.newBufferedWriter(file, UTF_8);

            writer.write("# IME Hotfix configuration");
            writer.newLine();
            writer.newLine();

            comment(writer, "Detach the IME from the window while no text field is focused.");
            comment(writer, "Leave this on: while the IME owns the keyboard, Windows reports every");
            comment(writer, "key as VK_PROCESSKEY and GLFW drops it, so WASD would stop working.");
            entry(writer, "disableImeOutsideTextFields", options.disableImeOutsideTextFields);

            comment(writer, "Hide the composition window Windows draws itself. The composition text");
            comment(writer, "is rendered inside the text field instead. Turn off if your IME refuses");
            comment(writer, "to show conversion candidates without its own window.");
            entry(writer, "suppressSystemCompositionWindow", options.suppressSystemCompositionWindow);

            comment(writer, "Draw the composition text in the field at the caret. With this off the");
            comment(writer, "composition is not drawn by the mod at all.");
            entry(writer, "inlinePreedit", options.inlinePreedit);

            comment(writer, "Keep the candidate list anchored under the caret.");
            entry(writer, "pinCandidateWindowToCaret", options.pinCandidateWindowToCaret);

            comment(writer, "Let searches react to the composition before it is confirmed, so");
            comment(writer, "typing a partial word already narrows the results. The stored value");
            comment(writer, "is never modified - the field only reports the composition as part");
            comment(writer, "of its value while one is in flight.");
            entry(writer, "filterWithComposition", options.filterWithComposition);

            comment(writer, "When focus leaves a text field mid-composition (clicking an item,");
            comment(writer, "tabbing away, clicking outside the field), commit what is on screen");
            comment(writer, "into the field being left. With this off the composition stays alive");
            comment(writer, "and follows the caret into whatever gains focus next.");
            entry(writer, "commitCompositionOnBlur", options.commitCompositionOnBlur);

            comment(writer, "Discard a composition still in flight once no text field wants input");
            comment(writer, "at all, e.g. because the screen closed and there is nowhere to");
            comment(writer, "commit it to.");
            entry(writer, "cancelCompositionOnFocusLoss", options.cancelCompositionOnFocusLoss);

            comment(writer, "Log every IME window message. Very noisy; for diagnosing only.");
            entry(writer, "verboseLogging", options.verboseLogging);
        } catch (IOException error) {
            ImeSupport.logger().warn("Could not write " + file, error);
        } finally {
            closeQuietly(writer);
        }
    }

    private static void comment(BufferedWriter writer, String text) throws IOException {
        writer.write("# " + text);
        writer.newLine();
    }

    private static void entry(BufferedWriter writer, String key, boolean value) throws IOException {
        writer.write(key + "=" + value);
        writer.newLine();
        writer.newLine();
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        raw = raw.trim();
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        return fallback;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing useful to do about a failed close of a config file.
        }
    }
}
