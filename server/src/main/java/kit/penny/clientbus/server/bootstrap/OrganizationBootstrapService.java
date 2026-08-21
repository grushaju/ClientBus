package kit.penny.clientbus.server.bootstrap;

import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.config.properties.BootstrapAdminProperties;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrganizationBootstrapService
        implements CommandLineRunner {

    private final BootstrapAdminProperties properties;
    private final PasswordEncoder passwordEncoder;

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final OrganizationRepository organizationRepository;

    public OrganizationBootstrapService(
            BootstrapAdminProperties properties,
            PasswordEncoder passwordEncoder,
            UserRepository userRepository,
            EmployeeRepository employeeRepository,
            OrganizationRepository organizationRepository
    ) {
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.organizationRepository = organizationRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {

        if (userRepository.existsByRole(UserRole.SUPER_ADMIN)) {
            return;
        }

        createInitialAdmin();
    }

    private void createInitialAdmin() {

        String passwordHash =
                passwordEncoder.encode(
                        properties.getPassword()
                );

        UserEntity user =
                new UserEntity();

        user.setEmail(
                properties.getEmail()
        );

        user.setUsername(
                properties.getUsername()
        );

        user.setPasswordHash(
                passwordHash
        );

        user.setRole(
                UserRole.SUPER_ADMIN
        );

        user.setEnabled(true);

        user = userRepository.save(user);

        OrganizationEntity organization =
                createInitialOrganization(
                        properties.getName()
                );

        EmployeeEntity employee =
                new EmployeeEntity(
                        organization,
                        user,
                        properties.getFirstname(),
                        properties.getLastname(),
                        properties.getPhone()
                );

        employee.setUser(user);
        employee.setOrganization(organization);

        employeeRepository.save(employee);
    }

    private OrganizationEntity createInitialOrganization(String name) {

        OrganizationEntity organization =
                new OrganizationEntity(name);

        return organizationRepository.save(
                organization
        );
    }
}