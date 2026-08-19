package jp.antimeme.mc.imehotfix.core.win32;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef;

import java.util.Arrays;
import java.util.List;

/**
 * {@code CANDIDATEFORM} (Windows SDK 10.0.26100.0, {@code um/imm.h}):
 *
 * <pre>
 * typedef struct tagCANDIDATEFORM {
 *     DWORD dwIndex;
 *     DWORD dwStyle;
 *     POINT ptCurrentPos;
 *     RECT  rcArea;
 * } CANDIDATEFORM;
 * </pre>
 */
public class CandidateForm extends Structure {

    public int dwIndex;
    public int dwStyle;
    public WinDef.POINT ptCurrentPos;
    public WinDef.RECT rcArea;

    public CandidateForm() {
        this.ptCurrentPos = new WinDef.POINT();
        this.rcArea = new WinDef.RECT();
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("dwIndex", "dwStyle", "ptCurrentPos", "rcArea");
    }
}
