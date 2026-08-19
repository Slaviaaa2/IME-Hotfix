package jp.antimeme.mc.imehotfix.core.win32;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef;

import java.util.Arrays;
import java.util.List;

/**
 * {@code COMPOSITIONFORM} (Windows SDK 10.0.26100.0, {@code um/imm.h}):
 *
 * <pre>
 * typedef struct tagCOMPOSITIONFORM {
 *     DWORD dwStyle;
 *     POINT ptCurrentPos;
 *     RECT  rcArea;
 * } COMPOSITIONFORM;
 * </pre>
 *
 * <p>Field order is declared through {@code getFieldOrder()} rather than
 * {@code @Structure.FieldOrder} so the same source also compiles against the JNA 4.x that ships
 * with older Minecraft versions.</p>
 */
public class CompositionForm extends Structure {

    public int dwStyle;
    public WinDef.POINT ptCurrentPos;
    public WinDef.RECT rcArea;

    public CompositionForm() {
        this.ptCurrentPos = new WinDef.POINT();
        this.rcArea = new WinDef.RECT();
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("dwStyle", "ptCurrentPos", "rcArea");
    }
}
