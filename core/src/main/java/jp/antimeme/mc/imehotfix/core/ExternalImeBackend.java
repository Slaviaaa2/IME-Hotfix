package jp.antimeme.mc.imehotfix.core;

/**
 * Backend for platforms where the game already receives the composition and only the presentation
 * needs replacing.
 *
 * <p>Minecraft 26.1+ ships on a GLFW build with input-method support and delivers the composition
 * itself, so there is nothing to hook: the port feeds what the game received into
 * {@link #submitPreedit} and everything downstream — the inline rendering, the overflow marking,
 * the config — is shared with the ports that have to talk to the OS directly.</p>
 *
 * <p>Caret reporting and enabling/disabling the IME are no-ops here, because the game's own
 * plumbing already handles both.</p>
 */
public final class ExternalImeBackend implements ImeBackend {

    private volatile boolean attached;
    private volatile boolean inputActive;
    private volatile ImePreedit preedit = ImePreedit.EMPTY;

    /** Publishes a composition the game handed us. Pass {@link ImePreedit#EMPTY} to clear it. */
    public void submitPreedit(ImePreedit composition) {
        this.preedit = composition == null ? ImePreedit.EMPTY : composition;
    }

    @Override
    public boolean attach(long nativeWindowHandle) {
        this.attached = true;
        return true;
    }

    @Override
    public void detach() {
        this.attached = false;
        this.preedit = ImePreedit.EMPTY;
    }

    @Override
    public boolean isAttached() {
        return this.attached;
    }

    @Override
    public void setInputActive(boolean active) {
        this.inputActive = active;
        if (!active) {
            this.preedit = ImePreedit.EMPTY;
        }
    }

    @Override
    public boolean isInputActive() {
        return this.inputActive;
    }

    @Override
    public ImePreedit preedit() {
        return this.preedit;
    }

    @Override
    public boolean isComposing() {
        return !this.preedit.isEmpty();
    }

    @Override
    public void setCaretRect(int x, int y, int width, int height) {
        // The game reports the text input area to the platform itself.
    }

    @Override
    public void setExclusionRect(int x, int y, int width, int height) {
        // As above.
    }

    @Override
    public void cancelComposition() {
        this.preedit = ImePreedit.EMPTY;
    }

    @Override
    public String describe() {
        return "game-provided (GLFW input method)";
    }
}
