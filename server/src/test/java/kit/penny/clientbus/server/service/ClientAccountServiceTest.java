package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.ClientAccountState;
import kit.penny.clientbus.server.mapper.ClientAccountMapper;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientAccountServiceTest {

    @Mock
    private ClientAccountRepository clientAccountRepository;

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private ClientAccountMapper clientAccountMapper;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private ClientAccountService clientAccountService;

    @Test
    void getOrCreateForInbound_shouldCreateAccountWithTelegramProfile() {

        String externalId = "123456789";
        String username = "test_user";
        String phone = "+79991234567";
        String displayName = "Test User";

        when(
                clientAccountRepository.findByChannelTypeAndExternalId(
                        ChannelType.TELEGRAM,
                        externalId
                )
        ).thenReturn(Optional.empty());

        ClientAccountEntity savedAccount =
                new ClientAccountEntity();

        when(clientAccountRepository.save(any(ClientAccountEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ClientAccountEntity result =
                clientAccountService.getOrCreateForInbound(
                        ChannelType.TELEGRAM,
                        externalId,
                        username,
                        phone,
                        displayName
                );

        assertNotNull(result);

        assertEquals(
                ChannelType.TELEGRAM,
                result.getChannelType()
        );
        assertEquals(
                externalId,
                result.getExternalId()
        );
        assertEquals(
                username,
                result.getUsername()
        );
        assertEquals(
                phone,
                result.getPhone()
        );
        assertEquals(
                displayName,
                result.getDisplayName()
        );
        assertEquals(
                ClientAccountState.ACTIVE,
                result.getState()
        );
        assertNull(result.getClient());

        verify(clientAccountRepository)
                .save(any(ClientAccountEntity.class));
    }

    @Test
    void getOrCreateForInbound_shouldUpdateExistingAccountProfile() {

        UUID accountId = UUID.randomUUID();

        String externalId = "123456789";
        String oldUsername = "old_user";
        String oldPhone = "+79990000000";
        String oldDisplayName = "Old Name";

        String newUsername = "new_user";
        String newPhone = "+79991234567";
        String newDisplayName = "New Name";

        ClientAccountEntity existingAccount =
                new ClientAccountEntity();

        existingAccount.setId(accountId);
        existingAccount.setChannelType(ChannelType.TELEGRAM);
        existingAccount.setExternalId(externalId);
        existingAccount.setUsername(oldUsername);
        existingAccount.setPhone(oldPhone);
        existingAccount.setDisplayName(oldDisplayName);
        existingAccount.setState(ClientAccountState.ACTIVE);

        when(
                clientAccountRepository.findByChannelTypeAndExternalId(
                        ChannelType.TELEGRAM,
                        externalId
                )
        ).thenReturn(Optional.of(existingAccount));

        ClientAccountEntity result =
                clientAccountService.getOrCreateForInbound(
                        ChannelType.TELEGRAM,
                        externalId,
                        newUsername,
                        newPhone,
                        newDisplayName
                );

        assertSame(existingAccount, result);

        assertEquals(
                externalId,
                result.getExternalId()
        );
        assertEquals(
                newUsername,
                result.getUsername()
        );
        assertEquals(
                newPhone,
                result.getPhone()
        );
        assertEquals(
                newDisplayName,
                result.getDisplayName()
        );

        assertEquals(accountId, result.getId());

        verify(clientAccountRepository, never())
                .save(any(ClientAccountEntity.class));
    }
}
