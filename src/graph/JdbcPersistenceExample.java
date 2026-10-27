package graph;

import functions.custom.Sheet;

/**
 * Demonstrates plugging {@link JdbcPersistenceModule} into a graph. Run it with
 * a JDBC driver on the classpath:
 *
 * <pre>
 *   jdbcdemo.bat "jdbc:sqlite:C:/tmp/graph.db"        // sqlite
 *   jdbcdemo.bat "jdbc:postgresql://localhost/db" user pass org.postgresql.Driver
 * </pre>
 *
 * Without arguments it just prints the required table and usage (so it is safe
 * to run with no database and no driver).
 */
public final class JdbcPersistenceExample {

    private JdbcPersistenceExample() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java -cp \"out;<driver.jar>\" graph.JdbcPersistenceExample"
                    + " <jdbcUrl> [user] [password] [driverClass]");
            System.out.println();
            System.out.println("Required table (adapt to your database):");
            System.out.println("  CREATE TABLE graph_persist (");
            System.out.println("    connection_name VARCHAR(64),");
            System.out.println("    source_name     VARCHAR(64),");
            System.out.println("    target_name     VARCHAR(64),");
            System.out.println("    value_text      VARCHAR(255),");
            System.out.println("    extra_text      VARCHAR(255),");
            System.out.println("    created_at      TIMESTAMP);");
            System.out.println();
            System.out.println("The graph delegates persist(...) to this module; target(...) writes");
            System.out.println("the target book directly.");
            return;
        }

        String url = args[0];
        String user = args.length > 1 ? args[1] : null;
        String pass = args.length > 2 ? args[2] : null;
        String driver = args.length > 3 ? args[3] : null;

        try (JdbcPersistenceModule jdbc = JdbcPersistenceModule.open(driver, url, user, pass)) {
            GraphModel model = new GraphModel(jdbc);          // <- the module is plugged in here
            ObjectType bk = ObjectType.defaultCatalog().get(0);

            GraphNode source = model.addNode(bk, 20, 20);
            GraphNode target = model.addNode(bk, 320, 20);

            Sheet src = Workbooks.findSheet(source.book(), "S1");
            Workbooks.writeValue(src, 0, 1, 123.45);          // A1 = 123.45

            GraphEdge edge = model.connect(source, target);
            edge.connection.setExpression(1, 2, "source(\"S1\", 1, 1)");
            edge.connection.setExpression(1, 4, "persist(source(\"S1\", 1, 1), 'demo')");
            edge.connection.setExpression(1, 6, "target(\"S1\", 2, 2, source(\"S1\", 1, 1) * 2)");

            edge.runConnection();                              // performs the DB + target writes

            Sheet dst = Workbooks.findSheet(target.book(), "S1");
            System.out.println("persist column value : " + edge.connection.valueAt(1, 4));
            System.out.println("target B2 (exported) : " + dst.cell(1, 2).display());
            System.out.println("persist(...) wrote one row into graph_persist.");
        }
    }
}
