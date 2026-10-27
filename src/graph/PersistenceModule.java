package graph;

import java.util.List;

/**
 * The pluggable processing module behind the {@code persist(...)} connection
 * function. The GUI/engine only knows this interface; a concrete module (e.g. a
 * JDBC writer) decides what persistence actually means.
 *
 * <p>The built-in {@link DefaultPersistenceModule} merely records the calls; to
 * connect a real database implement this interface and pass an instance to
 * {@link GraphModel}.</p>
 */
public interface PersistenceModule {

    /**
     * Persists the calculated value of a connection row.
     *
     * @param connectionName  label of the connection (edge)
     * @param sourceName      label of the source box
     * @param targetName      label of the target box
     * @param value           the calculated value of the expression in the
     *                        persist column
     * @param extraParameters any further parameters supplied to {@code persist(...)}
     * @return a result value that becomes the value of the persist cell
     */
    Object persist(String connectionName, String sourceName, String targetName,
                   Object value, List<Object> extraParameters);
}
