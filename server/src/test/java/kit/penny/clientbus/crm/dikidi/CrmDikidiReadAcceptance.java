package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.crm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.net.URI;
import java.sql.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in only: reads existing credentials and calls allowlisted DIKIDI reads.
 * No JPA, schema initialization, synchronization or local/provider business writes run here.
 * The class name deliberately excludes the default *Test selection.
 */
@EnabledIfSystemProperty(named="clientbus.crm.acceptance.live",matches="true")
class CrmDikidiReadAcceptance {
    @Test void readImplementedDatasetsFromAnExistingConnection() {
        String from=System.getProperty("clientbus.crm.acceptance.dateFrom");
        String to=System.getProperty("clientbus.crm.acceptance.dateTo");
        assertNotNull(from,"Set explicit acceptance dateFrom/dateTo (inclusive, at most 7 dates).");
        assertNotNull(to,"Set explicit acceptance dateFrom/dateTo (inclusive, at most 7 dates).");
        var query=new CrmAppointmentQuery(LocalDate.parse(from),LocalDate.parse(to));
        assertTrue(java.time.temporal.ChronoUnit.DAYS.between(query.dateFrom(),query.dateTo())<7,"Live read budget: at most 7 dates.");
        // Bootstrap only the configuration and cipher, never ServerApplication or auto-configuration.
        try (var context=new SpringApplicationBuilder(CrmSettings.class,CrmSecretCipher.class)
                .web(WebApplicationType.NONE).logStartupInfo(false)
                .properties("spring.main.banner-mode=off","logging.level.root=OFF").run()) {
            var env=context.getEnvironment();
            DriverManager.setLoginTimeout(5);
            try (var db=DriverManager.getConnection(env.getRequiredProperty("spring.datasource.url"),
                    env.getRequiredProperty("spring.datasource.username"),env.getRequiredProperty("spring.datasource.password"))) {
                db.setReadOnly(true); db.setAutoCommit(false);
                try (var statement=db.prepareStatement("""
                        select c.id,c.organizationid,c.credentialversion,c.login,c.secretkeyid,c.secretciphertext,p.id,p.externalid
                        from crm.connection c join crm.project p on p.connectionid=c.id
                        where p.enabled and c.provider='DIKIDI' and c.status<>'DISABLED'
                            and (?::uuid is null or p.id=?::uuid)
                            and (?::uuid is null or p.workspaceid=?::uuid) order by p.id limit 2
                        """)) {
                    String project=System.getProperty("clientbus.crm.acceptance.projectId");
                    String workspace=System.getProperty("clientbus.crm.acceptance.workspaceId");
                    if (project!=null) UUID.fromString(project);
                    if (workspace!=null) UUID.fromString(workspace);
                    statement.setString(1,project); statement.setString(2,project); statement.setQueryTimeout(5);
                    statement.setString(3,workspace); statement.setString(4,workspace);
                    try (var rows=statement.executeQuery()) {
                        assertTrue(rows.next(),"No enabled DIKIDI project in the configured database.");
                        UUID connection=rows.getObject(1,UUID.class),organization=rows.getObject(2,UUID.class);
                        long version=rows.getLong(3); String login=rows.getString(4),key=rows.getString(5);
                        byte[] encrypted=rows.getBytes(6); String externalProject=rows.getString(8);
                        assertFalse(rows.next(),"Several projects are enabled; select clientbus.crm.acceptance.projectId explicitly.");
                        db.rollback();
                        char[] secret=context.getBean(CrmSecretCipher.class).decrypt(organization,connection,key,encrypted);
                        Arrays.fill(encrypted,(byte)0);
                        try (var access=new CrmAccess(connection,version,login,secret)) {
                            Arrays.fill(secret,'\0');
                            inspect(access,externalProject,query);
                        } finally { Arrays.fill(secret,'\0'); }
                    }
                }
            }
        } catch (CrmFailure error) {
            fail("DIKIDI live acceptance failed: "+error.code());
        } catch (SQLException error) {
            fail("Read-only database access failed: SQLSTATE="+error.getSQLState());
        } catch (RuntimeException error) {
            // Never attach provider response bodies, credentials or configuration as a failure cause.
            fail("Live acceptance failed: "+error.getClass().getSimpleName());
        }
    }
    private static void inspect(CrmAccess access,String project,CrmAppointmentQuery query) {
        var policy=new DikidiAcceptancePolicy();
        var passwordLogins=new java.util.concurrent.atomic.AtomicInteger();
        var settings=new DikidiProvider.Settings(Duration.ofSeconds(5),Duration.ofSeconds(15),4194304);
        try (var provider=new DikidiProvider(settings,() -> new DikidiTransport() {
            private final JdkDikidiTransport delegate=new JdkDikidiTransport(settings.connectTimeout(),settings.maxResponseBytes(),policy);
            @Override public Response exchange(String method,URI uri,List<Map.Entry<String,String>> form,
                    boolean ajax,String projectId,Duration timeout) {
                if (uri.getPath().equals("/ajax/user/auth/")) passwordLogins.incrementAndGet();
                var response=delegate.exchange(method,uri,form,ajax,projectId,timeout);
                if (Boolean.getBoolean("clientbus.crm.acceptance.diagnostics") && uri.getPath().endsWith("/clients/filter/"))
                    clientResponseShape(response);
                return response;
            }
            @Override public void close() { delegate.close(); }
        })) {
            // Exercise verification before any project has been read or cached.
            provider.verifyConnection(access);
            provider.verifyConnection(access);
            assertEquals(1,passwordLogins.get(),"Repeated account verification must not log in again.");
            var info=provider.inspectProject(access,project);
            assertTrue(project.equals(info.externalId()),"Project identity mismatch.");
            var catalog=provider.readCatalog(access,project);
            validateCatalog(catalog,project);
            int initialLogins=passwordLogins.get();
            provider.verifyConnection(access);
            provider.verifyConnection(access);
            assertEquals(initialLogins,passwordLogins.get(),"Verification must reuse the authenticated session.");
            System.out.println("LIVE currentSessionVerification=PASSED repeatedPasswordLogins=0");
            System.out.printf("LIVE catalog masters=%d services=%d offers=%d namePresent=%s zoneKnown=%s%n",
                    catalog.masters().size(),catalog.services().size(),catalog.offers().size(),catalog.projectDisplayName()!=null,info.zoneId()!=null);

            var clients=new HashMap<CrmClientReference,CrmClient>();
            String cursor=null; int clientPages=0,nonemptyClientPages=0;
            do {
                var page=provider.readClients(access,project,cursor);
                assertTrue((project+":CLIENTS").equals(page.scope()),"Client project mismatch.");
                assertEquals(CrmReadResult.Completeness.UNKNOWN,page.completeness());
                for (var client:page.items()) assertTrue(clients.put(new CrmClientReference(client.identityKind(),client.externalId()),client)==null,"Client repeated between pages.");
                clientPages++; if (!page.items().isEmpty()) nonemptyClientPages++;
                assertFalse(cursor!=null && cursor.equals(page.nextCursor()),"Client cursor did not advance.");
                cursor=page.nextCursor();
            } while (cursor!=null && clientPages<3);
            System.out.printf("LIVE clients=%d pages=%d nonemptyPages=%d terminal=%s completeness=UNKNOWN%n",clients.size(),clientPages,nonemptyClientPages,cursor==null);

            int schedules=0,mastersChecked=0;
            for (var master:catalog.masters().stream().limit(5).toList()) {
                var scope=new CrmWorkIntervalQuery(master.externalId(),query.dateFrom(),query.dateTo());
                var result=provider.readWorkIntervals(access,project,scope);
                assertTrue(project.equals(result.externalProjectId()) && scope.equals(result.scope()),"Schedule scope mismatch.");
                assertEquals(CrmReadResult.Completeness.UNKNOWN,result.completeness());
                schedules+=result.items().size(); mastersChecked++;
            }
            System.out.printf("LIVE schedules=%d mastersChecked=%d completeness=UNKNOWN%n",schedules,mastersChecked);

            var appointments=readAppointments(provider,access,project,query,false);
            long linked=appointments.values().stream().filter(row -> clients.containsKey(row.client())).count();
            long multiService=appointments.values().stream().filter(row -> row.services().size()>1).count();
            long multiMaster=appointments.values().stream().filter(row -> row.masters().size()>1).count();
            System.out.printf("LIVE appointments=%d knownClientReferences=%d multiService=%d multiMaster=%d completeness=UNKNOWN%n",appointments.size(),linked,multiService,multiMaster);
            var identity=appointments.values().stream().filter(row -> "APPOINTMENT".equals(row.kind()) && row.client()!=null
                    && "PROJECT".equals(row.client().identityKind())).map(CrmAppointment::client).findFirst()
                    .orElseGet(() -> clients.keySet().stream().filter(row -> "PROJECT".equals(row.identityKind())).findFirst().orElse(null));
            if (identity!=null) {
                var historyQuery=new CrmAppointmentQuery(query.dateFrom(),query.dateTo(),identity);
                var history=readAppointments(provider,access,project,historyQuery,true);
                verifyClientHistory(appointments,history,identity);
                System.out.printf("LIVE clientHistory=%d journalIdsMatch=true completeness=UNKNOWN%n",history.size());
            } else System.out.println("LIVE clientHistory=UNOBSERVED_NO_PROJECT_CLIENT");
            if (Boolean.getBoolean("clientbus.crm.acceptance.finance")) {
                boolean observed=false;
                for (var visit:appointments.values()) {
                    if (visit.client()==null || !"APPOINTMENT".equals(visit.kind())) continue;
                    try {
                        var finance=provider.readFinance(access,project,new CrmFinanceQuery(visit.externalId(),visit.localStart().toLocalDate(),visit.client()));
                        assertFalse(finance.lines().isEmpty()); assertFalse(finance.payments().isEmpty());
                        System.out.printf("LIVE finance lines=%d payments=%d currencyKnown=%s completeness=%s%n",
                                finance.lines().size(),finance.payments().size(),finance.currency()!=null,finance.completeness());
                        observed=true; break;
                    } catch (CrmFailure failure) {
                        if (failure.code()==CrmFailure.Code.CAPABILITY_UNVERIFIED) continue;
                        if (Boolean.getBoolean("clientbus.crm.acceptance.diagnostics"))
                            for (var frame:failure.getStackTrace()) if (frame.getClassName().startsWith("kit.penny.clientbus.crm.dikidi."))
                                System.out.println("FINANCE_FAILURE_LOCATION "+frame.getClassName()+":"+frame.getLineNumber());
                        throw failure;
                    }
                }
                assertTrue(observed,"No supported visit finance observed; finance acceptance is not complete.");
            }
            provider.invalidate(access.connectionId());
            validateCatalog(provider.readCatalog(access,project),project);
            System.out.printf("LIVE freshSession=PASSED httpCalls=%d providerWrites=0 localWrites=0%n",policy.calls());
        }
    }
    static void verifyClientHistory(Map<String,CrmAppointment> journal,Map<String,CrmAppointment> history,CrmClientReference identity) {
        assertTrue(history.values().stream().allMatch(row -> identity.equals(row.client()) && "APPOINTMENT".equals(row.kind())),
                "History leaked another client or record kind.");
        var journalIds=new HashSet<String>();
        journal.forEach((id,row) -> { if (identity.equals(row.client()) && "APPOINTMENT".equals(row.kind())) journalIds.add(id); });
        assertTrue(journalIds.equals(history.keySet()),
                "Client history differs from journal. Repeat the read if provider data changed concurrently; this run is not accepted.");
    }
    private static void validateCatalog(CrmCatalogSnapshot catalog,String project) {
        assertTrue((project+":CATALOG").equals(catalog.scope()),"Catalog project mismatch.");
        var masters=new HashSet<String>(); catalog.masters().forEach(row -> masters.add(row.externalId()));
        var services=new HashSet<String>(); catalog.services().forEach(row -> services.add(row.externalId()));
        assertTrue(catalog.offers().stream().allMatch(row -> masters.contains(row.externalMasterId()) && services.contains(row.externalServiceId())),"Catalog contains an orphan offer.");
    }
    /** Structural diagnostics only: never print HTML, IDs, contact data or provider messages. */
    private static void clientResponseShape(DikidiTransport.Response response) {
        try {
            var root=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
            System.out.printf("LIVE clientResponse status=%d rootHtml=%s scripts=%d%n",response.status(),root.path("html").isString(),root.path("script").size());
            var fragments=new ArrayList<String>();
            if (root.path("html").isString()) fragments.add(root.path("html").asString());
            for (var script:root.path("script")) {
                System.out.println("LIVE scriptKeys="+script.properties().stream().map(Map.Entry::getKey).filter(key -> key.matches("[a-zA-Z_]{1,30}")).sorted().toList());
                for (String field:List.of("append","container")) {
                    String selector=script.path(field).asString("");
                    if (!selector.isEmpty() && selector.matches("[a-zA-Z_#. >:-]{1,100}"))
                        System.out.println("LIVE "+field+"Selector="+selector);
                }
                if (script.path("html").isString()) fragments.add(script.path("html").asString());
            }
            for (var html:fragments) {
                int rows=0,names=0,anchors=0,phones=0,systems=0;
                var structure=new ArrayList<String>();
                var tags=java.util.regex.Pattern.compile("<([a-zA-Z]+)\\b([^>]*)>").matcher(html);
                while (tags.find()) {
                    String tag=tags.group(1).toLowerCase(Locale.ROOT),attrs=tags.group(2);
                    if (tag.equals("tr") && attrs.matches("(?s).*\\bid=['\"]item-.*")) rows++;
                    if (attrs.contains("line-name")) names++;
                    if (attrs.contains("line-phone")) phones++;
                    if (attrs.matches("(?s).*data-is-system=['\"]1['\"].*")) systems++;
                    if (tag.equals("a")) anchors++;
                    String structureTag=tag;
                    var classes=java.util.regex.Pattern.compile("\\bclass=['\"]([a-zA-Z _-]{1,100})['\"]").matcher(attrs);
                    if (classes.find()) structureTag+="."+classes.group(1).replace(' ','.');
                    if (structure.size()<40) structure.add(structureTag);
                }
                System.out.printf("LIVE clientHtml chars=%d itemRows=%d names=%d anchors=%d phones=%d systemFlags=%d blank=%s%n",html.length(),rows,names,anchors,phones,systems,html.isBlank());
                System.out.println("LIVE clientHtml structure="+structure+" commentsOnly="+html.replaceAll("(?s)<!--.*?-->","").isBlank());
            }
        } catch (RuntimeException error) { System.out.println("LIVE clientResponse diagnostics=UNAVAILABLE"); }
    }
    private static Map<String,CrmAppointment> readAppointments(DikidiProvider provider,CrmAccess access,String project,
            CrmAppointmentQuery query,boolean history) {
        var items=new HashMap<String,CrmAppointment>(); var cursors=new HashSet<String>(); String cursor=null; int pages=0;
        do {
            var page=history ? provider.readClientHistory(access,project,query,cursor) : provider.readAppointments(access,project,query,cursor);
            assertTrue(project.equals(page.externalProjectId()) && query.equals(page.scope()),"Appointment scope mismatch.");
            assertEquals(CrmReadResult.Completeness.UNKNOWN,page.completeness());
            for (var row:page.items()) assertTrue(items.put(row.externalId(),row)==null,"Appointment repeated between date pages.");
            assertTrue(++pages<=7,"Live date-page budget exceeded.");
            cursor=page.nextCursor(); assertTrue(cursor==null || cursors.add(cursor),"Appointment cursor did not advance.");
        } while (cursor!=null);
        return items;
    }
}
