package kit.penny.clientbus.crm.dikidi;

import java.net.URI;
import java.time.Duration;
import java.util.*;

public interface DikidiTransport extends AutoCloseable {
    record Response(int status, String body, String retryAfter) {
        public Response(int status,String body){this(status,body,null);}
        @Override public String toString() { return "DikidiResponse[status=" + status + "]"; }
    }
    Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
                      boolean ajax, String projectId, Duration timeout);
    interface Observer {
        default void beforeSend() {}
        default void dispatched() {}
        default void response(Response response) {}
    }
    default Response exchange(String method,URI uri,List<Map.Entry<String,String>> form,boolean ajax,String projectId,Duration timeout,Observer observer) {
        observer.beforeSend();observer.dispatched();
        var response=exchange(method,uri,form,ajax,projectId,timeout);observer.response(response);return response;
    }
    @Override default void close() {}
}
