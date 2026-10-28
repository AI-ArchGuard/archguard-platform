package io.github.aiarchguard.platform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 以 Spring Modulith 与 ArchUnit 检查模块化单体边界和 Project 领域依赖。第二条规则只扫描 Project 领域包，不能据此宣称所有领域均有同等专用依赖检查。
 */
class ArchitectureTest {
    @Test
    // 从应用入口发现模块并验证可见性与依赖关系，防止跨模块依赖内部实现。
    void verifiesModularMonolithBoundaries() {
        ApplicationModules.of(ArchGuardPlatformApplication.class).verify();
    }

    @Test
    // 单独约束 Project 领域不依赖框架、持久化和序列化类型。
    void keepsProjectDomainIndependentFromFrameworkAndPersistenceTypes() {
        noClasses()
            .that().resideInAPackage("..project.internal.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "jakarta..", "java.sql..", "com.fasterxml.jackson..")
            .check(new ClassFileImporter().importPackages("io.github.aiarchguard.platform.project.internal.domain"));
    }
}
