package graph;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * A {@link PersistenceModule} that writes every successful {@code persist(...)}
 * call into a database through JDBC - the concrete "other module" the connection
 * sheet delegates to.
 *
 * <p>The JDK ships no JDBC driver, so put a driver for your database on the
 * classpath (PostgreSQL, MySQL/MariaDB, SQLite, H2, SQL Server, ...). Example
 * table (adjust to your schema):</p>
 *
 * <pre>
 *   CREATE TABLE graph_persist (
 *     id              BIGINT PRIMARY KEY AUTO_INCREMENT,   -- serial / identity varies by DB
 *     connection_name VARCHAR(64),
 *     source_name     VARCHAR(64),
 *     target_name     VARCHAR(64),
 *     value_text      VARCHAR(255),
 *     extra_text      VARCHAR(255),
 *     created_at      TIMESTAMP
 *   );
 * </pre>
 *
 * <p>The default statement binds the six columns in this order:
 * connectionName, sourceName, targetName, value, extraParameters, now. Supply
 * your own SQL through {@link #JdbcPersistenceModule(Connection, String)} when
 * the schema differs (it must still use six {@code ?} placeholders).</p>
 */
public class JdbcPersistenceModule implements PersistenceModule, AutoCloseable {

    public static final String DEFAULT_SQL =
            "INSERT INTO graph_persist "
            + "(connection_name, source_name, target_name, value_text, extra_text, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)";

    private final Connection connection;
    private final String sql;
    private final boolean ownsConnection;

    public JdbcPersistenceModule(Connection connection) {
        this(connection, DEFAULT_SQL, false);
    }

    public JdbcPersistenceModule(Connection connection, String sql) {
        this(connection, sql, false);
    }

    public JdbcPersistenceModule(Connection connection, String sql, boolean ownsConnection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null.");
        }
        this.connection = connection;
        this.sql = sql == null || sql.isBlank() ? DEFAULT_SQL : sql;
        this.ownsConnection = ownsConnection;
    }

    /**
     * Opens a connection (optionally loading a driver class first) and returns a
     * module that owns - and closes - it.
     */
    public static JdbcPersistenceModule open(String driverClass, String url,
                                             String user, String password) throws Exception {
        if (driverClass != null && !driverClass.isBlank()) {
            Class.forName(driverClass);
        }
        Connection c = (user == null || user.isBlank())
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
        return new JdbcPersistenceModule(c, DEFAULT_SQL, true);
    }

    @Override
    public Object persist(String connectionName, String sourceName, String targetName,
                          Object value, List<Object> extraParameters) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, connectionName);
            ps.setString(2, sourceName);
            ps.setString(3, targetName);
            ps.setString(4, value == null ? null : value.toString());
            ps.setString(5, extraParameters == null || extraParameters.isEmpty()
                    ? null : extraParameters.toString());
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            return value;
        } catch (SQLException e) {
            throw new RuntimeException("JDBC persist failed: " + e.getMessage(), e);
        }
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public void close() throws Exception {
        if (ownsConnection && connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
