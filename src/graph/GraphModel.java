package graph;

import java.util.ArrayList;
import java.util.List;

/** The in-memory graph: its boxes, its connections and the persistence module. */
public class GraphModel {

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final PersistenceModule persistence;
    /** Application-designated nodes by role (e.g. "rulebook"), as node ids. */
    private final java.util.Map<String, Integer> designated = new java.util.LinkedHashMap<>();
    private int nextNodeId = 1;
    private int nextEdgeId = 1;

    public GraphModel() {
        this(new DefaultPersistenceModule());
    }

    public GraphModel(PersistenceModule persistence) {
        this.persistence = persistence == null ? new DefaultPersistenceModule() : persistence;
    }

    public List<GraphNode> nodes() {
        return nodes;
    }

    public List<GraphEdge> edges() {
        return edges;
    }

    public PersistenceModule persistence() {
        return persistence;
    }

    /**
     * Designates {@code node} for {@code role} (e.g. "rulebook"), or clears the
     * role when {@code node} is null. The designation is saved with the graph, so an
     * application can find "its" box again after a reload.
     */
    public void setDesignated(String role, GraphNode node) {
        if (role == null || role.isBlank()) {
            return;
        }
        if (node == null) {
            designated.remove(role.trim());
        } else {
            designated.put(role.trim(), node.id);
        }
    }

    /** The node designated for {@code role}, or null. */
    public GraphNode designated(String role) {
        Integer id = role == null ? null : designated.get(role.trim());
        if (id == null) {
            return null;
        }
        for (GraphNode n : nodes) {
            if (n.id == id) {
                return n;
            }
        }
        return null;
    }

    /** The role -> node-id map (for persistence). */
    public java.util.Map<String, Integer> designatedIds() {
        return new java.util.LinkedHashMap<>(designated);
    }

    public int nextNodeId() {
        return nextNodeId++;
    }

    public int nextEdgeId() {
        return nextEdgeId++;
    }

    public GraphNode addNode(ObjectType type, int x, int y) {
        GraphNode n = new GraphNode(nextNodeId(), type, x, y);
        n.setName(uniqueName(n.name(), n));
        nodes.add(n);
        return n;
    }

