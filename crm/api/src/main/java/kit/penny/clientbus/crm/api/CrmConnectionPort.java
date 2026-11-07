package kit.penny.clientbus.crm.api;

public interface CrmConnectionPort {
    void verifyConnection(CrmAccess access);
    CrmProjectInfo inspectProject(CrmAccess access, String externalProjectId);
    default java.util.List<CrmProjectInfo> listProjects(CrmAccess access) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
}
