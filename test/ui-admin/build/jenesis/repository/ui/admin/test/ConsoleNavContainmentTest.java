package build.jenesis.repository.ui.admin.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.admin.web.CapabilityService;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One console module that cannot contribute its nav links costs its own links and nothing else.
 *
 * <p>The fan-out runs while {@code CapabilityService} is being constructed, so uncontained it did not merely drop a
 * nav entry - it threw out of the constructor, which is a Spring context that does not start and therefore a
 * deployment that does not boot, because one optional module was broken. That is the §3 rule inverted: a discovered
 * optional contributor is supposed to degrade, and this one took the shell with it.
 *
 * <p>{@link HostileConsoleModule} is registered for the whole test module rather than injected, which is the point:
 * if the containment regressed, every suite here would fail at once instead of this one leg.
 *
 * <p>The last leg is a different claim about the same fan-out: which modules it asks. A console module that is
 * switched off is not imported, so its screens do not exist - and the nav was built from every <em>installed</em>
 * module, so a switched-off one still contributed a link, to a path nothing had mapped.
 */
class ConsoleNavContainmentTest {

    @Test
    void a_module_whose_nav_throws_is_discovered_and_contributes_nothing() {
        assertThat(ConsoleModuleProvider.installed())
                .as("the hostile module really is on this path, or the containment is untested")
                .anySatisfy(provider -> assertThat(provider).isInstanceOf(HostileConsoleModule.class));
    }

    /**
     * The clause that says a contribution is discovered once and never on the request path, asserted where the
     * console that ships does the discovering.
     *
     * <p>It was asserted against the shell's own advice until that node went, which made it a claim about a
     * console no image served. The shipped path discovers through {@link CapabilityService}, which resolves its
     * contributed nav in its constructor, so the fan-out happens when the bean is built or not at all.
     *
     * <p>Counting asks rather than timing anything is deliberate: a timing assertion on a saturated machine is
     * the flake this repository keeps having to remove, and the claim is not "it is fast" but "it happens once".
     * The defect it guards against is real - a console re-discovering its navigation twice a page walks the whole
     * module graph per render.
     */
    @Test
    void the_contributed_nav_is_discovered_once_and_never_on_the_request_path() {
        NavigatingConsoleModule.forget();
        CapabilityService service = new CapabilityService(new StandardEnvironment());
        int afterConstruction = NavigatingConsoleModule.asked();
        assertThat(afterConstruction)
                .as("building the service is what discovers the modules, so the fan-out happens here or nowhere")
                .isPositive();

        for (int render = 0; render < 50; render++) {
            service.moduleNav();
            service.capabilities();
        }

        assertThat(NavigatingConsoleModule.asked())
                .as("a hundred reads asked the installed modules nothing further; a module re-asked per render is "
                        + "the whole module graph walked per page, which is what the clause forbids")
                .isEqualTo(afterConstruction);
    }

    @Test
    void the_service_still_constructs_and_the_nav_simply_lacks_that_module() {
        CapabilityService service = new CapabilityService(new StandardEnvironment());

        // Until a second module joined this path, "no other module's links are lost" had no other module to lose:
        // the nav was empty and the assertion was that it stayed empty, which the containment would satisfy by
        // discarding everything. With a working neighbour on the path the claim can be made properly - the
        // hostile module contributes nothing and the neighbour's links all survive.
        assertThat(service.moduleNav())
                .as("the neighbour's links survive a module that throws while being asked for its own")
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.label())
                        .as("every surviving link came from the module that answered, not the one that threw")
                        .isIn("Everyone", "Admins", "Operators", "Settings"));
        assertThat(service.capabilities())
                .as("and the rest of the gate is unaffected - the failure cost nav entries, not the shell")
                .isNotNull();
    }

    /**
     * A console module a deployment has switched off contributes no link, because it has no screen for one to reach.
     *
     * <p>The nav was resolved from every installed module while the Spring configurations were imported from the
     * enabled ones, so the two disagreed by exactly the set of switched-off modules - and each of those put an entry
     * in the bar pointing at a path nothing had mapped. It was not hypothetical: the deploy screen ships switched
     * off, so every deployment carried an admin-only "Deploy" link answering 404, with nothing in the console able
     * to say whether the module was missing or merely off.
     *
     * <p>Switching off the one module on this path that contributes links is what makes the assertion bite: resolve
     * from installed and the four links are all still here.
     */
    @Test
    void a_switched_off_console_module_contributes_no_nav_link() {
        StandardEnvironment switchedOff = new StandardEnvironment();
        switchedOff.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("jenreg." + new NavigatingConsoleModule().name(), "false")));

        assertThat(new CapabilityService(switchedOff).moduleNav())
                .as("a module the deployment switched off is not imported, so a link to its screen is a link to a "
                        + "404 - the nav has to ask the same question the imports ask")
                .isEmpty();
    }
}
