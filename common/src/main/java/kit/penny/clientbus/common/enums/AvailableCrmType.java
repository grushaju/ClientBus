package kit.penny.clientbus.common.enums;

import java.util.Arrays;
import java.util.List;

public enum AvailableCrmType {
    DIKIDI(CrmType.DIKIDI);

    private final CrmType crmType;

    AvailableCrmType(CrmType crmType) { this.crmType = crmType; }
    public CrmType getCrmType() { return crmType; }
    public static List<CrmType> types() {
        return Arrays.stream(values()).map(AvailableCrmType::getCrmType).toList();
    }
}

