package graph;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/**
 * The graph drawing surface: rectangular resizable boxes (each holding a Book)
 * and directed arrows (connections) between them. Boxes can be moved/resized and
 * arrows are drawn with an arrowhead. Double-clicking a box opens its book,
 * double-clicking an arrow opens its 6-column connection sheet.
 */
public class GraphCanvas extends JPanel {

    /** Callbacks the owning frame implements. */
    public interface Host {
        void canvasSelectionChanged();

        void editConnection(GraphEdge edge);

        void editBook(GraphNode node);

        void canvasStatus(String message);

        void addBoxAt(Point where);

        void importBook(GraphNode node);

        void exportBook(GraphNode node);

        void renameBox(GraphNode node);
    }

    private static final int HANDLE = 12;
    private static final Color BG = new Color(0xF7F7FB);
    private static final Color GRID = new Color(0xE7E7EF);
    private static final Color BOX_FILL = new Color(0xFFFDF5);
    private static final Color BOX_SELECTED = new Color(0xE8F0FF);
    private static final Color BORDER = new Color(0x54607A);
    private static final Color ARROW = new Color(0x2E6FB7);
    private static final Color ARROW_SELECTED = new Color(0xD2691E);

    private final GraphModel model;
    private final Host host;

    private GraphNode selectedNode;
    private GraphEdge selectedEdge;

    private boolean connectMode;
    private GraphNode connectSource;
    private Point mouse = new Point();

    private int dragMode; // 0 none, 1 move, 2 resize
    private int dragDx;
    private int dragDy;
    private int resizeW0;
    private int resizeH0;

