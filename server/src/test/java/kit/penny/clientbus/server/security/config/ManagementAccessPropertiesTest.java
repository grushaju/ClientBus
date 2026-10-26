package kit.penny.clientbus.server.security.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class ManagementAccessPropertiesTest {
    private static final String USERNAME="test-only-operator";
    private static final String HASH=new BCryptPasswordEncoder(4).encode("test-only operator password");

    private ManagementAccessProperties bind(Map<String,String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("clientbus.management",ManagementAccessProperties.class);
    }

    @Test void absentCredentialsAreDisabledForDirectConstructionAndConfigurationBinding() {
        for(var credentials:new ManagementAccessProperties[]{
                new ManagementAccessProperties(null,null),new ManagementAccessProperties("",""),
                bind(Map.of()),bind(Map.of("clientbus.management.username","",
                        "clientbus.management.password-hash",""))}) {
            assertFalse(credentials.enabled());
            assertEquals("",credentials.username());
            assertEquals("",credentials.passwordHash());
        }
    }

    @Test void validCredentialsBindWithoutChangingTheUsernameOrHash() {
        var credentials=bind(Map.of("clientbus.management.username",USERNAME,
                "clientbus.management.password-hash",HASH));
        assertTrue(credentials.enabled());
        assertEquals(USERNAME,credentials.username());
        assertEquals(HASH,credentials.passwordHash());
    }

    @Test void supportedBcryptVersionsAndCostBoundariesAreAcceptedWithoutHashingAtThoseCosts() {
        for(String version:new String[]{"2a","2b","2y"})
            for(String cost:new String[]{"04","10","16"}) {
                String hash="$"+version+"$"+cost+HASH.substring(6);
                var credentials=new ManagementAccessProperties(USERNAME,hash);
                assertTrue(credentials.enabled());
                assertEquals(hash,credentials.passwordHash());
            }
    }

    @Test void partialCredentialsAreRejectedInsteadOfEnablingAnUnusableIdentity() {
        for(String missing:new String[]{null,""," "}) {
            assertThrows(IllegalArgumentException.class,()->new ManagementAccessProperties(missing,HASH));
            assertThrows(IllegalArgumentException.class,()->new ManagementAccessProperties(USERNAME,missing));
        }
        assertThrows(RuntimeException.class,()->bind(Map.of("clientbus.management.username",USERNAME)));
        assertThrows(RuntimeException.class,()->bind(Map.of("clientbus.management.password-hash",HASH)));
    }

    @Test void oversizedAndControlCharacterUsernamesAreRejected() {
        for(String username:new String[]{"x".repeat(129)," operator","operator ","operator\n",
                "oper\rator","oper\tator","oper\u0000ator","oper\u007fator","operator:extra"})
            assertThrows(IllegalArgumentException.class,()->new ManagementAccessProperties(username,HASH));
        assertTrue(new ManagementAccessProperties("x".repeat(128),HASH).enabled());
    }

    @Test void malformedAndOversizedHashesAreRejected() {
        for(String hash:new String[]{"not-a-bcrypt-hash",HASH.substring(1),HASH+"x"," "+HASH,HASH+"\n",
                HASH.replace("$2a$","$2x$"),HASH.substring(0,59),HASH.substring(0,59)+"?","x".repeat(1024)})
            assertThrows(IllegalArgumentException.class,()->new ManagementAccessProperties(USERNAME,hash));
    }

    @Test void unsupportedBcryptCostsAreRejectedWithoutPerformingPasswordVerification() {
        for(String cost:new String[]{"00","03","17","31","99"}) {
            String hash=HASH.substring(0,4)+cost+HASH.substring(6);
            assertThrows(IllegalArgumentException.class,()->new ManagementAccessProperties(USERNAME,hash));
        }
    }

    @Test void stringRepresentationsNeverExposeConfiguredCredentials() {
        String enabled=new ManagementAccessProperties(USERNAME,HASH).toString();
        assertTrue(enabled.contains("enabled=true"));
        assertTrue(enabled.contains("redacted"));
        assertFalse(enabled.contains(USERNAME));
        assertFalse(enabled.contains(HASH));
        assertTrue(new ManagementAccessProperties(null,null).toString().contains("enabled=false"));
    }

    @Test void validationErrorsDoNotIncludeRejectedConfigurationValues() {
        String rejectedHash="sensitive-invalid-password-hash";
        var failure=assertThrows(IllegalArgumentException.class,
                ()->new ManagementAccessProperties(USERNAME,rejectedHash));
        assertFalse(failure.getMessage().contains(USERNAME));
        assertFalse(failure.getMessage().contains(rejectedHash));
        var bindingFailure=assertThrows(RuntimeException.class,
                ()->bind(Map.of("clientbus.management.username",USERNAME,
                        "clientbus.management.password-hash",rejectedHash)));
        for(Throwable cause=bindingFailure;cause!=null;cause=cause.getCause()) {
            String message=cause.getMessage();
            if(message!=null) {
                assertFalse(message.contains(USERNAME));
                assertFalse(message.contains(rejectedHash));
            }
        }
    }
}
