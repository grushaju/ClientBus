package kit.penny.clientbus.common.dto.crm;
import java.util.*;
public record CrmClientCandidateDto(UUID id, String firstName, String lastName, List<String> phoneList, boolean phoneMatch) {}
