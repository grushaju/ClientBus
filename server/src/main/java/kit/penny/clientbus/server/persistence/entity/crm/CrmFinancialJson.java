package kit.penny.clientbus.server.persistence.entity.crm;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.Objects;

/** Exact decimal conversion at the finance JSON boundary, including database reloads. */
public final class CrmFinancialJson {
    private static final JsonMapper JSON=JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private CrmFinancialJson() {}
    public static Map<String,Object> read(String json) {
        return JSON.readValue(Objects.requireNonNull(json),new TypeReference<Map<String,Object>>() {});
    }
    public static String write(Map<String,Object> value) { return JSON.writeValueAsString(Objects.requireNonNull(value)); }
    public static Map<String,Object> object(Object value) { return read(JSON.writeValueAsString(Objects.requireNonNull(value))); }
}
