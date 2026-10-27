package graph;

import java.util.ArrayList;
import java.util.List;

import expr.CellRef;
import expr.DataType;
import expr.EvalUtil;
import expr.ExpressionException;
import functions.Function;
import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * The three connection functions evaluated by a connection sheet:
 *
 * <ul>
 *   <li><b>import</b> ({@code source(...)}) - reads a cell of the source book.
 *       {@code source(sheetName, cellName)} or {@code source(sheetName, row, col)}
 *       (1-based row/column). {@code import(...)} is accepted as an alias.</li>
 *   <li><b>export</b> ({@code target(...)}) - writes a value into a cell of the
 *       target book. {@code target(sheetName, cellName, expr)} or
 *       {@code target(sheetName, row, col, expr)}. {@code export(...)} is an
 *       alias.</li>
 *   <li><b>persist</b> ({@code persist(expr, extra...)}) - hands the calculated
 *       value (plus any further parameters) to the {@link PersistenceModule}.</li>
 * </ul>
 *
 * All three are bound to one {@link GraphEdge}; because a function is looked up
 * in the registry at evaluation time, the edge's source/target boxes may be
 * wired after the functions are created.
 */
public final class EdgeFunctions {

    private EdgeFunctions() {
    }

    /** Common base holding the bound edge and the exposed function name. */
    private abstract static class EdgeFunction implements Function {
        protected final GraphEdge edge;
        private final String name;

        EdgeFunction(GraphEdge edge, String name) {
            this.edge = edge;
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public DataType getReturnType() {
            return DataType.ANY;
        }
    }

    /** {@code source(sheet, cell)} / {@code source(sheet, row, col)}. */
    public static final class SourceFunction extends EdgeFunction {
        public SourceFunction(GraphEdge edge) {
            super(edge, "source");
        }

        private SourceFunction(GraphEdge edge, String alias) {
            super(edge, alias);
        }

        /** An alias instance named e.g. {@code import}. */
        public static SourceFunction alias(GraphEdge edge, String name) {
            return new SourceFunction(edge, name);
        }

        @Override
        public DataType[] getParameterTypes() {
            return new DataType[] { DataType.ANY, DataType.ANY, DataType.ANY };
        }

        @Override
        public boolean acceptsParameterCount(int count) {
            return count >= 1 && count <= 3;
        }

        @Override
        public String parameterCountDescription() {
            return "1 to 3 parameter(s)";
        }

        /**
         * Reads a source cell: the live in-memory source book when it has the
         * sheet, otherwise (an unopened box) the persisted workbook XML using its
         * cached, already-calculated values - without opening or re-evaluating.
         */
        private Object readSource(String sheetName, String cellName,
                                  int row1, int col1, boolean byName) {
            SheetBook source = edge.sourceBook();
            if (Workbooks.findSheet(source, sheetName) != null) {
                Sheet live = Workbooks.findSheet(source, sheetName);
                return Workbooks.readCell(live, cellName, row1, col1, byName);
            }
            String file = edge.sourceFile();
            if (file != null) {
                Object cached = FileCellCache.read(file, sheetName, cellName, row1, col1, byName);
                if (cached != null) {
                    return cached;
                }
            }
            throw new ExpressionException("Source book has no sheet '" + sheetName + "'"
                    + (file == null ? "." : " (and no cached value in " + file + ")."));
        }

        @Override
        public Object evaluate(List<Object> params) {
            if (params.size() == 1) {
                Object v = params.get(0);
                // source("Sheet.Cell") / import("Sheet.Cell")
                if (v instanceof String s) {
                    int dot = s.indexOf('.');
                    if (dot > 0 && dot < s.length() - 1) {
                        return readSource(s.substring(0, dot), s.substring(dot + 1), 0, 0, true);
                    }
                    throw new ExpressionException("source(name): use source(\"Sheet\", \"Cell\") "
                            + "or a dotted reference like M1.SALARY.");
                }
                // A plain dotted reference (e.g. M1.SALARY) is already resolved to the
                // source value through the injected source bindings.
                return v;
            }
            if (params.size() == 2 && isNumber(params.get(0)) && isNumber(params.get(1))) {
                // source(row, col) -> first sheet of the source book
                Sheet sheet = Workbooks.firstSheet(edge.sourceBook());
                if (sheet != null) {
                    return Workbooks.readCell(sheet, null,
                            intValue(params.get(0)), intValue(params.get(1)), false);
                }
                String file = edge.sourceFile();
                if (file != null) {
                    Object cached = FileCellCache.read(file, null,
                            null, intValue(params.get(0)), intValue(params.get(1)), false);
                    if (cached != null) {
                        return cached;
                    }
                }
                throw new ExpressionException("Source box '" + edge.sourceLabel()
                        + "' has no book/sheet.");
            }
            String sheetName = EvalUtil.asString(params.get(0));
            if (workbookHasSheet(edge.sourceBook(), sheetName) || edge.sourceFile() == null) {
                Sheet sheet = Workbooks.findSheet(edge.sourceBook(), sheetName);
                if (sheet == null) {
                    throw new ExpressionException("Source book has no sheet '" + sheetName + "'.");
                }
                if (params.size() == 2) {
                    return Workbooks.readCell(sheet, EvalUtil.asString(params.get(1)), 0, 0, true);
                }
                return Workbooks.readCell(sheet, null,
                        intValue(params.get(1)), intValue(params.get(2)), false);
            }
            // Unopened box: read the cached value from the persisted XML.
            if (params.size() == 2) {
                return readSource(sheetName, EvalUtil.asString(params.get(1)), 0, 0, true);
            }
            return readSource(sheetName, null, intValue(params.get(1)), intValue(params.get(2)), false);
        }

