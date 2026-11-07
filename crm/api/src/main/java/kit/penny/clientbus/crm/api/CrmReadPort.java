package kit.penny.clientbus.crm.api;

public interface CrmReadPort {
    default CrmFinanceSnapshot readFinance(CrmAccess access, String externalProjectId, CrmFinanceQuery query) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
    default CrmAppointmentPage readAppointments(CrmAccess access,String externalProjectId,CrmAppointmentQuery query,String cursor) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
    default CrmAppointmentPage readClientHistory(CrmAccess access,String externalProjectId,CrmAppointmentQuery query,String cursor) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
    default CrmScheduleSnapshot readWorkIntervals(CrmAccess access, String externalProjectId, CrmWorkIntervalQuery query) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
    /** Scope is externalProjectId + ":CLIENTS". Cursor is owned by the adapter. */
    default CrmClientPage readClients(CrmAccess access, String externalProjectId, String cursor) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
    /** The returned scope is externalProjectId + ":MASTERS"; a different scope must never be applied. */
    CrmReadResult<CrmMaster> readMasters(CrmAccess access, String externalProjectId);

    /** Scope is externalProjectId + ":CATALOG". COMPLETE covers all three collections; projectDisplayName is optional. */
    default CrmCatalogSnapshot readCatalog(CrmAccess access, String externalProjectId) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
}
