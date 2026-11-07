package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.crm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit diagnostic opt-in for an account that need not have a bound project. No DB writes or raw responses. */
@EnabledIfSystemProperty(named="clientbus.crm.connection.live",matches="true")
class CrmDikidiConnectionAcceptance {
    @Test void verifyExistingAccountWithoutAProject() throws Exception {
        String selected=System.getProperty("clientbus.crm.connection.id");
        assertTrue(selected!=null || Boolean.getBoolean("clientbus.crm.connection.latest"),"Select a connection explicitly.");
        if (selected!=null) UUID.fromString(selected);
        try (var context=new SpringApplicationBuilder(CrmSettings.class,CrmSecretCipher.class)
                .web(WebApplicationType.NONE).logStartupInfo(false)
                .properties("spring.main.banner-mode=off","logging.level.root=OFF").run()) {
            var env=context.getEnvironment(); DriverManager.setLoginTimeout(5);
            try (var db=DriverManager.getConnection(env.getRequiredProperty("spring.datasource.url"),
                    env.getRequiredProperty("spring.datasource.username"),env.getRequiredProperty("spring.datasource.password"))) {
                db.setReadOnly(true); db.setAutoCommit(false);
                try (var statement=db.prepareStatement("""
                        select id,organizationid,credentialversion,login,secretkeyid,secretciphertext
                        from crm.connection where provider='DIKIDI' and status<>'DISABLED'
                        and (?::uuid is null or id=?::uuid) order by createdat desc,id desc limit 1
                        """)) {
                    statement.setString(1,selected); statement.setString(2,selected); statement.setQueryTimeout(5);
                    try (var row=statement.executeQuery()) {
                        assertTrue(row.next(),"No selected enabled DIKIDI account.");
                        UUID id=row.getObject(1,UUID.class),organization=row.getObject(2,UUID.class);
                        long version=row.getLong(3); String login=row.getString(4),key=row.getString(5); byte[] encrypted=row.getBytes(6);
                        db.rollback();
                        System.out.println("CONNECTION_PROBE selected="+id);
                        char[] secret=context.getBean(CrmSecretCipher.class).decrypt(organization,id,key,encrypted);
                        Arrays.fill(encrypted,(byte)0);
                        try (var access=new CrmAccess(id,version,login,secret)) {
                            Arrays.fill(secret,'\0'); verify(access);
                        } finally { Arrays.fill(secret,'\0'); }
                    }
                }
            }
        } catch (SQLException failure) { fail("Connection probe database error: SQLSTATE="+failure.getSQLState()); }
    }
    private static void verify(CrmAccess access) {
        var policy=new DikidiAcceptancePolicy(16); var logins=new AtomicInteger();
        var settings=new DikidiProvider.Settings(Duration.ofSeconds(5),Duration.ofSeconds(15),4194304);
        try (var provider=new DikidiProvider(settings,() -> new DikidiTransport() {
            private final JdkDikidiTransport delegate=new JdkDikidiTransport(settings.connectTimeout(),settings.maxResponseBytes(),policy);
            public Response exchange(String method,URI uri,List<Map.Entry<String,String>> form,boolean ajax,String project,Duration timeout) {
                if (uri.getPath().equals("/ajax/user/auth/")) logins.incrementAndGet();
                var response=delegate.exchange(method,uri,form,ajax,project,timeout);
                var html=DikidiHtml.parse(response.body());
                if (uri.getPath().equals("/ru/")) System.out.printf("CONNECTION_PROBE page status=%d csrf=%s accountMarker=%s%n",
                        response.status(),html.hidden.containsKey("telegram_csrf"),html.authenticatedAccount());
                else {
                    boolean complete=false,password=false,error=false;
                    String reason="UNCLASSIFIED",errorCode="UNKNOWN";
                    try {
                        var json=JsonMapper.builder().build().readTree(response.body());
                        complete=json.path("callback").toString().contains("sw.auth.complete");
                        String body=json.path("html").asString("");
                        password=body.contains("name=\"password\"") || body.contains("name='password'");
                        error=json.has("error") && !"0".equals(json.path("error").asString(""));
                        String code=json.path("error").isObject() ? json.path("error").path("code").asString("") : json.path("error").asString("");
                        if (code.matches("[0-9]{1,6}")) errorCode=code;
                        String message=(json.path("message").asString("")+" "+json.path("error").path("message").asString("")).toLowerCase(Locale.ROOT);
                        if (message.matches("(?s).*(csrf|токен|token).*")) reason="TOKEN";
                        else if (message.matches("(?s).*(captcha|капч|робот).*")) reason="CAPTCHA";
                        else if (message.matches("(?s).*(согласи|agreement).*")) reason="CONSENT";
                        else if (message.matches("(?s).*(парол|password).*")) reason="PASSWORD";
                        else if (message.matches("(?s).*(смс|sms|подтверж|код).*")) reason="CONFIRMATION";
                        else if (message.matches("(?s).*(номер|phone|телефон).*")) reason="PHONE";
                        else if (message.matches("(?s).*(блокир|disabled).*")) reason="ACCOUNT_DISABLED";
                        else if (message.matches("(?s).*(лимит|позже|limit).*")) reason="RATE";
                    } catch (RuntimeException ignored) { /* Report structure only. */ }
                    System.out.printf("CONNECTION_PROBE %s status=%d authComplete=%s passwordForm=%s errorPresent=%s code=%s category=%s%n",
                            uri.getPath(),response.status(),complete,password,error,errorCode,reason);
                }
                return response;
            }
            public void close() { delegate.close(); }
        })) {
            provider.verifyConnection(access); int initial=logins.get();
            provider.verifyConnection(access); assertEquals(initial,logins.get(),"Repeated verification re-authenticated.");
            if (Boolean.getBoolean("clientbus.crm.projects.live")) {
                var projects=provider.listProjects(access);
                assertEquals(projects.size(),projects.stream().map(CrmProjectInfo::externalId).distinct().count());
                assertTrue(projects.stream().allMatch(project -> project.displayName()!=null && !project.displayName().isBlank()));
                System.out.printf("PROJECT_LIST_PROBE PASSED projects=%d localWrites=0%n",projects.size());
            }
            System.out.printf("CONNECTION_PROBE PASSED passwordLogins=%d httpCalls=%d localWrites=0%n",logins.get(),policy.calls());
        } catch (CrmFailure failure) {
            for (var frame:failure.getStackTrace()) if (frame.getClassName().equals(DikidiProvider.class.getName())) {
                System.out.println("CONNECTION_PROBE failureLocation="+frame.getMethodName()+":"+frame.getLineNumber()); break;
            }
            fail("Connection probe failed: "+failure.code());
        }
    }
}
