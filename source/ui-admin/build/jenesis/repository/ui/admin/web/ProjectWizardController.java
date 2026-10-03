package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.store.RepositoryDocument;

import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.Wizard;
import build.jenesis.repository.ui.ConsoleScreen;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.CacheService;
import build.jenesis.repository.ui.store.SettingsAdmin;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The new-project wizard: a build-cache project's name, its build tool and description, then each group of the
 * essential project settings the catalogue declares ({@link Wizard#PROJECT}), then the review - and on completion the
 * one creation every surface makes, its settings written with it
 * ({@link CacheService#createProject(String, String, String, Map)}). Nothing is written before
 * that. Its route stands apart from the projects' own, because a project may be called "new".
 */
@Controller
@ConsoleScreen
public class ProjectWizardController {

    /** The wizard's one route: its page, and where each step posts. */
    public static final String ROUTE = "/ui/new/project";

    private final CacheService service;
    private final SettingsAdmin settings;
    private final CurrentTenant tenant;

    public ProjectWizardController(CacheService service, SettingsAdmin settings, CurrentTenant tenant) {
        this.service = service;
        this.settings = settings;
        this.tenant = tenant;
    }

    /** The wizard's first step; it reads the settings documents its rows inherit from. */
    @GetMapping(ROUTE)
    public String start(Model model) throws IOException {
        model.addAttribute("wizard", WizardFlow.start(definition(), Map.of()));
        return "wizard";
    }

    /** A step posted ({@link WizardFlow#apply}); on completion the project is created, and a name taken since it was
     *  checked returns the run to the step that asked for it. */
    @PostMapping(ROUTE)
    public String step(@RequestParam Map<String, String> form, Model model, RedirectAttributes redirect)
            throws IOException {
        WizardFlow flow = WizardFlow.resume(definition(), form);
        if (flow.apply(form.get(WizardFlow.ACTION))) {
            Map<String, String> identity = flow.identity();
            String name = identity.get("name");
            String type = identity.get("type");
            try {
                service.createProject(name, type, identity.get("description"), flow.chosen());
                redirect.addFlashAttribute("message", "Created " + type + " project '" + name
                        + "'. Grant access by adding it to a credential.");
                return "redirect:/ui/projects/" + name;
            } catch (IllegalArgumentException refused) {
                flow.refuse(WizardFlow.IDENTITY + "name", refused.getMessage());
            }
        }
        model.addAttribute("wizard", flow);
        return "wizard";
    }

    private WizardFlow.Definition definition() throws IOException {
        List<WizardFlow.Step> steps = new ArrayList<>();
        steps.add(new WizardFlow.Step.Identity("Project", List.of("A project is the build cache's unit of isolation: "
                        + "one build tool's cache, whose entries are its own, and a credential is granted access to "
                        + "it."),
                List.of(new WizardFlow.Field("name", "Name", "Letters, digits and underscores.", List.of(), true),
                        new WizardFlow.Field("type", "Build tool", "The tool whose cache this is; the project answers "
                                + "that tool's endpoint and no other.", CacheService.TYPES, true),
                        new WizardFlow.Field("description", "Description", "Optional: one line under its name in a "
                                + "list.", List.of(), false))));
        steps.addAll(WizardFlow.settingsSteps(Wizard.PROJECT,
                settings.wizardViews(Wizard.PROJECT, tenant.name(), true)));
        return new WizardFlow.Definition("New project", ROUTE,
                new WizardFlow.Exit("Cancel", "/ui/projects", false), "Create project", "Create now", steps,
                List.of("Nothing has been created yet. Creating writes the project and the settings chosen here "
                        + "together; a setting left at its default keeps what the tenant and the deployment set."),
                new WizardFlow.Checks() {
                    @Override
                    public Map<String, String> identity(Map<String, String> identity) {
                        Map<String, String> refused = new LinkedHashMap<>();
                        service.nameRefusal(identity.get("name")).ifPresent(reason -> refused.put("name", reason));
                        CacheService.typeRefusal(identity.get("type")).ifPresent(reason -> refused.put("type", reason));
                        try {
                            RepositoryDocument.description(identity.get("description"));
                        } catch (IllegalArgumentException tooLong) {
                            refused.put("description", tooLong.getMessage());
                        }
                        return refused;
                    }

                    @Override
                    public Map<String, String> settings(Map<String, String> values) throws IOException {
                        return settings.refusals(Setting.Scope.PROJECT, values, true);
                    }
                });
    }
}
