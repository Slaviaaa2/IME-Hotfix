package jp.antimeme.mc.imehotfix.core;

/**
 * Minimal logging seam.
 *
 * <p>The core deliberately avoids log4j, SLF4J and anything Minecraft-shaped so that the exact
 * same sources can be compiled into every loader/version port without modification.</p>
 */
public interface ImeLogger {

    ImeLogger NOOP = new ImeLogger() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message, Throwable error) {
        }

        @Override
        public void debug(String message) {
        }
    };

    void info(String message);

    void warn(String message, Throwable error);

    void debug(String message);
}
