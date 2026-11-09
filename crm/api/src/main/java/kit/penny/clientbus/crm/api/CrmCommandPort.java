package kit.penny.clientbus.crm.api;

import java.time.LocalDate;
import java.time.Instant;
import java.util.*;

/** Opt-in only after proving both the command and its safe reconciliation protocol. */
public interface CrmCommandPort {
    @FunctionalInterface interface Progress {
        void reserved(String externalId);
        /** Commit the provider's pre-effect object before changing an existing appointment. */
        default void observed(String snapshot) { throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED); }
    }
    default Set<CrmCommand.Type> commandCapabilities() { return Set.of(); }
    /** Read-only authentication/context preparation before the server's effect checkpoint. */
    default void prepareCommand(CrmAccess access,String project,CrmCommand command,Instant deadline) {}
    default CrmAvailability availability(CrmAccess access, String project, String master,
            List<String> services, LocalDate date, Instant deadline) { throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED); }
    /** Called once, after a separately committed durable effect checkpoint. No auth retry is allowed here. */
    default CrmCommandResult command(CrmAccess access, String project, CrmCommand command, Instant deadline) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
    /** Persist a reservation reference before issuing the final save. A failed checkpoint must stop the save. */
    default CrmCommandResult command(CrmAccess access,String project,CrmCommand command,Instant deadline,Progress progress) {
        return command(access,project,command,deadline);
    }
    /** Read-only evidence gathering; never reserves, saves, moves or removes an object. */
    default CrmCommandResult reconcile(CrmAccess access, String project, CrmCommand command, Instant deadline) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
}
