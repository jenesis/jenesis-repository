package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.ConsoleUrlSpace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A console module's deployment-wide screen is gated as its menu entry says: every super-admin entry's path and what
 * lies beneath it - the screen's forms and fragments - is a route only a super-admin may open, whichever module
 * contributes it, and nothing a lower floor's entry names is; a module that cannot say where its screens are
 * contributes no route, as it contributes no link.
 */
class ModuleScreenGateTest {

    @Test
    void a_modules_super_admin_entries_are_its_gated_routes() {
        assertThat(ConsoleUrlSpace.superadmin(List.of(new NavigatingConsoleModule(), new HostileConsoleModule())))
                .containsExactly("/operators", "/operators/**");
    }

    @Test
    void every_installed_module_is_asked() {
        assertThat(ConsoleUrlSpace.superadmin(ConsoleModuleProvider.installed()))
                .as("the discovered modules, the hostile one among them")
                .contains("/operators", "/operators/**");
    }
}
