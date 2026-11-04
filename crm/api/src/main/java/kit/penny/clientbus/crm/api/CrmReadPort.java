package kit.penny.clientbus.crm.api;

public interface CrmReadPort {
    /** Scope is externalProjectId + ":CLIENTS". Cursor is owned by the adapter. */
    default CrmClientPage readClients(CrmAccess access, String externalProjectId, String cursor) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
    /** The returned scope is externalProjectId + ":MASTERS"; a different scope must never be applied. */
    CrmReadResult<CrmMaster> readMasters(CrmAccess access, String externalProjectId);

    /** Scope is externalProjectId + ":CATALOG". COMPLETE covers all three collections. */
    default CrmCatalogSnapshot readCatalog(CrmAccess access, String externalProjectId) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
}
