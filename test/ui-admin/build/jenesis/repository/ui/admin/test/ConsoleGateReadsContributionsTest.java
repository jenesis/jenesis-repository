package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.spi.CapabilityContributor;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.ui.admin.web.CapabilityService;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's module-presence gate reads each contributed flag; it does not derive its own answer beside the
 * module that owns one.
 *
 * <p>It used to derive all six, and two of the six derivations disagreed with the contributor's. {@code gc} was
 * resolved against a configuration chain that answered nothing while the contributor resolved it against a real
 * one, so a deployment gating its collector on a setting got a console that contradicted its own
 * {@code /api/capabilities}. {@code dependents} was a different question outright - the console asked whether the
 * dependents *maintenance task* was installed, the contributor whether a query provider was. The two ship in one
 * module today, so that half was latent rather than live; the point is that one flag had two definitions and
 * nothing held them together.
 *
 * <p>This pins the wiring rather than the values, which is what makes it hold as flags are added: none of the six
 * owning modules is on this module's path, so every registry the old code read is empty here and a derived answer
 * is {@code false}. Only an answer that came through the contribution pipeline can be {@code true}.
 */
class ConsoleGateReadsContributionsTest {

    @Test
    void every_console_gating_flag_comes_from_the_contribution_and_not_from_a_second_derivation() {
        CapabilityService.Capabilities gate = new CapabilityService(new StandardEnvironment()).capabilities();

        Map<String, Boolean> read = Map.of(
                "audit", gate.audit(),
                "gc", gate.gc(),
                "scan", gate.scan(),
                "provenance", gate.provenance(),
                "dependents", gate.dependents(),
                "search", gate.search());

        assertThat(read)
                .as("each flag the stand-in contributes must reach the gate; a derived answer would be false here, "
                        + "because none of the owning modules is on this path")
                .containsOnlyKeys(ContributedFlags.FLAGS.toArray(String[]::new))
                .containsValue(true)
                .allSatisfy((flag, value) -> assertThat(value).as("the %s flag", flag).isTrue());
    }

    @Test
    void the_gate_and_the_served_document_answer_the_same_flags_the_same_way() {
        // Same pipeline, same configuration chain, so the console cannot drift from what a client is told. This is
        // the property the two surfaces lacked when each ran its own merge over its own chain.
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> served = CapabilityContributor
                .resolve(Map.of(), Features.namespaced(environment::getProperty)).capabilities();
        CapabilityService.Capabilities gate = new CapabilityService(environment).capabilities();

        assertThat(served).as("the stand-in's flags reach the served document too").isNotEmpty();
        assertThat(gate.audit()).isEqualTo(served.get("audit"));
        assertThat(gate.gc()).isEqualTo(served.get("gc"));
        assertThat(gate.scan()).isEqualTo(served.get("scan"));
        assertThat(gate.provenance()).isEqualTo(served.get("provenance"));
        assertThat(gate.dependents()).isEqualTo(served.get("dependents"));
        assertThat(gate.search()).isEqualTo(served.get("search"));
    }
}
