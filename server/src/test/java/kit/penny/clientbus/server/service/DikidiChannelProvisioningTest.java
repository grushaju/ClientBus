package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.entity.crm.CrmProjectEntity;
import kit.penny.clientbus.server.persistence.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DikidiChannelProvisioningTest {
    @Test void differentCompanyGetsANewAccountAndRetiringItDoesNotTouchTheArchive() {
        var channels = mock(ChannelRepository.class);
        var conversations = mock(ConversationRepository.class);
        var checkpoints = mock(ChatCheckpointRepository.class);
        var statuses = mock(ChannelStatusService.class);
        var service = new DikidiChannelProvisioning(channels, conversations, checkpoints, statuses, mock(UiDomainEvents.class));
        var workspace = new WorkspaceEntity(); workspace.setId(UUID.randomUUID());
        var archived = new ChannelEntity(workspace, ChannelType.DIKIDI, "Old company");
        archived.setStatus(ChannelConnectionStatus.DISABLED);
        archived.setAccount(new ChannelAccountEntity(archived, "old-company", null, null, null));
        var project = mock(CrmProjectEntity.class, RETURNS_DEEP_STUBS);
        when(project.getProvider()).thenReturn(CrmType.DIKIDI);
        when(project.getWorkspace()).thenReturn(workspace);
        when(project.getExternalId()).thenReturn("new-company");
        when(channels.findAllByWorkspaceIdAndType(workspace.getId(), ChannelType.DIKIDI)).thenReturn(List.of(archived));

        service.provisionOnBind(project);
        var saved = ArgumentCaptor.forClass(ChannelEntity.class);
        verify(channels).save(saved.capture());
        assertNotSame(archived, saved.getValue());
        assertEquals("new-company", saved.getValue().getAccount().getExternalId());
        assertEquals("old-company", archived.getAccount().getExternalId());
        service.retireOnDetach(project);
        verifyNoInteractions(statuses, conversations, checkpoints);
    }
}