        private static boolean workbookHasSheet(SheetBook book, String name) {
            return Workbooks.findSheet(book, name) != null;
        }
    }

    /** {@code target(sheet, cell, expr)} / {@code target(sheet, row, col, expr)}. */
    public static final class TargetFunction extends EdgeFunction {
        public TargetFunction(GraphEdge edge) {
            super(edge, "target");
        }

        private TargetFunction(GraphEdge edge, String alias) {
            super(edge, alias);
        }

        public static TargetFunction alias(GraphEdge edge, String name) {
            return new TargetFunction(edge, name);
        }

        @Override
        public DataType[] getParameterTypes() {
            return new DataType[] { DataType.ANY, DataType.ANY, DataType.ANY, DataType.ANY };
        }

        @Override
        public boolean acceptsParameterCount(int count) {
            return count == 3 || count == 4;
        }

        @Override
        public String parameterCountDescription() {
            return "3 or 4 parameter(s)";
        }

        @Override
        public Object evaluate(List<Object> params) {
            String sheetName = EvalUtil.asString(params.get(0));
            Sheet sheet = Workbooks.findSheet(edge.targetBook(), sheetName);
            if (sheet == null) {
                throw new ExpressionException("Target book has no sheet '" + sheetName
                        + "' (target box '" + edge.targetLabel() + "').");
            }
            // The format pattern / alignment of the exporting connection cell (the one
            // whose expression contains this target(...) call) is copied to the target
            // cell. The evaluation engine publishes the current cell on the book.
            String fmt = null;
            String al = null;
            SheetBook connBook = edge.connection.book();
            CellRef cur = connBook == null ? null : connBook.currentCell();
            if (cur != null) {
                Sheet connSheet = connBook.get(cur.getSheet());
                if (connSheet != null) {
                    fmt = connSheet.getFormatPattern(cur.getRow(), cur.getCol() + 1);
                    al = connSheet.getAlignment(cur.getRow(), cur.getCol() + 1);
                }
            }
            // During an automatic "source book saved" run only the export cells whose
            // value actually changed are written; persist is skipped (see below).
            boolean write = edge.sideEffectsEnabled();
            if (write && edge.selectiveExportActive()) {
                write = cur != null && edge.isSelectedExport(cur.getSheet() + ":" + cur.getRow());
            }
            Object value;
            if (params.size() == 3) {
                value = params.get(2);
                if (write) {
                    Workbooks.writeCell(sheet, sheetName, EvalUtil.asString(params.get(1)),
                            0, 0, true, value, fmt, al);
                }
            } else {
                value = params.get(3);
                if (write) {
                    Workbooks.writeCell(sheet, sheetName, null,
                            intValue(params.get(1)), intValue(params.get(2)), false, value, fmt, al);
                }
            }
            if (write) {
                // Persist the updated target book so its cached (calculated) values
                // are refreshed on disk too.
                edge.flushTargetBook();
            }
            return value;
        }
    }

    /** {@code persist(expr, extra...)}. */
    public static final class PersistFunction extends EdgeFunction {
        public PersistFunction(GraphEdge edge) {
            super(edge, "persist");
        }

        @Override
        public DataType[] getParameterTypes() {
            return new DataType[] { DataType.ANY };
        }

        @Override
        public boolean acceptsParameterCount(int count) {
            return count >= 1;
        }

        @Override
        public String parameterCountDescription() {
            return "at least 1 parameter";
        }

        @Override
        public Object evaluate(List<Object> params) {
            Object value = params.get(0);
            if (!edge.sideEffectsEnabled() || edge.selectiveExportActive()) {
                return value; // preview, or an automatic export run: do not touch the database
            }
            List<Object> extra = params.size() > 1
                    ? new ArrayList<>(params.subList(1, params.size()))
                    : new ArrayList<>();
            return edge.persist(value, extra);
        }
    }

    private static boolean isNumber(Object o) {
        return o instanceof Number;
    }

    private static int intValue(Object o) {
        return (int) EvalUtil.asDouble(o);
    }
}
