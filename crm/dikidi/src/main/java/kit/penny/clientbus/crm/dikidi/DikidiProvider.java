package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.integration.dikidi.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.*;

/** Verified owner reads and bounded commands. Mutating sequences never use the read/auth retry loop. */
public final class DikidiProvider implements CrmProvider, AutoCloseable {
    public record Settings(Duration connectTimeout, Duration requestTimeout, int maxResponseBytes) {
        public Settings {
            if (connectTimeout == null || requestTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                    || requestTimeout.isNegative() || requestTimeout.isZero() || maxResponseBytes < 1024)
                throw new IllegalArgumentException("Invalid DIKIDI settings");
        }
    }
    /** 13.4: транспорт, сессии входа и регулятор принадлежат общему DikidiClient. */
    private final DikidiClient client;
    private final boolean ownsClient;
    public DikidiProvider parallelism(int value){ client.parallelism(value); return this; }
    private final JsonMapper json = JsonMapper.builder().build();
    private static final class Effect { boolean changing,started; }
    private final ThreadLocal<Effect> effect=new ThreadLocal<>();
    private CrmJournalPort journals;
    private DikidiDayCache days;
    public DikidiProvider journalCache(CrmJournalPort port,Duration ttl,int entries,int bytes,int entryBytes){
        return journalCache(port,ttl,entries,bytes,entryBytes,true);
    }
    public DikidiProvider journalCache(CrmJournalPort port,Duration ttl,int entries,int bytes,int entryBytes,boolean reuse){
        if(journals!=null)throw new IllegalStateException("Journal cache already configured");
        journals=Objects.requireNonNull(port);days=new DikidiDayCache(ttl,entries,bytes,entryBytes,Clock.systemUTC(),reuse);return this;
    }
    public DikidiProvider(Settings settings) {
        this(settings,new DikidiClient(CrmBridges.settings(settings)),true);
    }
    public DikidiProvider(Settings settings,DikidiRequests.Settings requests,CrmTrafficPort pauses) {
        this(settings,new DikidiClient(CrmBridges.settings(settings),requests,CrmBridges.traffic(pauses)),true);
    }
    public DikidiProvider(Settings settings,Supplier<DikidiTransport> factory) { this(settings,new DikidiClient(CrmBridges.settings(settings),factory),true); }
    public DikidiProvider(Settings settings,Supplier<DikidiTransport> factory,DikidiRequests traffic) { this(settings,new DikidiClient(CrmBridges.settings(settings),factory,traffic),true); }
    /**
     * 13.4: общий клиент с чатовым потребителем. Один экземпляр на сборку;
     * close-владелец — сборка (Spring destroyMethod), адаптер клиент не закрывает.
     */
    public DikidiProvider(Settings settings,DikidiClient client) { this(settings,client,false); }
    private DikidiProvider(Settings settings,DikidiClient client,boolean ownsClient){this.client=client;this.ownsClient=ownsClient;}
    @Override public Instant retryAt(UUID connectionId){return client.retryAt(connectionId);}
    public DikidiRequests.Diagnostics diagnostics(){return client.diagnostics();}
    public record JournalDiagnostics(long hits,long loads,long joins,int entries,long bytes) {}
    public JournalDiagnostics journalDiagnostics(){
        if(days==null)return new JournalDiagnostics(0,0,0,0,0);
        var value=days.diagnostics();return new JournalDiagnostics(value.hits(),value.loads(),value.joins(),value.entries(),value.bytes());
    }
    @Override public String code() { return "DIKIDI"; }

    @Override public Set<CrmCommand.Type> commandCapabilities(){return Set.of(CrmCommand.Type.CREATE,CrmCommand.Type.MOVE,CrmCommand.Type.CANCEL);}
    @Override public void prepareCommand(CrmAccess access,String project,CrmCommand command,Instant until) {
        projectId(project);DikidiCommands.validate(command);if(until==null)throw new CrmFailure(INVALID_RESPONSE);
        execute(access,project,(session,deadline)->{catalog(session,project,deadline);return null;},until);
    }
    @Override public CrmCommandResult command(CrmAccess access,String project,CrmCommand command,Instant until,CrmCommandPort.Progress progress) {
        projectId(project);DikidiCommands.validate(command);
        if(until==null || !until.isAfter(Instant.now()) || command.type()==CrmCommand.Type.CREATE && progress==null)throw new CrmFailure(CAPABILITY_UNVERIFIED);
        var state=new Effect();effect.set(state);
        try {
            return executeOnce(access,project,until,(session,deadline)->{
                var catalog=catalog(session,project,deadline,command.type()==CrmCommand.Type.CREATE);
                return DikidiCommands.run((method,path,form)->{
                    state.changing=path.startsWith("/ajax/newrecord/time_reservation/") || path.startsWith("/owner/ajax/journal/newrecord_record_save/")
                            || path.startsWith("/ru/owner/ajax/journal/record_save/") || path.startsWith("/ru/owner/ajax/journal/remove/");
                    try{return tree(call(session,method,"https://dikidi.ru"+path,form,true,project));}finally{state.changing=false;}
                },project,command,catalog,progress);
            });
        } catch(CrmFailure failure) {
            if(!state.started)return new CrmCommandResult(CrmCommandResult.Outcome.REJECTED,command.externalAppointmentId(),failure.code().name(),failure.retryAt());
            throw failure;
        } finally {effect.remove();}
    }
    @Override public CrmCommandResult reconcile(CrmAccess access,String project,CrmCommand command,Instant until) {
        projectId(project);DikidiCommands.validate(command);
        if(until==null)throw new CrmFailure(INVALID_RESPONSE);
        return execute(access,project,(session,deadline)->{
            catalog(session,project,deadline);
            return DikidiCommands.reconcile((method,path,form)->tree(call(session,method,"https://dikidi.ru"+path,form,true,project)),project,command);
        },until);
    }

