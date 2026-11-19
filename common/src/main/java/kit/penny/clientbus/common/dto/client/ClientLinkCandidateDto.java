package kit.penny.clientbus.common.dto.client;
import java.util.*;
/**
 * Кандидат на ручную привязку учётной записи чата к Client (Stage 13.9, пункт 3 плана).
 * phoneMatch — подсказка, а не основание: связь ставит только человек.
 */
public record ClientLinkCandidateDto(UUID id, String firstName, String lastName, List<String> phoneList, boolean phoneMatch) {}
