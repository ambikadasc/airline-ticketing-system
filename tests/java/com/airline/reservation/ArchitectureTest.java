package com.airline.reservation;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.repository.Repository;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** The structural rules of the code base, checked on every build. */
@AnalyzeClasses(packages = "com.airline.reservation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	/** Controllers call services; they never reach the database directly. */
	@ArchTest
	static final ArchRule controllers_should_not_access_repositories = noClasses()
			.that().resideInAPackage("..api..")
			.should().dependOnClassesThat().resideInAPackage("..persistence..")
			.orShould().dependOnClassesThat().areAssignableTo(Repository.class);

	/** The web layer sits on top: services, the domain and persistence never depend on it. */
	@ArchTest
	static final ArchRule api_layer_should_not_be_used_by_service_domain_or_persistence = noClasses()
			.that().resideInAnyPackage("..service..", "..domain..", "..persistence..")
			.should().dependOnClassesThat().resideInAPackage("..api..");

	/** Features depend on each other in one direction only (booking -> flight -> aircraft, airport). */
	@ArchTest
	static final ArchRule feature_packages_should_be_free_of_cycles = slices()
			.matching("com.airline.reservation.(*)..")
			.should().beFreeOfCycles();

}
