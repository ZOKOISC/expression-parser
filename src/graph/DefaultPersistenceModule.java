package graph;

import java.util.ArrayList;
import java.util.List;

/**
 * The default {@link PersistenceModule}: it does not touch a database, it just
 * records every {@code persist(...)} call (in memory and on stdout) so the
 * graph can be exercised end to end without a back end. Replace it with a real
 * module to perform the actual persistence.
 */
public class DefaultPersistenceModule implements PersistenceModule {

    private final List<String> log = new ArrayList<>();

    @Override
    public Object persist(String connectionName, String sourceName, String targetName,
                          Object value, List<Object> extraParameters) {
        StringBuilder sb = new StringBuilder();
        sb.append("persist[").append(connectionName).append("] ")
                .append(sourceName).append(" -> ").append(targetName)
                .append(" value=").append(value);
        if (extraParameters != null && !extraParameters.isEmpty()) {
            sb.append(" extra=").append(extraParameters);
        }
        String record = sb.toString();
        log.add(record);
        System.out.println(record);
        return value;
    }

    /** A snapshot of every recorded persistence call, oldest first. */
    public List<String> log() {
        return new ArrayList<>(log);
    }

    public void clearLog() {
        log.clear();
    }
}
