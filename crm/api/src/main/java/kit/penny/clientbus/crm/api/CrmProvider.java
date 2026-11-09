package kit.penny.clientbus.crm.api;

/** Implemented 12.3 ports. Further reads/commands are added when their stages are implemented. */
public interface CrmProvider extends CrmConnectionPort, CrmReadPort, CrmCommandPort {
    String code();
    void invalidate(java.util.UUID connectionId);
}
