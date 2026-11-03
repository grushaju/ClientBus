package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmSecurityTest {
    private static CrmSecretCipher cipher() {
        return new CrmSecretCipher(new CrmSettings(Base64.getEncoder().encodeToString(new byte[32]), "test-key", 1000, 2000, 4096));
    }
    @Test void enumTypesAndAvailableImplementationsAreSeparate() {
        assertEquals(Set.of(CrmType.DIKIDI, CrmType.AMO, CrmType.BITRIX), Set.of(CrmType.values()));
        assertEquals(List.of(CrmType.DIKIDI), AvailableCrmType.types());
        var adapter = mock(CrmProvider.class); when(adapter.code()).thenReturn("DIKIDI");
        var registry = new CrmRegistry(List.of(adapter));
        assertSame(adapter, registry.require(CrmType.DIKIDI));
        for (var type : List.of(CrmType.AMO, CrmType.BITRIX))
            assertEquals("CRM_UNSUPPORTED", assertThrows(CrmException.class, () -> registry.require(type)).code());
        assertThrows(IllegalStateException.class, () -> new CrmRegistry(List.of(adapter, adapter)));
        assertThrows(IllegalStateException.class, () -> new CrmRegistry(List.of()));
        verify(adapter, never()).verifyConnection(any());
    }
    @Test void ciphertextIsRandomAndBoundToOrganizationConnectionAndKeyId() {
        var cipher = cipher(); UUID org = UUID.randomUUID(), connection = UUID.randomUUID();
        byte[] first = cipher.encrypt(org, connection, "test-secret");
        byte[] second = cipher.encrypt(org, connection, "test-secret");
        assertFalse(Arrays.equals(first, second));
        assertArrayEquals("test-secret".toCharArray(), cipher.decrypt(org, connection, "test-key", first));
        assertThrows(CrmException.class, () -> cipher.decrypt(UUID.randomUUID(), connection, "test-key", first));
        assertThrows(CrmException.class, () -> cipher.decrypt(org, UUID.randomUUID(), "test-key", first));
        assertThrows(CrmException.class, () -> cipher.decrypt(org, connection, "other-key", first));
        first[first.length-1] ^= 1;
        assertThrows(CrmException.class, () -> cipher.decrypt(org, connection, "test-key", first));
        assertThrows(CrmException.class, () -> cipher.decrypt(org, connection, "test-key", new byte[0]));
    }
    @Test void missingKeyFailsClosedAndSecretsDoNotAppearInToString() {
        var settings = new CrmSettings("", "key", 1000, 2000, 4096);
        var missing = new CrmSecretCipher(settings);
        assertFalse(missing.configured());
        assertEquals("CRM_CONFIGURATION_REQUIRED", assertThrows(CrmException.class,
                () -> missing.encrypt(UUID.randomUUID(), UUID.randomUUID(), "test-secret")).code());
        assertFalse(new CreateCrmConnectionRequest(CrmType.DIKIDI, "70000000000", "test-secret").toString().contains("test-secret"));
        assertFalse(new UpdateCrmConnectionRequest(0L, "70000000000", "test-secret", null).toString().contains("test-secret"));
        var access = new CrmAccess(UUID.randomUUID(), 1, "70000000000", "test-secret".toCharArray());
        assertFalse(access.toString().contains("test-secret")); access.close();
        assertTrue(new String(access.secret()).chars().allMatch(c -> c == 0));
        assertFalse(cipher().toString().contains(Base64.getEncoder().encodeToString(new byte[32])));
    }
}

