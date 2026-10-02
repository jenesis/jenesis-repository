package build.jenesis.repository.demo.web;

import build.jenesis.repository.ui.ConsoleModuleProvider;

/**
 * The demo the first-run guide offers an empty deployment: no menu entry and no repository page, only the offer on the
 * guide's first page and the page a run's progress is read on, both under {@code /ui/setup}.
 *
 * <p>On wherever the module is: the offer is made only while the tenant holds no repository, and loading anything is
 * confirmed by typing a phrase, so a deployment in use never sees it. {@code jenrepo.first-run-demo=false} takes it
 * away entirely.
 */
public final class DemoConsoleModule implements ConsoleModuleProvider {

    /** The module's name, its {@code jenrepo.} gate, and the namespace its templates resolve under. */
    public static final String NAME = "first-run-demo";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Class<?> configuration() {
        return DemoConsoleConfig.class;
    }
}
