package build.jenesis.repository.ui.test;

import build.jenesis.repository.contract.testkit.ContractCensus;
import build.jenesis.repository.contract.testkit.ContractCensus.Exemption;
import build.jenesis.repository.contract.testkit.ContractCensus.Provider;
import build.jenesis.repository.ui.Panel;
import build.jenesis.repository.ui.UiConfig;
import org.springframework.core.env.StandardEnvironment;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The identity ratchet {@link Panel} has no {@code name()} to get from the shared provider primitives. A panel is
 * discovered with a plain {@code ServiceLoader.load}, so nothing refuses two panels declaring the same {@link Panel#id}
 * - and an id is both the navigation key and the in-page anchor, so a collision renders two indistinguishable
 * navigation entries pointing at one of two identically-anchored cards, with nothing reporting it.
 *
 * <p>The refusal is here, at build time, rather than at render time on purpose: a packaging mistake must be caught
 * where it is introduced, and making the console <em>throw</em> on one would let a badly-packaged panel take the whole
 * console down - the failure mode the containment in {@link ConsoleControllerTest} exists to prevent.
 *
 * <p>Per the plan's first design gate the static and runtime legs are separate assertions: {@code ServiceLoader} sees
 * only what this module's graph resolved, so it cannot notice a panel module the test forgot to {@code requires}; the
 * static leg parses every source {@code provides ... with ...} clause instead. The uniqueness leg runs over a third
 * set again - the list {@link UiConfig} actually hands the controller, which includes the bean-contributed
 * {@code PosturePanel} that no {@code provides} clause declares and no {@code ServiceLoader} finds.
 */
class PanelCensusTest {

    /**
     * Every panel this repository renders, by class. This is the ratchet: a new panel does not reach the console
     * without an entry here, and adding the entry is what puts its id under the uniqueness assertion below.
     */
    private static final List<String> REGISTERED = List.of(
            "build.jenesis.repository.ui.BrowsePanel",
            "build.jenesis.repository.ui.ConsistencyPanel",
            "build.jenesis.repository.ui.CredentialsPanel",
            "build.jenesis.repository.ui.LogPanel",
            "build.jenesis.repository.ui.ObservabilityPanel",
            "build.jenesis.repository.ui.test.FailingPanel");

    /** No panel is exempt; the argument stays wired so an exemption is a visible, reason-bearing edit. */
    private static final List<Exemption> EXEMPTIONS = List.of();

    /** An id is a URL fragment and a navigation key: lowercase, no whitespace, nothing to escape. */
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*");

    /** Two roots, and the pair is the point: {@code source/} is the shipped inventory that must never grow a panel
     *  nobody registered, and this module declares the always-failing fixture the booted console renders. */
    private static List<Provider> declared() {
        // One call, where there were two. Reading the resolved graph rather than two source roots means the shipped
        // panels and this module's own always-failing fixture arrive together, because both are modules this test
        // JVM resolved - the split into "source" and "test/ui" existed only because a directory walk had to be told
        // where to look.
        return ContractCensus.declaredProviders(Panel.class);
    }

    /** The runtime leg, keyed by the panel's own id - so a duplicate id IS a duplicate provider name to the census. */
    private static List<Provider> discovered() {
        return ServiceLoader.load(Panel.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(panel -> Provider.runtime(panel.id(), panel))
                .toList();
    }

    /** Exactly what the console renders: the discovered panels plus the bean-contributed, config-aware one. */
    private static List<Panel> rendered() {
        return new UiConfig().panels(new StandardEnvironment());
    }

    @Test
    void every_declared_panel_is_discovered_at_runtime_and_registered_here() throws IOException {
        ContractCensus.of(Panel.class, declared(), discovered(), REGISTERED, EXEMPTIONS);
    }

    @Test
    void no_two_rendered_panels_share_an_id() {
        List<String> ids = rendered().stream().map(Panel::id).toList();

        assertThat(ids)
                .as("an id is the navigation key and the in-page anchor: two panels sharing one render two "
                        + "indistinguishable nav entries and two identically-anchored cards. Rename one.")
                .doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> assertThat(id)
                .as("an id reaches the page as a URL fragment and an anchor").matches(ID));
    }

    @Test
    void every_rendered_panel_declares_a_title() {
        assertThat(rendered()).allSatisfy(panel -> assertThat(panel.title())
                .as("%s contributes a navigation entry, so it needs a label", panel.getClass().getName())
                .isNotBlank());
    }

    @Test
    void the_census_refuses_a_duplicate_id() {
        // The whole point of the ratchet, proven rather than assumed: two panels answering to one id must fail the
        // build. Nothing asserted this before - both simply rendered.
        List<Provider> colliding = new ArrayList<>(discovered());
        colliding.add(new Provider(colliding.getFirst().name(), "build.jenesis.repository.ui.test.CollidingPanel"));

        assertThatThrownBy(() -> ContractCensus.of(Panel.class, colliding, colliding, REGISTERED, EXEMPTIONS))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("duplicate static provider name " + colliding.getFirst().name());
    }

    @Test
    void the_census_trips_when_a_leg_is_broken() throws IOException {
        List<Provider> declared = declared();
        List<Provider> discovered = discovered();

        assertThatThrownBy(() -> ContractCensus.of(Panel.class, declared, discovered,
                REGISTERED.subList(0, REGISTERED.size() - 1), EXEMPTIONS))
                .as("a panel nobody registered must fail - otherwise a new panel's id never reaches the uniqueness "
                        + "assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("neither fixture nor exemption");

        assertThatThrownBy(() -> ContractCensus.of(Panel.class, declared,
                discovered.subList(0, discovered.size() - 1), REGISTERED, EXEMPTIONS))
                .as("a declared panel the runtime graph cannot see must fail even though ServiceLoader is happy - the "
                        + "blind spot a discovery-only census has")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("runtime graph does not discover");

        assertThatThrownBy(() -> ContractCensus.of(Panel.class,
                declared.subList(0, declared.size() - 1), discovered, REGISTERED, EXEMPTIONS))
                .as("a panel the runtime graph renders that no source clause declares is as much a packaging error")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("static graph does not declare");
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            // Standalone the tree is source/; inside an enclosing project it is core/source/.
            if (Files.isDirectory(candidate.resolve("core").resolve("source").resolve("ui"))) {
                return candidate.resolve("core");
            }
            if (Files.isDirectory(candidate.resolve("source").resolve("ui"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the repository source tree");
    }
}
