package io.github.furlanettoeduardo.radar.shared;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * radar-shared carries the message contracts exchanged between the services and nothing else. It
 * stays records-only and framework-free. If Spring configuration ever needs a home, it gets its own
 * module rather than a place here.
 */
@AnalyzeClasses(
    packages = SharedContractsPurityTest.SHARED_PACKAGE,
    importOptions = ImportOption.DoNotIncludeTests.class)
class SharedContractsPurityTest {

  static final String SHARED_PACKAGE = "io.github.furlanettoeduardo.radar.shared";

  @ArchTest
  static final ArchRule shared_does_not_depend_on_any_framework =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta.persistence..",
              "javax.persistence..",
              "org.hibernate..",
              "com.fasterxml.jackson..")
          .because("message contracts are plain records, serialized by the adapter that owns them")
          .allowEmptyShould(true);
}
