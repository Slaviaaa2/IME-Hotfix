package jp.antimeme.mc.imehotfix.core.win32;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;

/**
 * The three {@code user32.dll} entry points needed to subclass GLFW's window procedure.
 *
 * <p>JNA's bundled {@code com.sun.jna.platform.win32.User32} declares {@code SetWindowLongPtr}
 * only with a {@code Pointer} payload, so a callback cannot be handed to it directly; this
 * interface adds the overload that takes a {@link WinUser.WindowProc}. Names are the exact
 * exported symbols — the wide variants — with no function mapper in play.</p>
 */
interface User32Ex extends StdCallLibrary {

    User32Ex INSTANCE = Native.load("user32", User32Ex.class);

    /** @return the previous window procedure, or {@code null} on failure */
    Pointer SetWindowLongPtrW(WinDef.HWND hWnd, int nIndex, WinUser.WindowProc newProc);

    /** @return the previous window procedure, or {@code null} on failure */
    Pointer SetWindowLongPtrW(WinDef.HWND hWnd, int nIndex, Pointer newProc);

    WinDef.LRESULT CallWindowProcW(Pointer previousProc, WinDef.HWND hWnd, int msg,
                                   WinDef.WPARAM wParam, WinDef.LPARAM lParam);
}
