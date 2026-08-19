package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.employee.EmployeeDto;
import kit.penny.clientbus.common.dto.employee.UpdateEmployeeRequest;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import org.springframework.stereotype.Component;

@Component
public class EmployeeMapper {

    public EmployeeDto toDto(
            EmployeeEntity entity
    ) {

        if (entity == null) {
            return null;
        }

        return new EmployeeDto(
                entity.getId(),
                entity.getWorkspace().getId(),
                entity.getUser().getId(),
                entity.getUser().getLogin(),
                entity.getFirstName(),
                entity.getLastName(),
                entity.getPhone(),
                entity.getEmail(),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public void updateEntity(
            EmployeeEntity entity,
            UpdateEmployeeRequest request
    ) {

        if (request.firstName() != null) {
            entity.setFirstName(request.firstName());
        }

        if (request.lastName() != null) {
            entity.setLastName(request.lastName());
        }

        if (request.phone() != null) {
            entity.setPhone(request.phone());
        }

        if (request.email() != null) {
            entity.setEmail(request.email());
        }

        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
    }
}