    /** True when {@code name} is free (case-insensitive) within the graph. */
    public boolean isNameAvailable(String name, GraphNode except) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.trim();
        for (GraphNode x : nodes) {
            if (x != except && n.equalsIgnoreCase(x.name())) {
                return false;
            }
        }
        return true;
    }

    /** A unique short name based on {@code base}, respecting the 15-char limit. */
    public String uniqueName(String base, GraphNode except) {
        String b = base == null || base.isBlank() ? "BOX" : base.trim();
        if (b.length() > GraphNode.MAX_NAME) {
            b = b.substring(0, GraphNode.MAX_NAME);
        }
        if (isNameAvailable(b, except)) {
            return b;
        }
        for (int i = 2; i < 100000; i++) {
            String suffix = String.valueOf(i);
            int keep = Math.max(1, GraphNode.MAX_NAME - suffix.length());
            String cand = b.substring(0, Math.min(b.length(), keep)) + suffix;
            if (isNameAvailable(cand, except)) {
                return cand;
            }
        }
        return b;
    }

    /** Adds an already-built node (used by the loader) and keeps ids/names consistent. */
    public void addLoadedNode(GraphNode node) {
        nodes.add(node);
        nextNodeId = Math.max(nextNodeId, node.id + 1);
        node.setName(uniqueName(node.name(), node));
    }

    /** Creates a directed connection between two distinct boxes. */
    public GraphEdge connect(GraphNode source, GraphNode target) {
        if (source == null || target == null || source == target) {
            return null;
        }
        GraphEdge e = new GraphEdge(nextEdgeId(), source, target, null, persistence, null);
        edges.add(e);
        nextEdgeId = Math.max(nextEdgeId, e.id + 1);
        return e;
    }

    public void addLoadedEdge(GraphEdge edge) {
        edges.add(edge);
        nextEdgeId = Math.max(nextEdgeId, edge.id + 1);
    }

    public void removeNode(GraphNode node) {
        nodes.remove(node);
        edges.removeIf(e -> e.source == node || e.target == node);
        designated.values().removeIf(id -> node != null && id == node.id);
    }

    public void removeEdge(GraphEdge edge) {
        edges.remove(edge);
    }

    public void clear() {
        nodes.clear();
        edges.clear();
        designated.clear();
        nextNodeId = 1;
        nextEdgeId = 1;
    }

    /**
     * Performs a chained recalculation across the whole graph, using only the
     * internal objects (the calculation state of each book and connection) - no
     * GUI and no presentation data are touched.
     *
     * <p>Every connection is recomputed (which recalculates its sheet and thus the
     * values it imports/exports), and each target book's changed cells cascade to
     * their dependents. A running order is derived from the connections so that an
     * upstream connection runs before a downstream one. Progress is reported to
     * {@code console} (may be null) and the final message is returned.</p>
     *
     * @return a short human-readable summary of the recalculation
     */
    public String recalculateChain(RecalcConsole console) {
        java.util.List<GraphEdge> order = topoOrder();
        if (console != null) {
            console.log("Chained recalculation: " + order.size() + " connection(s) in order.");
        }
        int n = 0;
        for (GraphEdge e : order) {
            if (console != null) {
                console.log("  connection " + e.label + " : "
                        + e.sourceLabel() + " -> " + e.targetLabel());
            }
            e.connection.recompute();
            e.flushTargetBook();
            n++;
        }
        String summary = "Recalculated " + n + " connection(s) over "
                + nodes.size() + " box(es).";
        if (console != null) {
            console.log(summary);
        }
        return summary;
    }

    /**
     * The "saved book" procedure: for every connection whose <b>source box</b> is
     * {@code source}, refresh its imports with a preview recalculation (no side
     * effects) and, only when the imported values changed, execute the connection
     * for real (persist/export). Everything runs headlessly - neither the
     * connection editor nor the target book editor is opened.
     *
     * @return the connections that were actually executed
     */
    public List<GraphEdge> runConnectionsSourcedFrom(GraphNode source) {
        return runConnectionsSourcedFrom(source, null);
    }

    /**
     * Same as {@link #runConnectionsSourcedFrom(GraphNode)} but reports progress to
     * {@code console} (may be null).
     */
    public List<GraphEdge> runConnectionsSourcedFrom(GraphNode source, RecalcConsole console) {
        List<GraphEdge> executed = new ArrayList<>();
        if (source == null) {
            return executed;
        }
        if (console != null) {
            console.log("Book '" + source.name() + "' closed - checking connections sourced from it:");
        }
        for (GraphEdge e : edges) {
            if (e.source != source) {
                continue;
            }
            if (console != null) {
                console.log("  connection " + e.label + " : "
                        + e.sourceLabel() + " -> " + e.targetLabel());
            }
            // Recalculate the connection's import/expression cells (background, no
            // side effects), then execute only the exports whose cached calculated
            // value changed.
            java.util.Map<String, Object> cached = e.connection.exportValues();
            e.connection.recompute();
            java.util.Set<String> changed = e.connection.changedExports(cached);
            if (changed.isEmpty()) {
                if (console != null) {
                    console.log("    no export value changed -> skipped");
                }
                continue;
            }
            if (console != null) {
                console.log("    " + changed.size() + " export(s) changed -> executing");
            }
            e.beginSelectiveExport(changed);
            try {
                e.runConnection();                          // headless, selective export
            } finally {
                e.endSelectiveExport();
            }
            executed.add(e);
        }
        if (console != null) {
            console.log("Done: " + executed.size() + " connection(s) executed.");
        }
        return executed;
    }

    /**
     * The connections in an order where an edge whose target feeds another edge's
     * source comes first (Kahn over the box dependency graph). Falls back to the
     * insertion order when the graph has cycles.
     */
    private java.util.List<GraphEdge> topoOrder() {
        java.util.List<GraphEdge> result = new ArrayList<>();
        java.util.Map<GraphNode, Integer> indeg = new java.util.LinkedHashMap<>();
        java.util.Map<GraphNode, java.util.List<GraphEdge>> out = new java.util.LinkedHashMap<>();
        for (GraphNode node : nodes) {
            indeg.put(node, 0);
            out.put(node, new ArrayList<>());
        }
        for (GraphEdge e : edges) {
            if (e.source == null || e.target == null) {
                continue;
            }
            out.get(e.source).add(e);
            indeg.merge(e.target, 1, Integer::sum);
        }
        java.util.ArrayDeque<GraphNode> queue = new java.util.ArrayDeque<>();
        for (GraphNode node : nodes) {
            if (indeg.getOrDefault(node, 0) == 0) {
                queue.add(node);
            }
        }
        while (!queue.isEmpty()) {
            GraphNode node = queue.poll();
            for (GraphEdge e : out.getOrDefault(node, java.util.List.of())) {
                result.add(e);
                GraphNode t = e.target;
                if (indeg.merge(t, -1, Integer::sum) == 0) {
                    queue.add(t);
                }
            }
        }
        if (result.size() != edges.size()) {
            return new ArrayList<>(edges);   // cycle: keep insertion order
        }
        return result;
    }
}
