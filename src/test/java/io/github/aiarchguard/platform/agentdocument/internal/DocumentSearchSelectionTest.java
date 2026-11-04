package io.github.aiarchguard.platform.agentdocument.internal;

import static org.junit.jupiter.api.Assertions.*;
import io.github.aiarchguard.platform.agentdocument.*;
import io.github.aiarchguard.platform.project.*;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 直接测试文档应用服务的版本选择校验，不启动 Spring、数据库或其他集成夹具。 */
class DocumentSearchSelectionTest {
    private static final UUID PROJECT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID FIRST = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SECOND = UUID.fromString("33333333-3333-4333-8333-333333333333");

    static Stream<Arguments> legalSelections() {
        return Stream.of(
            Arguments.of("List.of 单版本", List.of(FIRST)),
            Arguments.of("List.of 双版本", List.of(SECOND, FIRST)),
            Arguments.of("List.copyOf", List.copyOf(new ArrayList<>(List.of(SECOND, FIRST)))),
            Arguments.of("构建器流式列表", Stream.of(SECOND, FIRST).toList()));
    }
    static Stream<Arguments> invalidSelections() {
        return Stream.of(
            Arguments.of("空引用", (Object) null),
            Arguments.of("空列表", List.of()),
            Arguments.of("含空元素", Arrays.asList(FIRST, null)),
            Arguments.of("重复版本", List.of(FIRST, FIRST)),
            Arguments.of("超过十个版本", Stream.generate(UUID::randomUUID).limit(11).toList()));
    }

    @ParameterizedTest(name = "{0}") @MethodSource("legalSelections")
    void acceptsLegalImmutableSelectionsAndPreservesScopedSearch(String label, List<UUID> versions) {
        var fixture = new Fixture(versions, false, versions.size());
        var result = assertDoesNotThrow(() -> fixture.service.search(PROJECT, versions, "  architecture rule  "));
        assertSame(fixture.result, result);
        assertEquals(List.of("authorize", "count", "search"), fixture.events);
    }

    @ParameterizedTest(name = "{0}") @MethodSource("invalidSelections")
    void invalidSelectionsNeverReachStore(String label, List<UUID> versions) {
        var fixture = new Fixture(versions, false, 0);
        assertThrows(InvalidDocumentException.class, () -> fixture.service.search(PROJECT, versions, "architecture rule"));
        assertEquals(List.of("authorize"), fixture.events);
    }

    @ParameterizedTest(name = "{0}") @MethodSource("invalidSelections")
    void authorizationPrecedesEvenInvalidSelectionValidation(String label, List<UUID> versions) {
        var fixture = new Fixture(versions, true, 0);
        assertThrows(ProjectNotFoundException.class, () -> fixture.service.search(PROJECT, versions, "architecture rule"));
        assertEquals(List.of("authorize"), fixture.events);
    }

    @Test void incompleteProjectScopedVersionMatchNeverSearchesPartialSelection() {
        var versions = List.of(SECOND, FIRST);
        var fixture = new Fixture(versions, false, 1);
        assertThrows(DocumentNotFoundException.class, () -> fixture.service.search(PROJECT, versions, "architecture rule"));
        assertEquals(List.of("authorize", "count"), fixture.events);
    }

    private static final class Fixture {
        final List<String> events = new ArrayList<>();
        final List<DocumentFragmentView> result = List.of(new DocumentFragmentView(
            PROJECT, FIRST, "a".repeat(64), 0, 0, "b".repeat(64), "architecture rule"));
        final DocumentApplicationService service;
        Fixture(List<UUID> selected, boolean deny, int visibleCount) {
            var authorization = (ProjectAuthorization) Proxy.newProxyInstance(ProjectAuthorization.class.getClassLoader(),
                new Class<?>[]{ProjectAuthorization.class}, (proxy, method, args) -> {
                    assertEquals("requireViewer", method.getName()); assertEquals(PROJECT, args[0]);
                    events.add("authorize");
                    if (deny) throw new ProjectNotFoundException();
                    return null;
                });
            var store = (DocumentStore) Proxy.newProxyInstance(DocumentStore.class.getClassLoader(),
                new Class<?>[]{DocumentStore.class}, (proxy, method, args) -> {
                    assertEquals(PROJECT, args[0]); assertEquals(selected, args[1]);
                    return switch (method.getName()) {
                        case "countVersions" -> {
                            assertEquals(List.of("authorize"), events); events.add("count"); yield visibleCount;
                        }
                        case "search" -> {
                            assertEquals(List.of("authorize", "count"), events);
                            assertEquals("architecture rule", args[2]); assertEquals(4, args[3]);
                            events.add("search"); yield result;
                        }
                        default -> throw new AssertionError("不允许的存储动作：" + method.getName());
                    };
                });
            service = new DocumentApplicationService(authorization,
                () -> { throw new AssertionError("检索不读取额外主体"); }, store,
                event -> { throw new AssertionError("检索不写审计"); },
                () -> { throw new AssertionError("检索不生成追踪写入"); }, Clock.systemUTC());
        }
    }
}
