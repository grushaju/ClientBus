package kit.penny.clientbus.crm.dikidi;

import java.net.URI;
import java.time.Duration;
import java.util.*;

public interface DikidiTransport extends AutoCloseable {
    record Response(int status, String body) {
        @Override public String toString() { return "DikidiResponse[status=" + status + "]"; }
    }
    Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
                      boolean ajax, String projectId, Duration timeout);
    @Override default void close() {}
}

