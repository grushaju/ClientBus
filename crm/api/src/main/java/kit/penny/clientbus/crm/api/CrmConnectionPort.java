package kit.penny.clientbus.crm.api;

public interface CrmConnectionPort {
    void verifyConnection(CrmAccess access);
    CrmProjectInfo inspectProject(CrmAccess access, String externalProjectId);
}

