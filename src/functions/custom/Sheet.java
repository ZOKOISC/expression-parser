package functions.custom;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import expr.CellRef;
import expr.DataType;
import expr.Node;
import functions.FunctionRegistry;

public class Sheet implements CellProvider {

    private String[][] cells = new String[0][0];
    private final Map<String, Cell> cellMap = new LinkedHashMap<>();

    public static String cellKey(int row, int jtableCol) {
        return new CellRef(row, jtableCol - 1).toString();
    }

    public void setSize(int rows, int cols) {
        cells = new String[rows][cols];
        cellMap.clear();
    }

    @Override
    public int rows() {
        return cells.length;
    }

    @Override
    public int cols() {
        return cells.length == 0 ? 0 : cells[0].length;
    }

    @Override
    public Cell at(int row, int col) {
        CellRef key = new CellRef(row - 1, col - 1);
        Cell m = cellMap.get(key.toString());
        if (m != null) {
            return m;
        }
        Cell parsed = Cell.parse(getRawValue(row - 1, col));
        return parsed.isEmpty() ? null : parsed;
    }

    public String getRawValue(int row, int col) {
        if (col == 0 || row < 0 || row >= cells.length) return "";
        String s = cells[row][col - 1];
        return s == null ? "" : s;
    }

    public void setRawValue(int row, int col, String value) {
        if (col > 0 && row >= 0 && row < cells.length) {
            cells[row][col - 1] = String.valueOf(value).trim();
        }
    }

    public Cell registeredCell(int row, int jtableCol) {
        return cellMap.get(cellKey(row, jtableCol));
    }

    public Cell cell(int row, int jtableCol) {
        String key = cellKey(row, jtableCol);
        Cell c = cellMap.get(key);
        if (c == null) {
            c = Cell.parse(getRawValue(row, jtableCol));
            cellMap.put(key, c);
        }
        return c;
    }

    public void saveCell(int row, int col, String text, String rawExpression, Node node,
                         DataType type, Set<CellRef> referenced) {
        setRawValue(row, col, text == null ? "" : text);
        Cell saved = Cell.parse(text == null ? "" : text);
        saved.setRawExpression(rawExpression);
        saved.setType(type);
        saved.setExpression(node);
        saved.setReferenced(referenced);
        Cell prev = cellMap.get(cellKey(row, col));
        if (prev != null) {
            saved.setDependents(prev.getDependents());
        }
        if (referenced != null) {
            CellRef selfRef = new CellRef(row, col - 1);
            for (CellRef ref : referenced) {
                String key = ref.toString();
                Cell referencedCell = cellMap.get(key);
                if (referencedCell == null) {
                    referencedCell = new Cell(ref, getRawValue(ref.getRow(), ref.getCol() + 1));
                    cellMap.put(key, referencedCell);
                } else if (referencedCell.getExpression() == null) {
                    referencedCell.setExpression(referencedCell.constantNode());
                }
                referencedCell.addDependency(selfRef, cellMap);
            }
        }
        cellMap.put(cellKey(row, col), saved);
    }

    public void recomputeDependentsOnEdit(int row, int col, Map<String, Object> bindings, FunctionRegistry registry) {
        Cell edited = cellMap.get(cellKey(row, col));
        if (edited == null) {
            return;
        }
        edited.recalculate(new CellRef(row, col - 1), cellMap, bindings, registry);
        Set<String> done = new LinkedHashSet<>();
        ArrayDeque<CellRef> todo = new ArrayDeque<>();
        todo.push(new CellRef(row, col - 1));
        while (!todo.isEmpty()) {
            CellRef r = todo.pop();
            if (!done.add(r.toString())) {
                continue;
            }
            Cell c = cellMap.get(r.toString());
            if (c != null) {
                setRawValue(r.getRow(), r.getCol() + 1, c.display());
                for (CellRef d : c.getDependents()) {
                    todo.push(d);
                }
            }
        }
    }
}