package io.github.codewithcharles.mcomputer;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The dependency rule, mechanically enforced.
 *
 * <p>The first test of this project, deliberately written before any business
 * test: an architecture that is not checked by a machine is a preference, not a
 * constraint.
 */
final class ArchitectureTest {

    private static final String ROOT = "io.github.codewithcharles.mcomputer";
    private static final String CORE = ROOT + ".core..";
    private static final String ADAPTERS = ROOT + ".minecraft..";
    private static final String ENTRYPOINT = ROOT + ".MComputer";

    /** Not "Minecraft", but everything the core has no right to know about. */
    private static final String[] THE_GAME = {
            "net.minecraft..", "net.fabricmc..", "com.mojang..", "org.spongepowered.."
    };

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    void coreDoesNotSeeMinecraft() {
        noClasses()
                .that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAnyPackage(THE_GAME)
                .because("the core must be unit-testable without launching the game")
                .check(classes);
    }

    @Test
    void coreDoesNotSeeLuaJ() {
        noClasses()
                .that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAnyPackage("org.luaj..")
                .because("this is what forces the Vm port to exist")
                .check(classes);
    }

    @Test
    void onlyTheAdapterLayerFacesTheGame() {
        noClasses()
                .that().resideOutsideOfPackage(ADAPTERS)
                .and().doNotHaveFullyQualifiedName(ENTRYPOINT)
                .should().dependOnClassesThat().resideInAnyPackage(THE_GAME)
                .because("adapters are the only zone aware of both worlds")
                .check(classes);
    }
}