package com.corebanking;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Fails the build if a module reaches into another module's internals or modules form a cycle. */
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(CoreApplication.class);

    @Test
    void modulesRespectBoundaries() {
        MODULES.verify();
    }

    @Test
    void writeModuleDiagrams() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
