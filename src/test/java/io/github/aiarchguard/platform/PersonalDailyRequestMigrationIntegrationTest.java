package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static io.github.aiarchguard.platform.PersonalDailyAuthorizationMigrationIntegrationTest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.agent.persistence.JdbcAgentStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.*;
import javax.sql.DataSource;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** V18 缺席时先明确失败；后续仅在独立测试库验证实际约束、历史及两连接竞争。 */
class PersonalDailyRequestMigrationIntegrationTest extends PostgresIntegrationTestSupport {
    private final PersonalDailyAuthorizationMigrationIntegrationTest v17 = new PersonalDailyAuthorizationMigrationIntegrationTest();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String PRICE = "deepseek-flash-context-peak-usd-2026-10-15";
    private record Facts(UUID project, UUID risk, UUID grant, UUID request) {}
    private String database18() throws Exception {
        String url = v17.database17(); migrate18(url, 1); return url;
    }
    private void migrate18(String url, int count) {
        var flyway = v17.agent(url).target("18").load();
        var result = assertDoesNotThrow(flyway::migrate, "目标 V18 必须实际存在并迁移成功");
        assertThat(result.migrationsExecuted).isEqualTo(count);
        assertThat(flyway.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("18"));
        assertThat(flyway.migrate().migrationsExecuted).isZero(); flyway.validate();
    }
    @Test void emptyDatabaseAppliesExactlyTenAgentMigrationsAndValidatesRepeatedly() throws Exception {
        String url = v17.emptyDatabase(); v17.main(url).migrate(); migrate18(url, 10);
        try (var c = v17.connect(url)) {
            for (String t : List.of("daily_request_links", "daily_attempts", "daily_outcomes", "daily_grant_stops"))
                assertThat(v17.number(c, "SELECT count(*) FROM agent." + t)).isZero();
        }
    }
    @Test void upgrade17PreservesOldChecksumsFunctionsFactsAndCreatesNoDailyHistory() throws Exception {
        String url = v17.database17(); Map<String,String> legacy, funcs; List<String> checksums;
        String grants, risks;
        try (var c = v17.connect(url)) {
            v17.legacy(c); v17.fixture(c); legacy = v17.legacyHistory(c); funcs = functions(c); checksums = checksums17(c);
            grants = v17.snapshot(c, "daily_grants"); risks = v17.snapshot(c, "daily_risk_acknowledgements");
        }
        migrate18(url, 1);
        try (var c = v17.connect(url)) {
            assertThat(v17.legacyHistory(c)).isEqualTo(legacy); assertThat(checksums17(c)).isEqualTo(checksums);
            assertThat(functions(c)).containsAllEntriesOf(funcs);
            assertThat(v17.snapshot(c, "daily_grants")).isEqualTo(grants); assertThat(v17.snapshot(c, "daily_risk_acknowledgements")).isEqualTo(risks);
            assertThat(v17.number(c, "SELECT count(*) FROM agent.live_template_request_slots")).isOne();
            for (String t : List.of("daily_request_links", "daily_attempts", "daily_outcomes", "daily_grant_stops"))
                assertThat(v17.number(c, "SELECT count(*) FROM agent." + t)).isZero();
        }
    }
    private List<String> checksums17(Connection c) throws SQLException {
        var values = new ArrayList<String>();
        for (String table : List.of("flyway_schema_history", "flyway_agent_schema_history"))
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT version,checksum FROM " + table + " WHERE type='SQL' AND version::int<=17 ORDER BY installed_rank")) {
                while (r.next()) values.add(table + ":" + r.getString(1) + ":" + r.getString(2));
            }
        return values;
    }
    private Map<String,String> functions(Connection c) throws SQLException {
        var values = new LinkedHashMap<String,String>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT p.oid::regprocedure::text,pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON p.pronamespace=n.oid WHERE n.nspname='agent' ORDER BY 1")) {
            while (r.next()) values.put(r.getString(1), r.getString(2));
        } return values;
    }
    private Facts facts(Connection c) throws Exception {
        var grant = v17.fixture(c); UUID request = request(c, grant.project(), OWNER, false);
        insert(c, "daily_project_settings", row("project_id",grant.project(),"enabled",true,"revision",1,"grant_id",grant.grant()));
        v17.execute(c, v17.usageSql(grant, "0,0,0"));
        return new Facts(grant.project(),grant.risk(),grant.grant(),request);
    }
    private UUID request(Connection c, UUID project, UUID actor, boolean ordinary) throws Exception {
        UUID id = UUID.randomUUID(); String prompt = ordinary ? "pr-summary-0.1.0" : "pr-summary-deepseek-0.1.0";
        var bindings = new AgentRequestView.VersionBindings(UUID.randomUUID(), HASH, UUID.randomUUID(), List.of(UUID.randomUUID()), List.of(),
            prompt, ordinary ? "disabled-or-test-fake-0.1.0" : LiveCostPolicy.CONTEXT_MODEL_PROFILE,
            "responses-v1-restricted", ordinary ? "deterministic-fake-or-disabled" : "deepseek-flash", "0.1.0", ordinary ? "synthetic-0.1.0" : PRICE, HASH);
        var input = new AgentModelPort.ModelInput("PR_SUMMARY","0.1.0",prompt,null,null,null,null,null,null,List.of(),List.of(),List.of());
        var view = new AgentRequestView(id,project,actor,"c".repeat(32),"PR_SUMMARY","QUEUED",bindings,null,null,null,Instant.parse(AT),Instant.parse(AT));
        var client = JdbcClient.create(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c,true));
        assertThat(new JdbcAgentStore(client,mapper).insert(new AgentSnapshot(view,input,List.of()),id.toString())).isTrue(); return id;
    }
    private LinkedHashMap<String,Object> row(Object... cells) {
        var value = new LinkedHashMap<String,Object>(); for (int i=0;i<cells.length;i+=2) value.put((String)cells[i],cells[i+1]); return value;
    }
    private Map<String,Object> link(Facts f) { return row("request_id",f.request(),"project_id",f.project(),"grant_id",f.grant(),
        "risk_acknowledgement_id",f.risk(),"credential_version",CREDENTIAL,"settings_revision",1,"runtime_binding_digest",HASH,
        "payload_sha256",HASH,"candidates_sha256",OTHER,"created_at",AT); }
    private Map<String,Object> attempt(Facts f) { return row("request_id",f.request(),"project_id",f.project(),"grant_id",f.grant(),
        "attempt_id",UUID.randomUUID(),"deployment_id",DEPLOYMENT,"credential_version",CREDENTIAL,"settings_revision",1,
        "runtime_binding_digest",HASH,"utc_day","2026-10-30","price_version",PRICE,"reserved_microusd",316373,"created_at",AT); }
    private Map<String,Object> stop(Facts f) { return row("grant_id",f.grant(),"project_id",f.project(),"request_id",f.request(),"code","MODEL_TIMEOUT","created_at",AT); }
    private String sql(String table, Map<String,Object> values) {
        return "INSERT INTO agent."+table+"("+String.join(",",values.keySet())+") VALUES ("+
            values.values().stream().map(v -> v==null ? "NULL" : v instanceof Number || v instanceof Boolean ? v.toString() : "'"+v.toString().replace("'","''")+"'")
                .collect(java.util.stream.Collectors.joining(","))+")";
    }
    private void insert(Connection c,String table,Map<String,Object> values) throws SQLException { v17.execute(c,sql(table,values)); }
    private void rejectChange(Connection c,String table,Map<String,Object> valid,String key,Object wrong,String state) throws SQLException {
        var changed = new LinkedHashMap<>(valid); changed.put(key,wrong); String before=v17.snapshot(c,table);
        v17.rejects(c,sql(table,changed),state); assertThat(v17.snapshot(c,table)).isEqualTo(before);
    }
    @Test void linksRejectEachMismatchedIdentityBindingAndTimeWithoutPartialRows() throws Exception {
        try(var c=v17.connect(database18())) {
            var f=facts(c); var other=facts(c); var valid=link(f);
            for(String key:List.of("project_id","request_id","grant_id","risk_acknowledgement_id")) {
                Object wrong=switch(key){case "project_id"->other.project();case "request_id"->other.request();case "grant_id"->other.grant();default->other.risk();};
                rejectChange(c,"daily_request_links",valid,key,wrong,"23503");
            }
            UUID sameProjectRisk=UUID.randomUUID(),sameProjectGrant=UUID.randomUUID();
            v17.execute(c,v17.riskSql(f.project(),sameProjectRisk,v17.envelope()).replace("'risk-key'","'alternate-risk'"));
            var alternate=new PersonalDailyAuthorizationMigrationIntegrationTest.Fixture(f.project(),sameProjectRisk,sameProjectGrant);
            v17.execute(c,v17.grantSql(alternate,sameProjectGrant,"ARRAY['PR_SUMMARY']",2,700000));
            rejectChange(c,"daily_request_links",valid,"risk_acknowledgement_id",sameProjectRisk,"23514");
            rejectChange(c,"daily_request_links",valid,"grant_id",sameProjectGrant,"23514");
            rejectChange(c,"daily_request_links",valid,"credential_version",UUID.randomUUID(),"23514");
            rejectChange(c,"daily_request_links",valid,"runtime_binding_digest",OTHER,"23514");
            rejectChange(c,"daily_request_links",valid,"settings_revision",-1,"23514");
            rejectChange(c,"daily_request_links",valid,"settings_revision",2,"23514");
            rejectChange(c,"daily_request_links",valid,"created_at","2026-10-30T02:00:01Z","23514");
            rejectChange(c,"daily_request_links",valid,"payload_sha256","bad","23514");
            rejectChange(c,"daily_request_links",valid,"candidates_sha256","bad","23514");
            UUID foreignOwner=request(c,f.project(),UUID.randomUUID(),false);
            rejectChange(c,"daily_request_links",valid,"request_id",foreignOwner,"23514");
            insert(c,"daily_request_links",valid); assertThat(v17.number(c,"SELECT count(*) FROM agent.daily_request_links")).isOne();
        }
    }
    @Test void attemptsBindExactLinkAndFixedDeploymentCredentialSettingsPriceAndReservation() throws Exception {
        try(var c=v17.connect(database18())) {
            var f=facts(c); var other=facts(c); insert(c,"daily_request_links",link(f)); insert(c,"daily_request_links",link(other)); var valid=attempt(f);
            rejectChange(c,"daily_attempts",valid,"project_id",other.project(),"23503");
            rejectChange(c,"daily_attempts",valid,"grant_id",other.grant(),"23503");
            rejectChange(c,"daily_attempts",valid,"request_id",other.request(),"23503");
            for(String key:List.of("deployment_id","credential_version")) rejectChange(c,"daily_attempts",valid,key,UUID.randomUUID(),"23514");
            rejectChange(c,"daily_attempts",valid,"runtime_binding_digest",OTHER,"23514");
            rejectChange(c,"daily_attempts",valid,"settings_revision",2,"23514");
            rejectChange(c,"daily_attempts",valid,"price_version","synthetic-price","23514");
            for(long amount:List.of(-1L,0L,316372L,316374L)) rejectChange(c,"daily_attempts",valid,"reserved_microusd",amount,"23514");
            insert(c,"daily_attempts",valid); assertThat(v17.number(c,"SELECT count(*) FROM agent.daily_attempts")).isOne();
        }
    }
    @ParameterizedTest @ValueSource(strings={"ZERO","CAP","UNKNOWN"})
    void outcomesAcceptOnlyCoherentActualAmountsAndUnknownNulls(String mode) throws Exception {
        try(var c=v17.connect(database18())) {
            var f=facts(c); insert(c,"daily_request_links",link(f)); insert(c,"daily_attempts",attempt(f));
            var value=row("request_id",f.request(),"state",mode.equals("UNKNOWN")?"UNKNOWN":"SETTLED","usage",mode.equals("UNKNOWN")?null:"{}",
                "actual_microusd",mode.equals("UNKNOWN")?null:mode.equals("CAP")?316373:0,"created_at",AT);
            insert(c,"daily_outcomes",value); assertThat(v17.number(c,"SELECT count(*) FROM agent.daily_outcomes")).isOne();
        }
    }
    @Test void outcomesRejectMissingAttemptNegativeOverCapAndInvalidUsageOrUnknownAmounts() throws Exception {
        try(var c=v17.connect(database18())) {
            var f=facts(c); insert(c,"daily_request_links",link(f)); insert(c,"daily_attempts",attempt(f));
            var valid=row("request_id",f.request(),"state","SETTLED","usage","{}","actual_microusd",1,"created_at",AT);
            rejectChange(c,"daily_outcomes",valid,"request_id",UUID.randomUUID(),"23503");
            for(long cost:List.of(-1L,316374L,Long.MAX_VALUE)) rejectChange(c,"daily_outcomes",valid,"actual_microusd",cost,"23514");
            for(Object usage:Arrays.asList(null,"[]","1","\"text\"")) rejectChange(c,"daily_outcomes",valid,"usage",usage,"23514");
            rejectChange(c,"daily_outcomes",valid,"actual_microusd",null,"23514");
            var unknown=new LinkedHashMap<>(valid);unknown.put("state","UNKNOWN");unknown.put("usage",null);unknown.put("actual_microusd",null);
            rejectChange(c,"daily_outcomes",unknown,"usage","{}","23514");rejectChange(c,"daily_outcomes",unknown,"actual_microusd",0,"23514");
        }
    }
    @Test void stopsAndActivePointersRequireExactProjectGrantRequestAndPreserveHistory() throws Exception {
        try(var c=v17.connect(database18())) {
            var f=facts(c);var other=facts(c);insert(c,"daily_request_links",link(f));insert(c,"daily_request_links",link(other));
            var valid=stop(f);rejectChange(c,"daily_grant_stops",valid,"project_id",other.project(),"23503");
            rejectChange(c,"daily_grant_stops",valid,"grant_id",other.grant(),"23503");rejectChange(c,"daily_grant_stops",valid,"request_id",other.request(),"23503");
            rejectChange(c,"daily_grant_stops",valid,"code","UNRECOGNIZED","23514");
            String before=v17.snapshot(c,"daily_grant_usage");
            v17.rejects(c,"UPDATE agent.daily_grant_usage SET active_request_id='"+other.request()+"' WHERE grant_id='"+f.grant()+"'","23503");
            assertThat(v17.snapshot(c,"daily_grant_usage")).isEqualTo(before);
            v17.execute(c,"UPDATE agent.daily_grant_usage SET active_request_id='"+f.request()+"' WHERE grant_id='"+f.grant()+"'");
            insert(c,"daily_attempts",attempt(f));insert(c,"daily_outcomes",row("request_id",f.request(),"state","UNKNOWN","usage",null,"actual_microusd",null,"created_at",AT));insert(c,"daily_grant_stops",valid);
            for(String t:List.of("daily_request_links","daily_attempts","daily_outcomes","daily_grant_stops")) {
                String snapshot=v17.snapshot(c,t);v17.rejects(c,"UPDATE agent."+t+" SET created_at=created_at","23514");
                v17.rejects(c,"DELETE FROM agent."+t,"23514");assertThat(v17.snapshot(c,t)).isEqualTo(snapshot);
            }
        }
    }

    private Map<String,Object> live(Connection c,Facts f) throws Exception {
        UUID approval=UUID.randomUUID(),batch=UUID.randomUUID(),template=request(c,f.project(),OWNER,true);
        insert(c,"personal_enablements",row("id",approval,"project_id",f.project(),"deployment_id",DEPLOYMENT,"approved_by",OWNER,
            "approved_at",AT,"expires_at",EXPIRY,"credential_version",CREDENTIAL,"acknowledgement","{}"));
        String manifest="{\"schemaVersion\":\"0.1.0\",\"projectId\":\""+f.project()+"\",\"templates\":[{\"templateRequestId\":\""+template+
            "\",\"selection\":null,\"payloadSha256\":\""+HASH+"\",\"candidatesSha256\":\""+OTHER+"\"}]}";
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        insert(c,"live_batches",row("id",batch,"project_id",f.project(),"enablement_id",approval,"deployment_id",DEPLOYMENT,
            "inventory_id",UUID.randomUUID(),"approved_by",OWNER,"approved_at",AT,"expires_at",EXPIRY,"manifest_sha256",digest,
            "manifest",manifest,"max_requests",2,"max_cost_microusd",700000,"cost_policy_version",PRICE));
        return row("request_id",f.request(),"project_id",f.project(),"batch_id",batch,"template_id",template,"enablement_id",approval,
            "credential_version",CREDENTIAL,"settings_revision",1,"created_at",AT);
    }
    @ParameterizedTest @ValueSource(strings={"DAILY_FIRST","LIVE_FIRST"})
    void twoPhysicalConnectionsSerializeOppositeAssociationsOnTheSameRequest(String direction) throws Exception {
        String url=database18();Facts f;Map<String,Object> live;
        try(var setup=v17.connect(url)){f=facts(setup);live=live(setup,f);}
        boolean dailyFirst=direction.equals("DAILY_FIRST");String winnerTable=dailyFirst?"daily_request_links":"live_request_links";
        String loserTable=dailyFirst?"live_request_links":"daily_request_links";
        var pool=Executors.newSingleThreadExecutor();Future<String> loser=null;
        try(var first=v17.connect(url);var second=v17.connect(url);var observer=v17.connect(url)) {
            first.setAutoCommit(false);second.setAutoCommit(false);
            v17.execute(first,"SET LOCAL lock_timeout='8s'");v17.execute(second,"SET LOCAL lock_timeout='8s'");
            int firstPid=(int)v17.number(first,"SELECT pg_backend_pid()");int secondPid=(int)v17.number(second,"SELECT pg_backend_pid()");
            assertThat(firstPid).isNotEqualTo(secondPid);
            insert(first,winnerTable,dailyFirst?link(f):live);
            loser=pool.submit(()->{
                try{insert(second,loserTable,dailyFirst?live:link(f));second.commit();return "ACCEPTED";}
                catch(SQLException rejected){second.rollback();return rejected.getSQLState();}
            });
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
            while(System.nanoTime()<deadline) {
                assertThat(loser.isDone()).as("失败方向必须先在同一 requests 行上等待，不能并发双成功").isFalse();
                if(v17.number(observer,"SELECT count(*) FROM pg_stat_activity WHERE pid="+secondPid+" AND "+firstPid+"=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock'")==1){blocked=true;break;}
                Thread.onSpinWait();
            }
            assertThat(blocked).isTrue();first.commit();
            assertThat(loser.get(10,TimeUnit.SECONDS)).isEqualTo("23514");
            assertThat(v17.number(observer,"SELECT count(*) FROM agent."+winnerTable+" WHERE request_id='"+f.request()+"'")).isOne();
            assertThat(v17.number(observer,"SELECT count(*) FROM agent."+loserTable+" WHERE request_id='"+f.request()+"'")).isZero();
            assertThat(v17.number(observer,"SELECT count(*) FROM agent.live_template_request_slots WHERE project_id='"+f.project()+"'")).isEqualTo(dailyFirst?0:1);
        } finally {if(loser!=null)loser.cancel(true);pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void latestClaimExecutesScopedDailyExclusionAndRetainsOrdinaryPositiveControl() throws Exception {
        String url=database18();Facts f;UUID ordinary;
        try(var c=v17.connect(url)){f=facts(c);insert(c,"daily_request_links",link(f));ordinary=request(c,f.project(),OWNER,true);}
        var executed=new ArrayList<String>();var store=new JdbcAgentStore(JdbcClient.create(observedSource(url,executed,false)),mapper);
        assertThat(store.claim(f.request(),Instant.parse(AT))).isFalse();
        assertThat(store.claim(ordinary,Instant.parse(AT))).isTrue();
        assertThat(executed.stream().filter(sql->sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("update agent.requests")).toList())
            .hasSize(2).allSatisfy(sql->{String normalized=sql.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
                assertThat(normalized).contains("not exists", "agent.daily_request_links");
                var alias=java.util.regex.Pattern.compile("daily_request_links\\s+(\\w+)").matcher(normalized);
                assertThat(alias.find()).isTrue();String name=java.util.regex.Pattern.quote(alias.group(1));
                assertThat(normalized).containsPattern(name+"\\.project_id\\s*=\\s*r\\.project_id");
                assertThat(normalized).containsPattern(name+"\\.request_id\\s*=\\s*r\\.id");});
        try(var c=v17.connect(url)){
            assertThat(v17.text(c,"SELECT state FROM agent.requests WHERE id='"+f.request()+"'")).isEqualTo("QUEUED");
            assertThat(v17.text(c,"SELECT state FROM agent.requests WHERE id='"+ordinary+"'")).isEqualTo("RUNNING");
        }
    }
    @Test void genuine17ClaimStillWorksWithoutReferencingAbsentDailyTable() throws Exception {
        String url=v17.database17();UUID request;
        try(var c=v17.connect(url)){UUID p=v17.project(c);request=request(c,p,OWNER,true);}
        var executed=new ArrayList<String>();var store=new JdbcAgentStore(JdbcClient.create(observedSource(url,executed,false)),mapper);
        assertThat(store.claim(request,Instant.parse(AT))).isTrue();
        assertThat(executed.stream().filter(sql->sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("update agent.requests")).toList())
            .hasSize(1).allSatisfy(sql->assertThat(sql).doesNotContain("daily_request_links"));
    }
    @ParameterizedTest @ValueSource(strings={"TABLE_ONLY","HISTORY_ONLY","PROBE_ERROR"})
    void schemaCapabilityMismatchOrProbeFailureCannotClaimOrdinaryRequest(String mode) throws Exception {
        String url=mode.equals("HISTORY_ONLY")?database18():v17.database17();UUID request;
        try(var c=v17.connect(url)){
            UUID p=v17.project(c);request=request(c,p,OWNER,true);
            if(mode.equals("TABLE_ONLY"))v17.execute(c,"CREATE TABLE agent.daily_request_links(request_id uuid,project_id uuid)");
            if(mode.equals("HISTORY_ONLY"))v17.execute(c,"DROP TABLE agent.daily_request_links CASCADE");
        }
        var executed=new ArrayList<String>();var store=new JdbcAgentStore(JdbcClient.create(observedSource(url,executed,mode.equals("PROBE_ERROR"))),mapper);
        assertThatThrownBy(()->store.claim(request,Instant.parse(AT))).isInstanceOf(AgentUnavailableException.class);
        assertThat(executed.stream().filter(sql->sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("update agent.requests")).toList()).isEmpty();
        try(var c=v17.connect(url)){assertThat(v17.text(c,"SELECT state FROM agent.requests WHERE id='"+request+"'")).isEqualTo("QUEUED");}
    }
    // 只记录真实 execute 调用；结果完全委托 JDBC。故障模式只使元数据探测抛 SQL 异常。
    private DataSource observedSource(String url,List<String> executed,boolean failProbe) {
        var real=new DriverManagerDataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword());
        return new AbstractDataSource(){
            @Override public Connection getConnection() throws SQLException{return observe(real.getConnection());}
            @Override public Connection getConnection(String user,String password)throws SQLException{return observe(real.getConnection(user,password));}
            private Connection observe(Connection connection){
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    try{
                        Object value=method.invoke(connection,args);
                        if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql&&value instanceof PreparedStatement statement){
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(ignored,operation,parameters)->{
                                if(operation.getName().startsWith("execute")){
                                    executed.add(sql);
                                    if(failProbe&&(sql.contains("to_regclass")||sql.contains("flyway_agent_schema_history")))throw new SQLException("synthetic schema probe unavailable","08006");
                                }
                                try{return operation.invoke(statement,parameters);}catch(InvocationTargetException failure){throw failure.getCause();}
                            });
                        }return value;
                    }catch(InvocationTargetException failure){throw failure.getCause();}
                });
            }
        };
    }
}