    private <T> T executeOnce(CrmAccess access,String project,Instant until,Read<T> action) {
        try (var credential = CrmBridges.credential(access)) {
            return client.executeOnce(credential,until,session->action.apply(session,session.deadline()));
        } catch(DikidiFailure error){ throw CrmBridges.failure(error); }
    }

    @Override public CrmAvailability availability(CrmAccess access,String project,String master,List<String> services,LocalDate date,Instant until) {
        projectId(project); projectId(master);
        if (services==null || services.isEmpty() || services.size()>10 || services.stream().anyMatch(id->id==null || !id.matches("[0-9]{1,20}"))
                || new HashSet<>(services).size()!=services.size() || date==null || date.getYear()<2000 || date.getYear()>2100 || until==null)
            throw new CrmFailure(INVALID_RESPONSE);
        if (!until.isAfter(Instant.now())) throw new CrmFailure(TEMPORARY_FAILURE);
        var selected=List.copyOf(services);
        return execute(access,project,(session,deadline)->{
            var catalog=catalog(session,project,deadline);
            if (parseMasters(catalog).stream().noneMatch(row->master.equals(row.externalId()))) throw new CrmFailure(CONTEXT_MISMATCH);
            String address="https://dikidi.ru/ajax/newrecord/to_master_get_datetime/?company_id="+project+"&master_id="+master
                    +"&date="+date+"&action_source_id=3&is-from-journal=1";
            for (var service:selected) address+="&services_id%5B%5D="+service;
            Instant observed=Instant.now();
            return DikidiAvailability.parse(tree(call(session,"GET",address,null,true,project)),project,master,selected,date,observed);
        },until);
    }

    @Override public CrmFinanceSnapshot readFinance(CrmAccess access,String project,CrmFinanceQuery query) {
        projectId(project);
        if (query==null) throw new CrmFailure(INVALID_RESPONSE);
        return execute(access,project,(session,deadline) -> {
            call(session,"GET","https://dikidi.ru/ru/owner/journal/?company="+project,null,false,project);
            var response=tree(call(session,"POST","https://dikidi.ru/ru/owner/ajax/journal/api/?company="+project,
                    List.of(Map.entry("request[get][]","company.accesses"),Map.entry("request[get][]","masters.info"),
                            Map.entry("request[get][]","masters.records"),Map.entry("request[options][company][accesses][]",project),
                            Map.entry("request[options][masters][records][0][date_from]",query.date().toString()),
                            Map.entry("request[options][masters][records][0][date_to]",query.date().toString())),true,project));
            if (!response.path("company").path("accesses").path(project).isContainer()
                    || response.path("company").path("accesses").path(project).isEmpty()) throw new CrmFailure(CONTEXT_MISMATCH);
            parseMasters(response);
            var visit=DikidiAppointments.parse(response,project,query.date()).stream()
                    .filter(item -> query.externalAppointmentId().equals(item.externalId())).findFirst().orElseThrow(() -> new CrmFailure(CONTEXT_MISMATCH));
            if (!"APPOINTMENT".equals(visit.kind()) || visit.client()==null || !"PROJECT".equals(visit.client().identityKind()))
                throw new CrmFailure(CAPABILITY_UNVERIFIED);
            if (!Objects.equals(query.client(),visit.client())) throw new CrmFailure(CONTEXT_MISMATCH);
            JsonNode payment=null;
            for (var master:response.path("masters").path("records").path("data").properties()) {
                var row=master.getValue().path(query.date().toString()).path(query.externalAppointmentId());
                if (row.isMissingNode()) continue;
                var evidence=row.path("info").path("payment");
                if (!evidence.isObject()) throw new CrmFailure(CAPABILITY_UNVERIFIED);
                if (payment!=null && !payment.equals(evidence)) throw new CrmFailure(CONTEXT_MISMATCH);
                payment=evidence;
            }
            if (payment==null) throw new CrmFailure(CONTEXT_MISMATCH);
            String document=payment.path("document_id").asString("");
            if (document.isBlank() || document.equals("0")) throw new CrmFailure(CAPABILITY_UNVERIFIED);
            String sale=DikidiFinanceHtml.id(document);
            var lines=List.<CrmFinanceSnapshot.Line>of(); var payments=new ArrayList<CrmFinanceSnapshot.Payment>();
            BigDecimal discount=null,bonus=null;
            if (sale!=null) {
                var form=DikidiFinanceHtml.parse(financeHtml(tree(call(session,"GET",
                        "https://dikidi.ru/ru/owner/ajax/sales/form/"+sale+"/?company="+project,null,true,project))));
                form.requireIdentity("sale_id",sale);
                form.requireIdentity("table","clients"); form.requireIdentity("table_id",visit.client().externalId());
                lines=form.lines(); discount=form.optionalAmount("head_discount"); bonus=form.optionalAmount("head_bonus");
                var list=DikidiFinanceHtml.parse(financeHtml(tree(call(session,"POST","https://dikidi.ru/owner/ajax/sales_payments/get_list/",
                        List.of(Map.entry("company",project),Map.entry("sale_id",sale),Map.entry("isEdit","false")),true,project))));
                var paymentIds=list.paymentIds(project,sale);
                // No confirmed empty F02 shape is available yet. Missing links can also
                // mean an unavailable/error dialog, independently of paid or debt totals.
                if (paymentIds.isEmpty()) throw new CrmFailure(CAPABILITY_UNVERIFIED);
                for (String paymentId:paymentIds) {
                    var detail=tree(call(session,"GET","https://dikidi.ru/ru/owner/ajax/sales_payments/form/?company="+project+"&sale="+sale+"&id="+paymentId,
                            null,true,project));
                    var fields=DikidiFinanceHtml.parse(financeHtml(detail));
                    fields.requireIdentity("sale_id",sale); fields.requireIdentity("payment_id",paymentId);
                    // The response-level id identifies the UI dialog, not the payment.
                    // Only the form's explicit sale_id/payment_id establish financial identity.
                    payments.add(new CrmFinanceSnapshot.Payment(paymentId,fields.one("date"),fields.components()));
                }
            }
            var company=tree(call(session,"GET","https://dikidi.ru/mobile/ajax/newrecord/company_services?company="+project+"&array=1&lang=ru",null,true,project));
            String currency=company.path("data").path("currency").path("iso").asString("");
            return new CrmFinanceSnapshot(project,query,sale,currency.matches("[A-Z]{3}") ? currency : null,
                    DikidiFinanceHtml.amount(payment.path("cost").asString("")),DikidiFinanceHtml.amount(payment.path("paid").asString("")),
                    DikidiFinanceHtml.amount(payment.path("debt").asString("")),discount,bonus,lines,payments,Instant.now(),CrmReadResult.Completeness.UNKNOWN);
        });
    }
    private static String financeHtml(JsonNode result) {
        if (result.path("html").isString()) return result.path("html").asString();
        String found=null;
        if (result.path("script").isArray()) for (var script:result.path("script")) if (script.path("html").isString()) {
            if (found!=null) throw new CrmFailure(INVALID_RESPONSE);
            found=script.path("html").asString();
        }
        if (found==null) throw new CrmFailure(CAPABILITY_UNVERIFIED);
        return found;
    }

