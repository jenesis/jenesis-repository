package build.jenesis.repository.ui.admin.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.admin.web.CapabilityService;
import org.springframework.core.env.StandardEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One console module that cannot contribute its nav links costs its own links and nothing else.
 *
 * <p>The fan-out runs at construction, so uncontained it did not merely drop a nav entry - it threw out of
 * {@code CapabilityService}'s field initialiser, which is a Spring context that does not start and therefore a
 * deployment that does not boot, because one optional module was broken. That is the §3 rule inverted: a discovered
 * optional contributor is supposed to degrade, and this one took the shell with it.
 *
 * <p>{@link HostileConsoleModule} is registered for the whole test module rather than injected, which is the point:
 * if the containment regressed, every suite here would fail at once instead of this one leg.
 */
class ConsoleNavContainmentTest {

    @Test
    void a_module_whose_nav_throws_is_discovered_and_contributes_nothing() {
        assertThat(ConsoleModuleProvider.installed())
                .as("the hostile module really is on this path, or the containment is untested")
                .anySatisfy(provider -> assertThat(provider).isInstanceOf(HostileConsoleModule.class));
    }

    @Test
    void the_service_still_constructs_and_the_nav_simply_lacks_that_module() {
        CapabilityService service = new CapabilityService(new StandardEnvironment());

        assertThat(service.moduleNav())
                .as("the broken module contributes no links, and no other module's links are lost with it")
                .isEmpty();
        assertThat(service.capabilities())
                .as("and the rest of the gate is unaffected - the failure cost nav entries, not the shell")
                .isNotNull();
    }
}
