import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import expr.CellRect;
import functions.MathFunctions;
import functions.custom.NamedCellsDialog;
import functions.custom.Sheet;
import functions.custom.SheetBook;

/** Temporary probe: does "3,3 - $,$" resolve correctly on a 5x4 grid? */
public class Probe16 {
    public static void main(String[] args) throws Exception {
        SheetBook b = new SheetBook();
        Sheet s = b.add(new Sheet());
        s.install(MathFunctions.createRegistry());
        s.setName("S1");
        s.setSize(5, 4);

        Constructor<NamedCellsDialog> ctor = NamedCellsDialog.class.getDeclaredConstructor(
                java.awt.Frame.class, Sheet.class, javax.swing.JTable.class, int.class, int.class);
        ctor.setAccessible(true);
        NamedCellsDialog dlg = ctor.newInstance(null, s, new javax.swing.JTable(), -1, -1);
        Method applyEdit = NamedCellsDialog.class.getDeclaredMethod("applyEdit",
                boolean.class, String.class, expr.CellRef.class, CellRect.class,
                String.class, String.class, String.class);
        applyEdit.setAccessible(true);
        applyEdit.invoke(dlg, true, null, null, null, "AREA", "3,3", "$,$");

        CellRect r = s.namedRange("AREA");
        System.out.println("AREA = rows " + (r.getRow0() + 1) + ".." + (r.getRow1() + 1)
                + ", cols " + (r.getCol0() + 1) + ".." + (r.getCol1() + 1));
        System.exit(0);
    }
}
