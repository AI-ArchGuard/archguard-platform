package io.github.aiarchguard.platform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {
    @Test
    void verifiesModularMonolithBoundaries() {
        ApplicationModules.of(ArchGuardPlatformApplication.class).verify();
    }

    @Test
    void keepsProjectDomainIndependentFromFrameworkAndPersistenceTypes() {
        noClasses()
            .that().resideInAPackage("..project.internal.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "jakarta..", "java.sql..", "com.fasterxml.jackson..")
            .check(new ClassFileImporter().importPackages("io.github.aiarchguard.platform.project.internal.domain"));
    }
}
