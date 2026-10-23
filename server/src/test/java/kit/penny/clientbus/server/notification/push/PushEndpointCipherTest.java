package kit.penny.clientbus.server.notification.push;

import kit.penny.clientbus.common.notification.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PushEndpointCipherTest {
    static PushSettings settings(Duration timeout){return new PushSettings(Base64.getEncoder().encodeToString(new byte[32]),
        List.of(new PushSettings.AllowedApp("clientbus.test",PushProviderType.FCM,PushEnvironment.SANDBOX)),2,timeout);}
    @Test void encryptsWithFreshNonceAndAuthenticatesCiphertextAndVersion(){
        var cipher=new PushEndpointCipher(settings(Duration.ofSeconds(1)));
        byte[] first=cipher.encrypt("private-provider-reference"),second=cipher.encrypt("private-provider-reference");
        assertFalse(Arrays.equals(first,second));assertEquals("private-provider-reference",cipher.decrypt(first));
        first[first.length-1]^=1;
        assertThrows(PushEndpointCipher.Unavailable.class,()->cipher.decrypt(first));
        second[0]=2;
        assertThrows(PushEndpointCipher.Unavailable.class,()->cipher.decrypt(second));
        assertFalse(cipher.toString().contains("private-provider-reference"));
    }
    @Test void missingKeyKeepsDefaultAppBootableButRefusesCredentialPersistence(){
        var cipher=new PushEndpointCipher(new PushSettings("",List.of(),2,Duration.ofSeconds(1)));
        assertThrows(PushEndpointCipher.Unavailable.class,()->cipher.encrypt("secret"));
        assertThrows(IllegalArgumentException.class,()->new PushEndpointCipher(new PushSettings("invalid",List.of(),2,Duration.ofSeconds(1))));
    }
    @Test void providerTimeoutRequiresAtLeastOneMillisecond(){
        assertThrows(IllegalArgumentException.class,()->settings(Duration.ofNanos(999_999)));
        assertEquals(Duration.ofMillis(1),settings(Duration.ofMillis(1)).providerTimeout());
    }
    @Test void allowlistIncludesProviderEnvironmentAndApnsPlatform(){
        var settings=settings(Duration.ofSeconds(1));
        assertTrue(settings.allows("clientbus.test",PushProviderType.FCM,PushEnvironment.SANDBOX,PushPlatform.ANDROID));
        assertFalse(settings.allows("clientbus.test",PushProviderType.FCM,PushEnvironment.PRODUCTION,PushPlatform.ANDROID));
        assertFalse(settings.allows("other",PushProviderType.FCM,PushEnvironment.SANDBOX,PushPlatform.ANDROID));
        var apns=new PushSettings("",List.of(new PushSettings.AllowedApp("ios",PushProviderType.APNS,PushEnvironment.SANDBOX)),1,Duration.ofSeconds(1));
        assertFalse(apns.allows("ios",PushProviderType.APNS,PushEnvironment.SANDBOX,PushPlatform.ANDROID));
    }
}
