package io.github.furlanettoeduardo.radar.domain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Guards the central rule of the hexagon: the domain knows nothing about frameworks.
 *
 * <p>The maven-enforcer-plugin already bans these artifacts from the module's dependency tree. This
 * test covers the other half of the problem: an import that slips in through a dependency that is
 * legitimately present for another reason. Both checks stay.
 */
@AnalyzeClasses(
    packages = DomainPurityTest.DOMAIN_PACKAGE,
    importOptions = ImportOption.DoNotIncludeTests.class)
class DomainPurityTest {

  static final String DOMAIN_PACKAGE = "io.github.furlanettoeduardo.radar.domain";

  @ArchTest
  static final ArchRule domain_does_not_depend_on_any_framework =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta.persistence..",
              "javax.persistence..",
              "org.hibernate..",
              "com.fasterxml.jackson..")
          .because(
              "the domain must stay framework free: persistence, serialization and dependency"
                  + " injection belong in the adapters")
          // The module is empty in the foundation stage; an empty domain is not a violation.
          .allowEmptyShould(true);
}
