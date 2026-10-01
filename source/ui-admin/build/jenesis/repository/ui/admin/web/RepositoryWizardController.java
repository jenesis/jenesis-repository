package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.Wizard;
import build.jenesis.repository.ui.ConsoleScreen;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.RepositoryLifecycle;
import build.jenesis.repository.ui.store.SettingsAdmin;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The new-repository wizard: a repository's name, format and description, then each group of the essential
 * repository settings the catalogue declares ({@link Wizard#REPOSITORY}), then the review - and on completion the one
 * creation every surface makes, its settings written with it
 * ({@link RepositoryLifecycle#create(String, String, String, Map, boolean)}). Nothing is written before that.
 *
 * <p>Its route stands apart from the repositories' own ({@code /ui/repositories/<name>}), because a repository may be
 * called anything a name may be, "new" included. The routing is the deployment operator's to set, so a session that
 * is not a super-admin's sees it fixed rather than asked.
 */
@Controller
@ConsoleScreen
public class RepositoryWizardController {

    /** The wizard's one route: its page, and where each step posts. */
    public static final String ROUTE = "/ui/new/repository";

    private final RepositoryLifecycle lifecycle;
    private final SettingsAdmin settings;
    private final CurrentTenant tenant;

    public RepositoryWizardController(RepositoryLifecycle lifecycle, SettingsAdmin settings, CurrentTenant tenant) {
        this.lifecycle = lifecycle;
        this.settings = settings;
        this.tenant = tenant;
    }

    /** The wizard's first step; it reads the settings documents its rows inherit from. */
    @GetMapping(ROUTE)
    public String start(Authentication authentication, Model model) throws IOException {
        model.addAttribute("wizard", WizardFlow.start(definition(SetupWizard.superadmin(authentication)), Map.of()));
        return "wizard";
    }

    /** A step posted ({@link WizardFlow#apply}); on completion the repository is created, and a name taken since it was
     *  checked returns the run to the step that asked for it. */
    @PostMapping(ROUTE)
    public String step(@RequestParam Map<String, String> form, Authentication authentication, Model model,
                       RedirectAttributes redirect) throws IOException {
        boolean operator = SetupWizard.superadmin(authentication);
        WizardFlow flow = WizardFlow.resume(definition(operator), form);
        if (flow.apply(form.get(WizardFlow.ACTION))) {
            Map<String, String> identity = flow.identity();
            String name = identity.get("name");
            String format = identity.get("format");
            try {
                RepositoryType.Creation creation = lifecycle.create(name, format, identity.get("description"),
                        flow.chosen(), operator);
                if (creation == RepositoryType.Creation.CREATED) {
                    redirect.addFlashAttribute("message", "Created " + format + " repository '" + name + "'.");
                    return "redirect:/ui/repositories/" + name;
                }
                flow.refuse(WizardFlow.IDENTITY + "name", "Repository '" + name + "' exists already.");
            } catch (IllegalArgumentException refused) {
                flow.refuse(WizardFlow.IDENTITY + "name", refused.getMessage());
            }
        }
        model.addAttribute("wizard", flow);
        return "wizard";
    }

    private WizardFlow.Definition definition(boolean operator) throws IOException {
        String current = tenant.name();
        List<WizardFlow.Step> steps = new ArrayList<>();
        steps.add(WizardFlow.Step.identity("Repository", List.of("A repository holds one format, and every URL a "
                        + "client uses names it: /repository/" + current + "/<name>/..."),
                List.of(new WizardFlow.Field("name", "Name", "Letters, digits, hyphens and underscores.", List.of(),
                                true),
                        new WizardFlow.Field("format", "Format", "What the repository holds. It can later move only "
                                + "to a type that holds everything this one does.", RepositoryType.offerable(), true),
                        new WizardFlow.Field("description", "Description", "Optional: one line under its name in a "
                                + "list.", List.of(), false))));
        steps.addAll(WizardFlow.settingsSteps(Wizard.REPOSITORY,
                settings.wizardViews(Wizard.REPOSITORY, current, operator)));
        return new WizardFlow.Definition("New repository", ROUTE,
                new WizardFlow.Exit("Cancel", "/ui/repositories", false), "Create repository",
                "Create now", steps,
                List.of("Nothing has been created yet. Creating writes the repository and the settings chosen here "
                        + "together; a setting left at its default keeps what the tenant and the deployment set."),
                new WizardFlow.Checks() {
                    @Override
                    public Map<String, String> identity(Map<String, String> identity) throws IOException {
                        return lifecycle.identityRefusals(identity.get("name"), identity.get("format"),
                                identity.get("description"));
                    }

                    @Override
                    public Map<String, String> settings(Map<String, String> values) throws IOException {
                        return settings.refusals(Setting.Scope.REPOSITORY, values, operator);
                    }
                });
    }
}
