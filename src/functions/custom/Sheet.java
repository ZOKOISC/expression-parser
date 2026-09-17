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
    private Map<String, Cell> cellMap = new LinkedHashMap<>();
    private final Map<String, Object> bindings = new LinkedHashMap<>();
    private FunctionRegistry registry;
    private SheetBook book;

    public Sheet() {
    }

    public Sheet(SheetBook book) {
        this.book = book;
        this.cellMap = book.cellMap();
    }

    public void setSheetBook(SheetBook book) {
        this.book = book;
        this.cellMap = book.cellMap();
    }

    public int sheetIndex() {
        return book == null ? 0 : book.indexOf(this);
    }

    private String cellKey(int sheet, int row, int jtableCol) {
        return new CellRef(sheet, row, jtableCol - 1).toString();
    }

    public void install(FunctionRegistry registry) {
        this.registry = registry;
        registry.register(book == null ? new ArrayGetFunction(this) : new ArrayGetFunction(this, book));
    }

    public FunctionRegistry registry() {
        return registry;
    }

    public Map<String, Object> bindings() {
        return bindings;
    }

    public void setBindingsText(String text) {
        bindings.clear();
        bindings.putAll(parseBindings(text));
    }

    public void setSize(int rows, int cols) {
        cells = new String[rows][cols];
    }

    public void close() {
        cells = new String[0][0];
        String prefix = "S" + (sheetIndex() + 1) + "(";
        cellMap.keySet().removeIf(k -> k.startsWith(prefix));
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
        CellRef key = new CellRef(sheetIndex(), row - 1, col - 1);
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
        return cellMap.get(cellKey(sheetIndex(), row, jtableCol));
    }

    public Cell cell(int row, int jtableCol) {
        String key = cellKey(sheetIndex(), row, jtableCol);
        Cell c = cellMap.get(key);
        if (c == null) {
            c = Cell.parse(getRawValue(row, jtableCol));
            cellMap.put(key, c);
        }
        return c;
    }

    public Cell handleCellUpdate(int row, int col, String raw) {
        Cell existing = registeredCell(row, col);
        if (existing != null) {
            existing.updateValue(raw);
            return existing;
        }
        return Cell.parse(raw);
    }

    public void convertCell(int row, int col, DataType dt) {
        Cell cell = cell(row, col);
        Cell converted = cell.convertTo(dt);
        String storedText = converted.getType() == DataType.STRING
                ? "\"" + converted.display() + "\""
                : converted.getTextValue();
        setRawValue(row, col, storedText);
        cell.updateValue(storedText);
        recomputeDependentsOnEdit(row, col);
    }

    public void clearCell(int row, int col) {
        setRawValue(row, col, "");
        cell(row, col).updateValue("");
        recomputeDependentsOnEdit(row, col);
    }

    public void saveCell(int row, int col, String text, String rawExpression, Node node,
                         DataType type, Set<CellRef> referenced) {
        setRawValue(row, col, text == null ? "" : text);
        Cell saved = Cell.parse(text == null ? "" : text);
        saved.setRawExpression(rawExpression);
        saved.setType(type);
        saved.setExpression(node);
        CellRef selfRef = new CellRef(sheetIndex(), row, col - 1);
        Set<CellRef> normalized = new LinkedHashSet<>();
        if (referenced != null) {
            for (CellRef rawRef : referenced) {
                normalized.add(rawRef.normalize(sheetIndex()));
            }
        }
        saved.setReferenced(normalized);
        Cell prev = cellMap.get(cellKey(sheetIndex(), row, col));
        if (prev != null) {
            saved.setDependents(prev.getDependents());
        }
        for (CellRef ref : normalized) {
            String key = ref.toString();
            Cell referencedCell = cellMap.get(key);
            if (referencedCell == null) {
                referencedCell = new Cell(ref, rawFor(ref));
                cellMap.put(key, referencedCell);
            } else if (referencedCell.getExpression() == null) {
                referencedCell.setExpression(referencedCell.constantNode());
            }
            referencedCell.addDependency(selfRef, cellMap);
        }
        cellMap.put(cellKey(sheetIndex(), row, col), saved);
    }

    private String rawFor(CellRef ref) {
        if (ref.getSheet() == sheetIndex()) {
            return getRawValue(ref.getRow(), ref.getCol() + 1);
        }
        if (book == null) {
            return "";
        }
        Sheet s = book.get(ref.getSheet());
        return s == null ? "" : s.getRawValue(ref.getRow(), ref.getCol() + 1);
    }

    public void recomputeDependentsOnEdit(int row, int col, Map<String, Object> bindings, FunctionRegistry registry) {
        CellRef self = new CellRef(sheetIndex(), row, col - 1);
        recomputeDependents(self, bindings, registry);
    }

    public void recomputeDependentsOnEdit(int row, int col) {
        CellRef self = new CellRef(sheetIndex(), row, col - 1);
        recomputeDependents(self, bindings, registry);
    }

    private void recomputeDependents(CellRef self, Map<String, Object> bindings, FunctionRegistry registry) {
        Cell edited = cellMap.get(self.toString());
        if (edited == null) {
            return;
        }
        edited.recalculate(self, cellMap, bindings, registry, book);
        Set<String> done = new LinkedHashSet<>();
        ArrayDeque<CellRef> todo = new ArrayDeque<>();
        todo.push(self);
        while (!todo.isEmpty()) {
            CellRef r = todo.pop();
            if (!done.add(r.toString())) {
                continue;
            }
            Cell c = cellMap.get(r.toString());
            if (c != null) {
                sheetFor(r).setRawValue(r.getRow(), r.getCol() + 1, c.display());
                if (c.getDependents() != null) {
                    for (CellRef d : c.getDependents()) {
                        todo.push(d);
                    }
                }
            }
        }
    }

    private Sheet sheetFor(CellRef ref) {
        if (book == null || ref.getSheet() == sheetIndex()) {
            return this;
        }
        Sheet s = book.get(ref.getSheet());
        return s == null ? this : s;
    }

    public void loadDefaults() {
        setRawValue(0, 1, "13");
        setRawValue(0, 2, "'hello'");
        setRawValue(0, 3, "true");
        setRawValue(1, 1, "2024-01-15");
        setRawValue(1, 2, "2023-12-25 23:59:59");
        setRawValue(1, 3, "23:59:59");
    }

    public static Map<String, Object> parseBindings(String text) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Invalid binding (expected 'name = value'): " + s);
            }
            String name = s.substring(0, eq).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Missing variable name in: " + s);
            }
            parsed.put(name, parseValue(s.substring(eq + 1).trim()));
        }
        return parsed;
    }

    private static Object parseValue(String t) {
        if (t.length() >= 2) {
            char q = t.charAt(0);
            if ((q == '\'' || q == '"') && t.charAt(t.length() - 1) == q) {
                return t.substring(1, t.length() - 1);
            }
        }
        if (t.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (t.equalsIgnoreCase("false")) return Boolean.FALSE;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ignored) {
            return t;
        }
    }
}