    @Override public CrmAppointmentPage readAppointments(CrmAccess access,String project,CrmAppointmentQuery query,String cursor) {
        if (query==null || query.client()!=null) throw new CrmFailure(INVALID_RESPONSE);
        return appointments(access,project,query,cursor);
    }
    @Override public CrmAppointmentPage readClientHistory(CrmAccess access,String project,CrmAppointmentQuery query,String cursor) {
        if (query==null || query.client()==null) throw new CrmFailure(INVALID_RESPONSE);
        return appointments(access,project,query,cursor);
    }
    @Override public CrmAppointmentPage readAppointments(CrmAccess access,String project,CrmAppointmentQuery query,String cursor,CrmJournalOptions options){
        if(query==null||query.client()!=null)throw new CrmFailure(INVALID_RESPONSE);
        return journal(access,project,query,cursor,options);
    }
    @Override public CrmAppointmentPage readClientHistory(CrmAccess access,String project,CrmAppointmentQuery query,String cursor,CrmJournalOptions options){
        if(query==null||query.client()==null)throw new CrmFailure(INVALID_RESPONSE);
        return journal(access,project,query,cursor,options);
    }
    private CrmAppointmentPage journal(CrmAccess access,String project,CrmAppointmentQuery query,String cursor,CrmJournalOptions options){
        if(options==null||journals==null)return appointments(access,project,query,cursor);
        if(query.externalAppointmentId()!=null){
            if(!journals.current(options,access.connectionId(),access.credentialVersion()))throw new CrmFailure(CONTEXT_MISMATCH);
            var health=journals.health(access.connectionId(),access.credentialVersion());
            try{
                long sequence=journals.begin(options.projectId(),options.epoch());
                var page=appointments(access,project,query,cursor);
                if(!journals.current(options,access.connectionId(),access.credentialVersion()))throw new CrmFailure(CONTEXT_MISMATCH);
                return new CrmAppointmentPage(page.items(),page.nextCursor(),page.observedAt(),page.completeness(),page.externalProjectId(),page.scope(),new CrmJournalObservation(options.epoch(),sequence,query.dateFrom(),false,health));
            }catch(CrmFailure failure){throw failure.withSourceHealth(health);}
        }
        projectId(project);LocalDate day;
        try{day=cursor==null?query.dateFrom():LocalDate.parse(cursor);}catch(DateTimeException invalid){throw new CrmFailure(INVALID_RESPONSE);}
        if(day.isBefore(query.dateFrom())||day.isAfter(query.dateTo()))throw new CrmFailure(INVALID_RESPONSE);
        var key=new DikidiDayCache.Key(options.projectId(),access.connectionId(),access.credentialVersion(),project,day,options.contextVersion(),options.zoneRevision(),options.epoch());
        java.util.function.BooleanSupplier current=()->journals.current(options,access.connectionId(),access.credentialVersion());
        if(!current.getAsBoolean())throw new CrmFailure(CONTEXT_MISMATCH);
        var cached=days.read(key,access,options,owned->loadJournal(owned,project,day,options),current);
        var snapshot=cached.snapshot();var items=DikidiAppointments.parse(tree(snapshot.body()),project,day);
        if(query.client()!=null)items=items.stream().filter(row->"APPOINTMENT".equals(row.kind())&&query.client().equals(row.client())).toList();
        return new CrmAppointmentPage(items,day.isBefore(query.dateTo())?day.plusDays(1).toString():null,snapshot.observedAt(),CrmReadResult.Completeness.UNKNOWN,project,query,
                new CrmJournalObservation(snapshot.epoch(),snapshot.sequence(),day,cached.hit(),snapshot.health()));
    }
    private DikidiDayCache.Snapshot loadJournal(CrmAccess owned,String project,LocalDate day,CrmJournalOptions options){
        var health=journals.health(owned.connectionId(),owned.credentialVersion());
        try{return execute(owned,project,(session,deadline)->{
            call(session,"GET","https://dikidi.ru/ru/owner/journal/?company="+project,null,false,project);
            long sequence=journals.begin(options.projectId(),options.epoch());Instant observed=Instant.now();
            String body=call(session,"POST","https://dikidi.ru/ru/owner/ajax/journal/api/?company="+project,List.of(
                    Map.entry("request[get][]","company.accesses"),Map.entry("request[get][]","masters.info"),Map.entry("request[get][]","masters.records"),
                    Map.entry("request[options][company][accesses][]",project),Map.entry("request[options][masters][records][0][date_from]",day.toString()),
                    Map.entry("request[options][masters][records][0][date_to]",day.toString())),true,project);
            var root=tree(body);validateJournal(root,project,day);
            return new DikidiDayCache.Snapshot(body,observed,sequence,options.epoch(),day,health);
        },Instant.now().plusSeconds(75));}catch(CrmFailure failure){throw failure.withSourceHealth(health);}
    }
    private void validateJournal(JsonNode result,String project,LocalDate day){
        if(!result.path("company").path("accesses").path(project).isContainer()||result.path("company").path("accesses").path(project).isEmpty())throw new CrmFailure(CONTEXT_MISMATCH);
        parseMasters(result);DikidiAppointments.parse(result,project,day);
    }
    private CrmAppointmentPage appointments(CrmAccess access,String project,CrmAppointmentQuery query,String cursor) {
        projectId(project);
        LocalDate day;
        try { day=cursor==null ? query.dateFrom() : LocalDate.parse(cursor); }
        catch (DateTimeException error) { throw new CrmFailure(INVALID_RESPONSE); }
        if (day.isBefore(query.dateFrom()) || day.isAfter(query.dateTo())) throw new CrmFailure(INVALID_RESPONSE);
        return execute(access,project,(session,deadline) -> {
            if(query.externalAppointmentId()!=null) {
                projectId(query.externalAppointmentId());catalog(session,project,deadline);
                var root=DikidiCommands.single((method,path,form)->tree(call(session,method,"https://dikidi.ru"+path,form,true,project)),project,query.externalAppointmentId());
                if(!root.path("company").path("accesses").path(project).isContainer() || root.path("company").path("accesses").path(project).isEmpty())throw new CrmFailure(CONTEXT_MISMATCH);
                var row=DikidiAppointments.single(root,project,query.externalAppointmentId());
                var items=row!=null && row.localStart().toLocalDate().equals(day) && (query.client()==null || "APPOINTMENT".equals(row.kind()) && query.client().equals(row.client())) ? List.of(row) : List.<CrmAppointment>of();
                return new CrmAppointmentPage(items,null,Instant.now(),CrmReadResult.Completeness.UNKNOWN,project,query);
            }
            call(session,"GET","https://dikidi.ru/ru/owner/journal/?company="+project,null,false,project);
            var form=new ArrayList<>(List.of(Map.entry("request[get][]","company.accesses"),Map.entry("request[get][]","masters.info"),
                    Map.entry("request[get][]","masters.records"),Map.entry("request[options][company][accesses][]",project),
                    Map.entry("request[options][masters][records][0][date_from]",day.toString()),
                    Map.entry("request[options][masters][records][0][date_to]",day.toString())));
            var result=tree(call(session,"POST","https://dikidi.ru/ru/owner/ajax/journal/api/?company="+project,form,true,project));
            if (!result.path("company").path("accesses").path(project).isContainer()
                    || result.path("company").path("accesses").path(project).isEmpty()) throw new CrmFailure(CONTEXT_MISMATCH);
            parseMasters(result);
            var items=DikidiAppointments.parse(result,project,day);
            if (query.client()!=null) items=items.stream().filter(item -> "APPOINTMENT".equals(item.kind()) && query.client().equals(item.client())).toList();
            return new CrmAppointmentPage(items,day.isBefore(query.dateTo()) ? day.plusDays(1).toString() : null,
                    Instant.now(),CrmReadResult.Completeness.UNKNOWN,project,query);
        });
    }

