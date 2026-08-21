package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.employee.EmployeeDto;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import org.springframework.stereotype.Component;

@Component
public class EmployeeMapper {

    public EmployeeDto toDto(
            EmployeeEntity employee
    ) {

        var user = employee.getUser();

        return new EmployeeDto(
                employee.getId(),
                employee.getOrganization().getId(),
                user.getUsername(),
                user.getEmail(),
                employee.getFirstName(),
                employee.getLastName(),
                employee.getPhone(),
                user.isEnabled(),
                employee.getCreatedAt(),
                employee.getUpdatedAt()
        );
    }
}