    public GraphCanvas(GraphModel model, Host host) {
        this.model = model;
        this.host = host;
        setBackground(BG);
        setPreferredSize(new Dimension(1200, 900));
        setFocusable(true);
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    maybeShowPopup(e);
                    return;
                }
                handlePressed(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                handleDragged(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragMode = 0;
                requestFocusInWindow();
                if (e.isPopupTrigger()) {
                    maybeShowPopup(e);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                mouse = e.getPoint();
                if (connectMode) {
                    repaint();
                } else {
                    setCursor(onResizeHandle(e.getPoint()) ? Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
                            : Cursor.getDefaultCursor());
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    GraphNode n = findNode(e.getPoint());
                    if (n != null) {
                        host.editBook(n);
                        return;
                    }
                    GraphEdge edge = findEdge(e.getPoint());
                    if (edge != null) {
                        host.editConnection(edge);
                    }
                }
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    /** Right-click context menu: box / connection / empty-canvas actions. */
    private void maybeShowPopup(MouseEvent e) {
        Point p = e.getPoint();
        GraphNode node = findNode(p);
        GraphEdge edge = findEdge(p);
        JPopupMenu menu = new JPopupMenu();
        if (node != null) {
            select(node);
            JMenuItem editBook = new JMenuItem("Edit book...");
            editBook.addActionListener(a -> host.editBook(node));
            menu.add(editBook);
            JMenuItem rename = new JMenuItem("Rename...");
            rename.addActionListener(a -> host.renameBox(node));
            menu.add(rename);
            JMenuItem importBook = new JMenuItem("Import book...");
            importBook.addActionListener(a -> host.importBook(node));
            menu.add(importBook);
            JMenuItem exportBook = new JMenuItem("Export book...");
            exportBook.addActionListener(a -> host.exportBook(node));
            menu.add(exportBook);
            JMenuItem remove = new JMenuItem("Remove box");
            remove.addActionListener(a -> {
                model.removeNode(node);
                if (selectedNode == node) {
                    selectedNode = null;
                }
                host.canvasSelectionChanged();
                repaint();
            });
            menu.add(remove);
        } else if (edge != null) {
            selectedEdge = edge;
            selectedNode = null;
            host.canvasSelectionChanged();
            repaint();
            JMenuItem editConn = new JMenuItem("Edit connection...");
            editConn.addActionListener(a -> host.editConnection(edge));
            menu.add(editConn);
            JMenuItem run = new JMenuItem("Run connection");
            run.addActionListener(a -> {
                edge.runConnection();
                host.canvasStatus("Ran connection " + edge.label);
                repaint();
            });
            menu.add(run);

            JMenu insert = new JMenu("Insert row");
            JMenuItem imp = new JMenuItem("Import (source(...))");
            imp.addActionListener(a -> insertRow(edge, 2, "source(\"" + firstSheetName(edge.source) + "\", 1, 1)"));
            insert.add(imp);
            JMenuItem per = new JMenuItem("Persist (persist(...))");
            per.addActionListener(a -> insertRow(edge, 4,
                    "persist(source(\"" + firstSheetName(edge.source) + "\", 1, 1), 'tag')"));
            insert.add(per);
            JMenuItem exp = new JMenuItem("Export (target(...))");
            exp.addActionListener(a -> insertRow(edge, 6,
                    "target(\"" + firstSheetName(edge.target) + "\", 1, 1, 0)"));
            insert.add(exp);
            menu.add(insert);

            JMenuItem remove = new JMenuItem("Remove connection");
            remove.addActionListener(a -> {
                model.removeEdge(edge);
                selectedEdge = null;
                host.canvasSelectionChanged();
                repaint();
            });
            menu.add(remove);
        } else {
            JMenuItem add = new JMenuItem("Add box here");
            add.addActionListener(a -> host.addBoxAt(p));
            menu.add(add);
        }
        menu.show(this, e.getX(), e.getY());
    }

    /** Puts a template into the first empty row of a connection and opens it. */
    private void insertRow(GraphEdge edge, int col, String text) {
        int row = firstEmptyRow(edge);
        if (row < 0) {
            host.canvasStatus("No empty row left in connection " + edge.label);
            return;
        }
        try {
            edge.connection.setExpression(row, col, text);
        } catch (Exception ex) {
            host.canvasStatus("Error: " + ex.getMessage());
            return;
        }
        host.editConnection(edge);
    }

    private int firstEmptyRow(GraphEdge edge) {
        for (int r = 1; r < edge.connection.rows(); r++) {
            boolean empty = true;
            for (int c = 1; c <= ConnectionSheet.COLUMNS; c++) {
                if (!edge.connection.sourceAt(r, c).isBlank()) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                return r;
            }
        }
        return -1;
    }

    private String firstSheetName(GraphNode node) {
        if (node == null) {
            return "S1";
        }
        functions.custom.Sheet s = Workbooks.firstSheet(node.book());
        return s == null ? "S1" : s.name();
    }

    private void handlePressed(MouseEvent e) {
        Point p = e.getPoint();
        mouse = p;
        if (connectMode) {
            GraphNode n = findNode(p);
            if (n != null) {
                if (connectSource == null) {
                    connectSource = n;
                    host.canvasStatus("Connect: now click the target box.");
                } else if (connectSource != n) {
                    GraphEdge edge = model.connect(connectSource, n);
                    connectSource = null;
                    selectedEdge = edge;
                    selectedNode = null;
                    host.canvasStatus("Connection created: " + (edge == null ? "-" : edge.label));
                    host.canvasSelectionChanged();
                }
                repaint();
            }
            return;
        }
        if (selectedNode != null && onResizeHandle(p)) {
            dragMode = 2;
            resizeW0 = selectedNode.w;
            resizeH0 = selectedNode.h;
            mouse = p;
            return;
        }
        GraphNode n = findNode(p);
        if (n != null) {
            selectedNode = n;
            selectedEdge = null;
            dragMode = 1;
            dragDx = p.x - n.x;
            dragDy = p.y - n.y;
            host.canvasSelectionChanged();
            repaint();
            return;
        }
        GraphEdge edge = findEdge(p);
        selectedEdge = edge;
        selectedNode = null;
        dragMode = 0;
        host.canvasSelectionChanged();
        repaint();
    }

    private void handleDragged(MouseEvent e) {
        Point p = e.getPoint();
        if (dragMode == 1 && selectedNode != null) {
            selectedNode.x = p.x - dragDx;
            selectedNode.y = p.y - dragDy;
            repaint();
        } else if (dragMode == 2 && selectedNode != null) {
            selectedNode.resizeTo(resizeW0 + (p.x - mouse.x), resizeH0 + (p.y - mouse.y));
            repaint();
        }
    }

    private boolean onResizeHandle(Point p) {
        if (selectedNode == null) {
            return false;
        }
        int hx = selectedNode.x + selectedNode.w;
        int hy = selectedNode.y + selectedNode.h;
        return p.x >= hx - HANDLE && p.x <= hx + 4 && p.y >= hy - HANDLE && p.y <= hy + 4;
    }

    // ------------------------------------------------------------------ hit tests

    public GraphNode findNode(Point p) {
        List<GraphNode> nodes = model.nodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            if (nodes.get(i).contains(p.x, p.y)) {
                return nodes.get(i);
            }
        }
        return null;
    }

    public GraphEdge findEdge(Point p) {
        for (GraphEdge e : model.edges()) {
            Point a = anchor(e.source, e.target);
            Point b = anchor(e.target, e.source);
            if (distanceToSegment(p, a, b) <= 8) {
                return e;
            }
        }
        return null;
    }

    private double distanceToSegment(Point p, Point a, Point b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = a.x + t * dx;
        double cy = a.y + t * dy;
        return Math.hypot(p.x - cx, p.y - cy);
    }

    /** The point where the centre-to-centre line crosses the source box border. */
    private Point anchor(GraphNode from, GraphNode to) {
        int cx = from.x + from.w / 2;
        int cy = from.y + from.h / 2;
        int tx = to.x + to.w / 2;
        int ty = to.y + to.h / 2;
        double dx = tx - cx;
        double dy = ty - cy;
        if (dx == 0 && dy == 0) {
            return new Point(cx, cy);
        }
        double hw = from.w / 2.0;
        double hh = from.h / 2.0;
        double scale = Math.min(dx == 0 ? Double.MAX_VALUE : Math.abs(hw / dx),
                dy == 0 ? Double.MAX_VALUE : Math.abs(hh / dy));
        int x = (int) Math.round(cx + dx * scale);
        int y = (int) Math.round(cy + dy * scale);
        return new Point(x, y);
    }

    // ------------------------------------------------------------------ selection

    public GraphNode selectedNode() {
        return selectedNode;
    }

    public GraphEdge selectedEdge() {
        return selectedEdge;
    }

    public void select(GraphNode node) {
        selectedNode = node;
        selectedEdge = null;
        repaint();
    }

    public void setConnectMode(boolean on) {
        connectMode = on;
        if (!on) {
            connectSource = null;
        }
        setCursor(on ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
        repaint();
    }

    public boolean isConnectMode() {
        return connectMode;
    }

    public void deleteSelection() {
        if (selectedNode != null) {
            model.removeNode(selectedNode);
            selectedNode = null;
        } else if (selectedEdge != null) {
            model.removeEdge(selectedEdge);
            selectedEdge = null;
        }
        host.canvasSelectionChanged();
        repaint();
    }

    public void addNodeAtCenter(ObjectType type, Point where) {
        GraphNode n = model.addNode(type, where.x - 85, where.y - 42);
        selectedNode = n;
        selectedEdge = null;
        host.canvasSelectionChanged();
        repaint();
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawGrid(g2);
        for (GraphEdge e : model.edges()) {
            drawEdge(g2, e);
        }
        for (GraphNode n : model.nodes()) {
            drawNode(g2, n);
        }
        if (connectMode && connectSource != null) {
            Point a = new Point(connectSource.x + connectSource.w / 2,
                    connectSource.y + connectSource.h / 2);
            g2.setColor(ARROW_SELECTED);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[] { 6f, 6f }, 0f));
            g2.drawLine(a.x, a.y, mouse.x, mouse.y);
        }
        g2.dispose();
    }

    private void drawGrid(Graphics2D g2) {
        g2.setColor(GRID);
        for (int x = 0; x < getWidth(); x += 25) {
            g2.drawLine(x, 0, x, getHeight());
        }
        for (int y = 0; y < getHeight(); y += 25) {
            g2.drawLine(0, y, getWidth(), y);
        }
    }

    private void drawNode(Graphics2D g2, GraphNode n) {
        boolean sel = n == selectedNode;
        g2.setColor(sel ? BOX_SELECTED : BOX_FILL);
        g2.fillRoundRect(n.x, n.y, n.w, n.h, 14, 14);
        g2.setColor(sel ? ARROW : BORDER);
        g2.setStroke(new BasicStroke(sel ? 2.5f : 1.5f));
        g2.drawRoundRect(n.x, n.y, n.w, n.h, 14, 14);

        // header strip
        g2.setColor(typeColor(n.type.abbrev));
        g2.fillRoundRect(n.x, n.y, n.w, 24, 14, 14);
        g2.setColor(Color.WHITE);
        g2.setFont(canvasFont(Font.BOLD, 14f));
        g2.drawString(clip(g2, n.name() + "   #" + n.id, n.w - 16), n.x + 10, n.y + 17);

        // title + type + book info
        g2.setColor(new Color(0x223344));
        g2.setFont(canvasFont(Font.BOLD, 12f));
        g2.drawString(clip(g2, n.title(), n.w - 16), n.x + 10, n.y + 42);
        g2.setFont(canvasFont(Font.PLAIN, 11f));
        g2.setColor(new Color(0x556677));
        g2.drawString(clip(g2, n.type.abbrev + " | Book: " + n.bookSummary(), n.w - 16), n.x + 10, n.y + 60);

        if (sel) {
            g2.setColor(ARROW);
            g2.fillRect(n.x + n.w - HANDLE, n.y + n.h - HANDLE, HANDLE, HANDLE);
        }
    }

    private String clip(Graphics2D g2, String s, int maxW) {
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(s) <= maxW) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (fm.stringWidth(sb.toString() + s.charAt(i) + "...") > maxW) {
                break;
            }
            sb.append(s.charAt(i));
        }
        return sb + "...";
    }

    private void drawEdge(Graphics2D g2, GraphEdge e) {
        if (e.source == null || e.target == null) {
            return;
        }
        Point a = anchor(e.source, e.target);
        Point b = anchor(e.target, e.source);
        boolean sel = e == selectedEdge;
        g2.setColor(sel ? ARROW_SELECTED : ARROW);
        g2.setStroke(new BasicStroke(sel ? 3f : 2f));
        g2.drawLine(a.x, a.y, b.x, b.y);
        drawArrowHead(g2, a, b, sel ? ARROW_SELECTED : ARROW);

        // label
        String text = e.label;
        g2.setFont(canvasFont(Font.BOLD, 11f));
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(text);
        int mx = (a.x + b.x) / 2;
        int my = (a.y + b.y) / 2;
        g2.setColor(new Color(0xFFFFFFCC, true));
        g2.fillRoundRect(mx - tw / 2 - 5, my - 12, tw + 10, 18, 8, 8);
        g2.setColor(new Color(0x333355));
        g2.drawString(text, mx - tw / 2, my + 2);
    }

    private void drawArrowHead(Graphics2D g2, Point from, Point to, Color color) {
        double angle = Math.atan2(to.y - from.y, to.x - from.x);
        int size = 13;
        Polygon head = new Polygon();
        head.addPoint(to.x, to.y);
        head.addPoint((int) (to.x - size * Math.cos(angle - Math.PI / 7)),
                (int) (to.y - size * Math.sin(angle - Math.PI / 7)));
        head.addPoint((int) (to.x - size * Math.cos(angle + Math.PI / 7)),
                (int) (to.y - size * Math.sin(angle + Math.PI / 7)));
        g2.setColor(color);
        g2.fillPolygon(head);
    }

    private Font canvasFont(int style, float size) {
        Font base = getFont();
        if (base == null) {
            base = new Font("SansSerif", Font.PLAIN, 12);
        }
        return base.deriveFont(style, size);
    }

    private Color typeColor(String abbrev) {
        Color[] palette = {
                new Color(0x2E6FB7), new Color(0x2E8B57), new Color(0xB7662E),
                new Color(0x7A2EB7), new Color(0xB72E5A), new Color(0x2E8BA8),
                new Color(0x8B8B2E), new Color(0x555555)
        };
        int h = abbrev == null ? 0 : Math.abs(abbrev.hashCode());
        return palette[h % palette.length];
    }
}
