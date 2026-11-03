package kit.penny.clientbus.crm.api;

public interface CrmReadPort {
    /** The returned scope is externalProjectId + ":MASTERS"; a different scope must never be applied. */
    CrmReadResult<CrmMaster> readMasters(CrmAccess access, String externalProjectId);

    /** Scope is externalProjectId + ":CATALOG". COMPLETE covers all three collections. */
    default CrmCatalogSnapshot readCatalog(CrmAccess access, String externalProjectId) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
}
