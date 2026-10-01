package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.internal.AgentBudgetStore;
import io.github.aiarchguard.platform.agent.persistence.JdbcAgentBudgetStore;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class AgentBudgetIntegrationTest extends PostgresIntegrationTestSupport {
    @Autowired AgentBudgetStore budgets;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    @ParameterizedTest @ValueSource(strings = {"PROJECT", "DEPLOYMENT"})
    void concurrentReservationsHoldBothDailyCapsAndSurviveNewStore(String limitedScope) throws Exception {
        var day = LocalDate.of(2031, 1, limitedScope.equals("PROJECT") ? 1 : 10);
        var projects = IntStream.range(0, 8).mapToObj(i -> UUID.randomUUID()).toList();
        UUID limitedId = limitedScope.equals("PROJECT") ? projects.getFirst() : new UUID(0, 0);
        long cap = limitedScope.equals("PROJECT") ? 5_000_000 : 20_000_000;
        jdbc.sql("INSERT INTO agent.budget_usage(scope,scope_id,utc_day,spent_microusd) VALUES(:scope,:id,:day,:spent)")
            .param("scope", limitedScope).param("id", limitedId).param("day", day).param("spent", cap - 150_000).update();
        var barrier = new CyclicBarrier(8);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = projects.stream().<java.util.concurrent.Callable<Boolean>>map(project -> () -> {
                barrier.await();
                UUID id = limitedScope.equals("PROJECT") ? limitedId : project;
                return new TransactionTemplate(transactions).execute(status -> budgets.reserve(id, day, 100_000));
            }).toList();
            var results = pool.invokeAll(calls);
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertThat(accepted).isOne();
        }
        assertThat(jdbc.sql("SELECT spent_microusd+reserved_microusd FROM agent.budget_usage WHERE scope=:scope AND scope_id=:id AND utc_day=:day")
            .param("scope", limitedScope).param("id", limitedId).param("day", day).query(Long.class).single()).isEqualTo(cap - 50_000);
        var restarted = new JdbcAgentBudgetStore(jdbc);
        UUID retryProject = limitedScope.equals("PROJECT") ? limitedId : UUID.randomUUID();
        assertThat(new TransactionTemplate(transactions).<Boolean>execute(status -> restarted.reserve(retryProject, day, 100_000))).isFalse();
        assertThat(new TransactionTemplate(transactions).<Boolean>execute(status -> restarted.reserve(retryProject, day.plusDays(1), 100_000))).isTrue();
    }

    @Test void singleRequestCapAndInvalidSettlementDoNotMutateLedger() {
        UUID project = UUID.randomUUID();
        LocalDate day = LocalDate.of(2031, 2, 1);
        var tx = new TransactionTemplate(transactions);
        assertThat(tx.<Boolean>execute(status -> budgets.reserve(project, day, 100_001))).isFalse();
        assertThat(tx.<Boolean>execute(status -> budgets.reserve(project, day, -1))).isFalse();
        assertThat(tx.<Boolean>execute(status -> budgets.reserve(project, day, 100_000))).isTrue();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> budgets.settle(project, day, 100_000, 100_001)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql("SELECT reserved_microusd FROM agent.budget_usage WHERE scope='PROJECT' AND scope_id=:id AND utc_day=:day")
            .param("id", project).param("day", day).query(Long.class).single()).isEqualTo(100_000);
        tx.executeWithoutResult(status -> budgets.settle(project, day, 100_000, 1234));
        for (String scope : List.of("PROJECT", "DEPLOYMENT")) {
            UUID id = scope.equals("PROJECT") ? project : new UUID(0, 0);
            var amounts = jdbc.sql("SELECT reserved_microusd,spent_microusd FROM agent.budget_usage WHERE scope=:scope AND scope_id=:id AND utc_day=:day")
                .param("scope", scope).param("id", id).param("day", day)
                .query((rs, row) -> List.of(rs.getLong(1), rs.getLong(2))).single();
            assertThat(amounts).containsExactly(0L, 1234L);
        }
    }
}
