
package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationMapperTest {

    private final ConversationMapper mapper = new ConversationMapper();

    @Test
    void externalOperationsAvailableOnlyWhenChannelConnected() {
        for (ChannelConnectionStatus status : ChannelConnectionStatus.values()) {
            ConversationDto dto = toDtoWithStatus(status);

            assertEquals(status, dto.channelStatus());
            assertEquals(
                    status == ChannelConnectionStatus.CONNECTED,
                    dto.externalOperationsAvailable(),
                    "Unexpected availability for channel status " + status
            );
        }
    }

    @Test
    void returnsNullWhenEntityIsNull() {
        assertNull(mapper.toDto(null));
    }

    private ConversationDto toDtoWithStatus(
            ChannelConnectionStatus status
    ) {
        ConversationEntity entity = mock(ConversationEntity.class);
        WorkspaceEntity workspace = mock(WorkspaceEntity.class);
        ChannelAccountEntity channelAccount = mock(ChannelAccountEntity.class);
        ChannelEntity channel = mock(ChannelEntity.class);
        ClientAccountEntity clientAccount = mock(ClientAccountEntity.class);

        when(entity.getWorkspace()).thenReturn(workspace);
        when(entity.getChannelAccount()).thenReturn(channelAccount);
        when(entity.getClientAccount()).thenReturn(clientAccount);
        when(entity.getAssignedEmployee()).thenReturn(null);

        when(channelAccount.getChannel()).thenReturn(channel);
        when(channel.getStatus()).thenReturn(status);

        return mapper.toDto(entity);
    }
}
