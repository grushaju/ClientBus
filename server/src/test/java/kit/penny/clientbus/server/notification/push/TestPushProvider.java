package kit.penny.clientbus.server.notification.push;

import kit.penny.clientbus.common.notification.PushProviderType;
import kit.penny.clientbus.server.notification.contract.PushProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Test source/profile only; the runtime artifact has no fake successful provider. */
@Component
@Profile("test & notification-push-test")
public class TestPushProvider implements PushProvider {
    private final ConcurrentLinkedQueue<Request> requests=new ConcurrentLinkedQueue<>();
    private volatile Result result=new Result(Outcome.ACCEPTED,"test-provider-id",null);
    public PushProviderType provider(){return PushProviderType.FCM;}
    public Result send(Request request){requests.add(request);return result;}
    public void result(Result value){result=value;}
    public int attempts(){return requests.size();}
    public Request last(){return requests.stream().reduce((a,b)->b).orElseThrow();}
}