    @Override public CrmScheduleSnapshot readWorkIntervals(CrmAccess access, String externalProjectId, CrmWorkIntervalQuery query) {
        projectId(externalProjectId);
        if (query == null) throw new CrmFailure(INVALID_RESPONSE);
        return execute(access, externalProjectId, (session, deadline) -> {
            JsonNode result = catalog(session, externalProjectId, deadline, false, query);
            if (parseMasters(result).stream().noneMatch(master -> master.externalId().equals(query.externalMasterId())))
                throw new CrmFailure(CONTEXT_MISMATCH);
            return DikidiSchedules.parse(result, externalProjectId, query);
        });
    }

    @FunctionalInterface private interface Read<T> { T apply(DikidiClient.Session session, long deadline); }
    private <T> T execute(CrmAccess access, String project, Read<T> read) {
        return execute(access,project,read,null);
    }
    private <T> T execute(CrmAccess access, String project, Read<T> read,Instant until) {
        try (var credential = CrmBridges.credential(access)) {
            return client.execute(credential,until,session->read.apply(session,session.deadline()));
        } catch(DikidiFailure error){ throw CrmBridges.failure(error); }
    }
    @Override public void verifyConnection(CrmAccess access) {
        execute(access, null, (session, deadline) -> {
            // Read account navigation with existing cookies; never follow the logout link.
            // Login forms can also occur on authenticated pages, so positive evidence wins.
            var page = DikidiHtml.parse(call(session, "GET", "https://dikidi.ru/ru/", null, false, null));
            if (!page.authenticatedAccount())
                throw new DikidiFailure(page.hidden.containsKey("telegram_csrf") ? DikidiFailure.Code.AUTH_REQUIRED : DikidiFailure.Code.INVALID_RESPONSE);
            return null;
        });
    }
    @Override public CrmProjectInfo inspectProject(CrmAccess access, String externalProjectId) {
        projectId(externalProjectId);
        return execute(access, externalProjectId, (session, deadline) -> {
            catalog(session, externalProjectId, deadline);
            String name = projectName(session, externalProjectId, deadline);
            var online = tree(call(session, "GET", "https://dikidi.ru/mobile/ajax/newrecord/company_services?company="
                    + externalProjectId + "&array=1&lang=ru", null, true, externalProjectId));
            String currency = online.path("data").path("currency").path("iso").asString("");
            return new CrmProjectInfo(externalProjectId, name.isBlank() ? externalProjectId : name,
                    currency.matches("[A-Z]{3}") ? currency : null, null);
        });
    }
    @Override public List<CrmProjectInfo> listProjects(CrmAccess access) {
        return execute(access,null,(session,deadline) -> {
            String page=call(session,"GET","https://dikidi.ru/ru/business/projects",null,false,null);
            if (!DikidiProjectsHtml.businessPage(page))
                throw DikidiHtml.parse(page).hidden.containsKey("telegram_csrf")
                        ? new DikidiFailure(DikidiFailure.Code.AUTH_REQUIRED)
                        : new CrmFailure(INVALID_RESPONSE);
            var result=new ArrayList<CrmProjectInfo>(); var ids=new HashSet<String>();
            // Require a terminal page: never present a capped or repeated page as the full list.
            for (int portion=0;portion<100;portion++) {
                var response=tree(call(session,"POST","https://dikidi.ru/ru/business/ajax/projects/list/",
                        List.of(Map.entry("offset",Integer.toString(result.size())),Map.entry("limit","10"),Map.entry("more","1")),true,null));
                if (!".project-list".equals(response.path("append").asString("")) || !response.path("html").isString()) throw new CrmFailure(INVALID_RESPONSE);
                String html=response.path("html").asString();
                // Observed EOF after nonempty pages; an initially blank/unknown response is not an empty account.
                if (!result.isEmpty() && html.isBlank()) return List.copyOf(result);
                var rows=DikidiProjectsHtml.projects(html);
                if (rows.isEmpty()) return List.copyOf(result);
                if (rows.size()>10) throw new CrmFailure(INVALID_RESPONSE);
                for (var row:rows) {
                    if (!ids.add(row.externalId())) throw new CrmFailure(INVALID_RESPONSE);
                    result.add(row);
                }
            }
            throw new CrmFailure(INVALID_RESPONSE);
        });
    }
    @Override public CrmReadResult<CrmMaster> readMasters(CrmAccess access, String externalProjectId) {
        projectId(externalProjectId);
        return execute(access, externalProjectId, (session, deadline) -> {
            JsonNode result = catalog(session, externalProjectId, deadline);
            return new CrmReadResult<>(parseMasters(result), Instant.now(), CrmReadResult.Completeness.COMPLETE, externalProjectId + ":MASTERS");
        });
    }
    @Override public CrmClientPage readClients(CrmAccess access, String externalProjectId, String cursor) {
        projectId(externalProjectId);
        final int offset;
        try {
            offset = cursor == null ? 0 : Integer.parseInt(cursor);
            if (offset < 0 || offset > 1000000) throw new NumberFormatException();
        } catch (NumberFormatException error) { throw new CrmFailure(INVALID_RESPONSE); }
        return execute(access, externalProjectId, (session, deadline) -> {
            // Establish and verify the selected owner project on every portion.
            catalog(session, externalProjectId, deadline);
            var result = tree(call(session, "POST", "https://dikidi.ru/ru/owner/ajax/clients/filter/?company="+externalProjectId,
                    List.of(Map.entry("company",externalProjectId), Map.entry("limit","5"), Map.entry("offset",Integer.toString(offset)),
                            Map.entry("filter","username"), Map.entry("order","asc"), Map.entry("more","1"), Map.entry("query","")),
                    true, externalProjectId));
            String html = null;
            if (result.path("html").isString()) html=result.path("html").asString();
            if (result.path("script").isArray()) for (var script : result.path("script")) {
                if (script.path("html").isString()) {
                    // R08 updates both the row list and a separate count element. The count is not a page.
                    if (script.has("container")) {
                        if (!".company-clients .count-items".equals(script.path("container").asString("")) || script.has("append"))
                            throw new CrmFailure(INVALID_RESPONSE);
                        continue;
                    }
                    if (script.has("append") && !".company-clients tbody".equals(script.path("append").asString("")))
                        throw new CrmFailure(INVALID_RESPONSE);
                    if (html != null) throw new CrmFailure(INVALID_RESPONSE);
                    html=script.path("html").asString();
                }
            }
            if (html == null) throw new CrmFailure(INVALID_RESPONSE);
            var clients = DikidiClientsHtml.parse(html);
            if (clients.size()>5 || clients.isEmpty() && !DikidiClientsHtml.emptyPage(html)) throw new CrmFailure(INVALID_RESPONSE);
            // Directory reads must not refresh statistics in alphabetical order.
            var directory=clients.stream().map(client -> {
                var fields=new HashSet<>(client.presentFields());
                fields.removeAll(Set.of("visitCount","totalSpent"));
                return new CrmClient(client.externalId(),client.identityKind(),client.displayName(),client.contacts(),client.profile(),fields,client.avatarUrl());
            }).toList();
            // Offset paging has no snapshot token. Even a terminal page cannot prove a complete snapshot.
            return new CrmClientPage(directory, clients.isEmpty() ? null : Integer.toString(offset+clients.size()), Instant.now(),
                    CrmReadResult.Completeness.UNKNOWN, externalProjectId+":CLIENTS");
        });
    }
    @Override public CrmClientPage readClientStatistics(CrmAccess access, String externalProjectId, List<String> ids) {
        projectId(externalProjectId);
        if (ids.isEmpty() || ids.size()>5 || new HashSet<>(ids).size()!=ids.size()
                || ids.stream().anyMatch(id -> id==null || !id.matches("[0-9]{1,128}"))) throw new CrmFailure(INVALID_RESPONSE);
        return execute(access,externalProjectId,(session,deadline) -> {
            catalog(session,externalProjectId,deadline);
            var enriched=new ArrayList<CrmClient>();
            for (String id:ids) {
                var response=tree(call(session,"POST","https://dikidi.ru/ru/owner/ajax/clients/settings/"+id
                        +"/?company="+externalProjectId+"&tab=statistics",List.of(Map.entry("tabs","true")),true,externalProjectId));
                if (!response.path("html").isString()
                        || !".modal.settings #tab-settings-statistics".equals(response.path("container").asString("")))
                    throw new CrmFailure(INVALID_RESPONSE);
                var stats=DikidiClientStatisticsHtml.parse(response.path("html").asString());
                enriched.add(new CrmClient(id,"PROJECT",null,Map.of(),Map.of(),
                        Set.of("visitCount","totalSpent","averageCheck","statisticsCurrency"),null,
                        stats.visits(),stats.totalSpent(),stats.averageCheck(),stats.currency()));
            }
            return new CrmClientPage(enriched,null,Instant.now(),CrmReadResult.Completeness.UNKNOWN,externalProjectId+":CLIENTS");
        });
    }
    @Override public CrmCatalogSnapshot readCatalog(CrmAccess access, String externalProjectId) {
        projectId(externalProjectId);
        return execute(access, externalProjectId, (session, deadline) -> {
            JsonNode result = catalog(session, externalProjectId, deadline, true);
            List<CrmMaster> masters = parseMasters(result);
            Set<String> masterIds = new HashSet<>(); masters.forEach(master -> masterIds.add(master.externalId()));
            JsonNode section = result.path("masters").path("services"), ids = section.path("ids"), data = section.path("data");
            if (!ids.isArray() || !(data.isObject() || data.isArray() && data.isEmpty()) || ids.size() != data.size())
                throw new CrmFailure(INVALID_RESPONSE);
            Set<String> expected = new HashSet<>();
            for (var id : ids) if (!expected.add(identifier(id))) throw new CrmFailure(INVALID_RESPONSE);
            Map<String, CrmCatalogService> services = new TreeMap<>();
            List<CrmServiceOffer> offers = new ArrayList<>();
            List<String> outerIds = new ArrayList<>(); data.properties().forEach(item -> outerIds.add(item.getKey()));
            Collections.sort(outerIds);
            for (String masterId : outerIds) {
                if (!masterIds.contains(masterId) || !expected.remove(masterId)) throw new CrmFailure(CONTEXT_MISMATCH);
                JsonNode rows = data.path(masterId);
                if (!(rows.isObject() || rows.isArray() && rows.isEmpty())) throw new CrmFailure(INVALID_RESPONSE);
                for (var property : rows.properties()) {
                    JsonNode row = property.getValue();
                    String serviceId = identifier(row.path("id"));
                    if (!serviceId.equals(property.getKey()) || !masterId.equals(identifier(row.path("master_id"))))
                        throw new CrmFailure(CONTEXT_MISMATCH);
                    String title = catalogText(row.path("title"));
                    if (title == null || title.isBlank()) throw new CrmFailure(INVALID_RESPONSE);
                    services.putIfAbsent(serviceId, new CrmCatalogService(serviceId, title, null, Map.of()));
                    Map<String, Object> state = new LinkedHashMap<>(), attributes = new LinkedHashMap<>();
                    attributes.put("title", title);
                    catalogFlag(row, "active", state); catalogFlag(row, "online", state);
                    catalogFlag(row, "floating", attributes);
                    String description = catalogText(row.path("description"));
                    if (description != null) attributes.put("description", description);
                    Long breakSeconds = catalogSeconds(row.path("break_duration"));
                    if (breakSeconds != null) attributes.put("breakDurationSeconds", breakSeconds);
                    Set<String> presentFields = new HashSet<>();
                    if (row.has("cost")) presentFields.add("price");
                    if (row.has("time")) presentFields.add("durationSeconds");
                    offers.add(new CrmServiceOffer(masterId, serviceId, catalogMoney(row.path("cost")), null,
                            catalogSeconds(row.path("time")), state, attributes, presentFields));
                    if (offers.size() > 20000) throw new CrmFailure(INVALID_RESPONSE);
                }
            }
            if (!expected.isEmpty()) throw new CrmFailure(INVALID_RESPONSE);
            Instant observedAt = Instant.now();
            String name = null;
            CrmFailure.Code projectError = null;
            try {
                name = projectName(session, externalProjectId, deadline);
                if (name.isBlank()) throw new CrmFailure(INVALID_RESPONSE);
            } catch (DikidiFailure technical) {
                // 13.x-семантика: потеря входа на вспомогательном чтении не отменяет
                // верифицированный снимок — деградация + отметка, восстановление на следующем чтении.
                CrmFailure error = CrmBridges.failure(technical);
                if (error.code() == AUTH_REQUIRED) session.invalidateAuthentication();
                name = null; projectError = error.code();
            } catch (CrmFailure error) {
                // The verified catalog remains usable when the separate profile read fails.
                if (error.code() == CONTEXT_MISMATCH) throw error;
                if (error.code() == AUTH_REQUIRED) session.invalidateAuthentication();
                name = null; projectError = error.code();
            }
            return new CrmCatalogSnapshot(masters, new ArrayList<>(services.values()), offers,
                    observedAt, CrmReadResult.Completeness.COMPLETE, externalProjectId + ":CATALOG", name, projectError,
                    parseStaff(result, externalProjectId), readStaffAccesses(session, externalProjectId));
        });
    }
    /**
     * Записи доступа проекта (13.6): страница /ru/owner/permissions/ + ajax-форма
     * edit/{id} на каждую строку (живые наблюдения 16.11.2026). Ограниченная учётка
     * интеграции может не видеть страницу — это деградация (null: записи не наблюдаются
     * и не стареют), а не отказ снимка; потеря входа инвалидирует сессию как везде.
     */
    private List<CrmStaffAccess> readStaffAccesses(DikidiClient.Session session, String project) {
        try {
            String page = call(session, "GET", "https://dikidi.ru/ru/owner/permissions/?company=" + project,
                    null, false, project);
            List<CrmStaffAccess> accesses = new ArrayList<>();
            for (String accessId : DikidiPermissions.pageAccessIds(page)) {
                JsonNode answer = tree(call(session, "GET",
                        "https://dikidi.ru/ru/owner/ajax/permissions/edit/" + accessId + "/?company=" + project,
                        null, true, project));
                if (!"item-access".equals(answer.path("dialog").asString(""))) throw new CrmFailure(CONTEXT_MISMATCH);
                JsonNode record = answer.path("access");
                if (!record.isObject() || !identifier(record.path("id")).equals(accessId)
                        || !record.path("is_main_owner").isBoolean()) throw new CrmFailure(CONTEXT_MISMATCH);
                var form = DikidiPermissions.editForm(answer.path("html").asString(""));
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("roleValue", form.roleValue()); attributes.put("roleLabel", form.roleLabel());
                accesses.add(new CrmStaffAccess(accessId, form.name().isBlank() ? accessId : form.name(),
                        form.masterExternalId(), form.phone(), record.path("is_main_owner").asBoolean(), attributes));
            }
            return accesses;
        } catch (DikidiFailure technical) {
            if (CrmBridges.failure(technical).code() == AUTH_REQUIRED) session.invalidateAuthentication();
            return null;
        } catch (CrmFailure failure) {
            if (failure.code() == AUTH_REQUIRED) session.invalidateAuthentication();
            return null;
        } catch (RuntimeException unexpected) {
            // Слой доступа — надстройка: любой неожиданный отказ его чтения
            // деградирует снимок, но не отменяет проверенный каталог.
            return null;
        }
    }
    private String projectName(DikidiClient.Session session, String project, long deadline) {
        var profile = tree(call(session, "GET", "https://dikidi.ru/ru/owner/ajax/profile/index/?company=" + project,
                null, true, project));
        String name = DikidiHtml.parse(profile.path("html").asString("")).heading();
        if (name.length() > 65536) throw new CrmFailure(INVALID_RESPONSE);
        return name;
    }
    private static String catalogText(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString() || value.asString().length() > 65536) throw new CrmFailure(INVALID_RESPONSE);
        return value.asString();
    }
    private static void catalogFlag(JsonNode row, String key, Map<String, Object> target) {
        JsonNode value = row.path(key);
        if (value.isMissingNode() || value.isNull() || value.isString() && value.asString().isBlank()) return;
        String text = value.asString();
        if (Set.of("1", "true").contains(text)) target.put(key, true);
        else if (Set.of("0", "false").contains(text)) target.put(key, false);
        else throw new CrmFailure(INVALID_RESPONSE);
    }
    private static BigDecimal catalogDecimal(JsonNode value) {
        if (value.isMissingNode() || value.isNull() || value.isString() && value.asString().isBlank()) return null;
        if (!(value.isNumber() || value.isString())) throw new CrmFailure(INVALID_RESPONSE);
        try {
            String text = value.asString().trim();
            if (text.length() > 64) throw new NumberFormatException();
            BigDecimal result = new BigDecimal(text);
            if (result.signum() < 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException error) { throw new CrmFailure(INVALID_RESPONSE); }
    }
    private static BigDecimal catalogMoney(JsonNode value) {
        BigDecimal amount = catalogDecimal(value);
        if (amount == null) return null;
        try {
            BigDecimal normalized = amount.stripTrailingZeros();
            if (normalized.scale() > 4 || normalized.precision() - (long) normalized.scale() > 15) throw new ArithmeticException();
            return normalized.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) { throw new CrmFailure(INVALID_RESPONSE); }
    }
    private static Long catalogSeconds(JsonNode value) {
        BigDecimal minutes = catalogDecimal(value);
        if (minutes == null) return null;
        try {
            if (minutes.stripTrailingZeros().scale() > 2) throw new ArithmeticException();
            if (minutes.compareTo(BigDecimal.valueOf(Long.MAX_VALUE).divide(BigDecimal.valueOf(60), 16, RoundingMode.DOWN)) > 0)
                throw new ArithmeticException();
            return minutes.multiply(BigDecimal.valueOf(60)).longValueExact();
        }
        catch (ArithmeticException error) { throw new CrmFailure(INVALID_RESPONSE); }
    }
    /**
     * Держатели доступа проекта (company.accesses, живое наблюдение 16.11.2026): объект,
     * ключённый user id платформы («<user id>»: {id, roles_id, username}) — тот же ключ,
     * под которым автор виден в чате, поэтому импорт доступов заводит строки crm.employee
     * без отдельного сведения по имени. Ключ-актор «actor» (служебная запись профиля
     * доступа) не является сотрудником; любой иной нечисловой ключ — новый формат
     * платформы, он отвергается явно, а не молча теряется.
     */
    private List<CrmStaff> parseStaff(JsonNode result, String project) {
        JsonNode accesses = result.path("company").path("accesses").path(project);
        List<CrmStaff> staff = new ArrayList<>();
        for (var entry : accesses.properties()) {
            if (entry.getKey().equals("actor")) continue;
            String id = entry.getKey();
            if (!id.matches("[0-9]{1,20}")) throw new CrmFailure(INVALID_RESPONSE);
            JsonNode value = entry.getValue();
            if (!value.isObject() || !identifier(value.path("id")).equals(id)) throw new CrmFailure(CONTEXT_MISMATCH);
            String username = value.path("username").asString("");
            Map<String, String> attributes = new LinkedHashMap<>();
            JsonNode role = value.path("roles_id");
            if (!role.isMissingNode() && !role.isNull()) attributes.put("rolesId", role.asString());
            staff.add(new CrmStaff(id, username.isBlank() ? id : username, attributes));
            if (staff.size() > 5000) throw new CrmFailure(INVALID_RESPONSE);
        }
        return staff;
    }
    private List<CrmMaster> parseMasters(JsonNode result) {
            JsonNode info = result.path("masters").path("info"), data = info.path("data"), ids = info.path("ids");
            if (!ids.isArray() || !(data.isObject() || data.isArray() && data.isEmpty()) || ids.size() != data.size() || data.size() > 5000)
                throw new CrmFailure(INVALID_RESPONSE);
            Set<String> expected = new HashSet<>();
            for (var id : ids) if (!expected.add(identifier(id))) throw new CrmFailure(INVALID_RESPONSE);
            List<CrmMaster> masters = new ArrayList<>();
            for (var property : data.properties()) {
                JsonNode value = property.getValue();
                String id = identifier(value.path("id"));
                if (!property.getKey().equals(id) || !expected.remove(id)) throw new CrmFailure(CONTEXT_MISMATCH);
                if (!"master".equals(value.path("item_type").asString("")) || !"1".equals(value.path("is_master").asString("")))
                    throw new CrmFailure(INVALID_RESPONSE);
                String title = value.path("title").asString("");
                if (title.isBlank()) title = (value.path("first_name").asString("") + " " + value.path("last_name").asString("")).trim();
                if (title.isBlank() || title.length() > 65536) throw new CrmFailure(INVALID_RESPONSE);
                masters.add(new CrmMaster(id, title, Map.of("itemType", "master")));
            }
            if (!expected.isEmpty()) throw new CrmFailure(INVALID_RESPONSE);
            return masters;
    }
    private JsonNode catalog(DikidiClient.Session session, String project, long deadline) {
        return catalog(session, project, deadline, false);
    }
    private JsonNode catalog(DikidiClient.Session session, String project, long deadline, boolean includeServices) {
        return catalog(session, project, deadline, includeServices, null);
    }
    private JsonNode catalog(DikidiClient.Session session, String project, long deadline, boolean includeServices, CrmWorkIntervalQuery schedule) {
        // The journal GET initializes project cookies before interpreting ACCESS_DENIED.
        call(session, "GET", "https://dikidi.ru/ru/owner/journal/?company=" + project, null, false, project);
        var form = new ArrayList<>(List.of(Map.entry("request[get][]", "company.accesses"),
                Map.entry("request[get][]", "masters.info"), Map.entry("request[options][company][accesses][]", project)));
        if (includeServices) form.add(Map.entry("request[get][]", "masters.services"));
        if (schedule != null) {
            form.add(Map.entry("request[get][]", "masters.schedules"));
            form.add(Map.entry("request[options][masters][schedules][0][date_from]", schedule.dateFrom().toString()));
            form.add(Map.entry("request[options][masters][schedules][0][date_to]", schedule.dateTo().toString()));
        }
        JsonNode result = tree(call(session, "POST", "https://dikidi.ru/ru/owner/ajax/journal/api/?company=" + project,
                form, true, project));
        JsonNode projectAccess = result.path("company").path("accesses").path(project);
        if (!projectAccess.isContainer() || projectAccess.isEmpty()
                || !result.path("masters").path("info").isObject()) throw new CrmFailure(CONTEXT_MISMATCH);
        return result;
    }
    // 13.4: вход (telegram_csrf → check/auth → user/auth) живёт в DikidiClient;
    // адаптер видит только готовую сессию с гарантией авторизации.
    // Ревю 13.4 (бэклог 13.5): мёртвый параметр deadline убран — дедлайн исполняет
    // сам клиент (session.call проверяет его на каждой отправке).
    private String call(DikidiClient.Session session, String method, String address, List<Map.Entry<String, String>> form,
                        boolean ajax, String project) {
        var state = effect.get();
        DikidiTransport.Observer observer = state == null ? null : new DikidiTransport.Observer() {
            @Override public void dispatched() { if (state.changing) state.started = true; }
        };
        // 13.4: сбой транспорта остаётся техническим до границы execute — там единый маппинг.
        return session.call(method, address, form, ajax, project, observer);
    }
    private JsonNode tree(String body) {
        try {
            JsonNode result = json.readTree(body), error = result.path("error");
            if (result.isNull() || !result.isContainer()) throw new CrmFailure(INVALID_RESPONSE);
            JsonNode code = error.isObject() ? error.path("code") : error;
            if (!code.isMissingNode() && !code.isNull() && !"0".equals(code.asString(""))) {
                String message = result.path("message").asString("") + error.path("message").asString("");
                // Авторизационные коды ответа — техническая семантика сессии: восстановление
                // входа живёт в DikidiClient, поэтому они идут техническим сбоем (13.4).
                if (message.toLowerCase(Locale.ROOT).matches("(?s).*(auth|авторизац|парол|вход).*")) throw new DikidiFailure(DikidiFailure.Code.AUTH_REQUIRED);
                throw new DikidiFailure(DikidiFailure.Code.ACCESS_DENIED);
            }
            return result;
        } catch (DikidiFailure | CrmFailure error) { throw error; }
        catch (RuntimeException error) {
            if (body.contains("name=\"password\"") || body.contains("name='password'")) throw new DikidiFailure(DikidiFailure.Code.AUTH_REQUIRED);
            throw new CrmFailure(INVALID_RESPONSE);
        }
    }
    private static void projectId(String id) { if (id == null || !id.matches("[0-9]{1,20}")) throw new CrmFailure(CONTEXT_MISMATCH); }
    private static String identifier(JsonNode node) {
        if (!node.isString() && !node.isIntegralNumber()) throw new CrmFailure(INVALID_RESPONSE);
        String value = node.asString("");
        if (value.isBlank() || value.length() > 128) throw new CrmFailure(INVALID_RESPONSE);
        return value;
    }
    @Override public void invalidate(UUID connectionId) {
        if(days!=null)days.invalidate(connectionId);
        client.invalidate(connectionId);
    }
    @Override public void close() { if(days!=null)days.close(); if(ownsClient)client.close(); }
}
