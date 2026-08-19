package jp.antimeme.mc.imehotfix.core;

/**
 * Stand-in used on platforms with no implementation yet (Linux/macOS) and whenever the native
 * hook could not be installed. Every call is inert, so callers never need a null check.
 */
public final class NoopImeBackend implements ImeBackend {

    public static final NoopImeBackend INSTANCE = new NoopImeBackend();

    private NoopImeBackend() {
    }

    @Override
    public boolean attach(long nativeWindowHandle) {
        return false;
    }

    @Override
    public void detach() {
    }

    @Override
    public boolean isAttached() {
        return false;
    }

    @Override
    public void setInputActive(boolean active) {
    }

    @Override
    public boolean isInputActive() {
        return false;
    }

    @Override
    public ImePreedit preedit() {
        return ImePreedit.EMPTY;
    }

    @Override
    public boolean isComposing() {
        return false;
    }

    @Override
    public void setCaretRect(int x, int y, int width, int height) {
    }

    @Override
    public void setExclusionRect(int x, int y, int width, int height) {
    }

    @Override
    public void cancelComposition() {
    }

    @Override
    public String describe() {
        return "none";
    }
}
