package build.jenesis.repository.demo.web;

import module java.base;

import build.jenesis.repository.demo.DemoContributor;
import build.jenesis.repository.ui.SetupOffer;

/**
 * The demo, as the first-run guide offers it on its first page: while the tenant holds no repository, what the demo
 * is, the plain warning that it switches features on, reaches public registries and loads code with known
 * vulnerabilities, every repository, setting, registry and vulnerable artifact the contributors' plans name, and the
 * button confirmed by typing {@value DemoRun#PHRASE}. While a run is under way it links to its progress instead; once
 * the tenant holds a repository it offers nothing.
 *
 * <p>Two point reads and one bounded page of names: the run's document, its lease while it says it is running, and
 * the tenant's first names.
 */
public final class DemoOffer implements SetupOffer {

    private final DemoRun run;

    public DemoOffer(DemoRun run) {
        this.run = run;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Optional<Offer> offer(Viewer viewer) throws IOException {
        Optional<DemoRun.State> state = run.state(viewer.tenant());
        if (state.isPresent() && state.get().running()) {
            return Optional.of(new Offer("A demo is loading", List.of("The demo is being loaded into this tenant; "
                    + "its page shows each step as it is taken."), "", List.of(), null,
                    new Link("See the demo's progress", DemoController.ROUTE)));
        }
        if (run.holdsRepository(viewer.tenant())) {
            return Optional.empty();
        }
        List<DemoRun.Planned> plans = run.plans().stream().filter(planned -> !planned.plan().empty()).toList();
        if (plans.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Offer("Try Jenesis with a demo", List.of("This tenant holds no repository yet. A demo "
                + "fills it with sample content, so every screen has something to show: repositories, packages "
                + "published into them, one held for review, and versions with known vulnerabilities read through "
                + "proxies of public registries. Loading runs in the background, and its page shows each step as it "
                + "is taken. The repositories can be deleted afterwards like any other."),
                "The demo switches features on for the whole deployment, reaches public registries from this server, "
                        + "and loads code with known vulnerabilities into this deployment. Load it only into a "
                        + "deployment you are trying out, never into one that serves builds.",
                consequences(plans),
                new Action(DemoController.ROUTE, "Load the demo", DemoRun.PHRASE, "Load the demo into this tenant?",
                        "It switches the listed features on for the whole deployment, reaches the listed public "
                                + "registries, and loads code with known vulnerabilities."),
                null));
    }

    /** Every repository, setting, registry and vulnerable artifact the plans name, a line each kind. */
    private static List<String> consequences(List<DemoRun.Planned> plans) {
        List<String> repositories = new ArrayList<>();
        List<String> settings = new ArrayList<>();
        List<String> reaches = new ArrayList<>();
        List<String> vulnerable = new ArrayList<>();
        for (DemoRun.Planned planned : plans) {
            DemoContributor.Plan plan = planned.plan();
            for (DemoContributor.Repository repository : plan.repositories()) {
                repositories.add(repository.name() + " (" + (repository.hosted() ? "hosted " + repository.type()
                        : "a " + repository.type() + " proxy of " + repository.routing().substring(
                                repository.routing().indexOf(' ') + 1)) + ")");
            }
            plan.settings().forEach((key, brings) -> settings.add(Labels.of(key) + ": " + brings));
            reaches.addAll(plan.reaches());
            vulnerable.addAll(plan.vulnerable());
        }
        List<String> lines = new ArrayList<>();
        lines.add("Creates the repositories " + String.join(", ", repositories) + ".");
        if (!settings.isEmpty()) {
            lines.add("Switches on, for the whole deployment and recorded on the audit trail as you: "
                    + String.join("; ", settings) + ".");
        }
        if (!reaches.isEmpty()) {
            lines.add("Reaches from this server: " + String.join("; ", reaches) + ".");
        }
        if (!vulnerable.isEmpty()) {
            lines.add("Loads code with known vulnerabilities into this deployment: " + String.join("; ", vulnerable)
                    + ". They are there to be found, never to be built with.");
        }
        return lines;
    }
}
