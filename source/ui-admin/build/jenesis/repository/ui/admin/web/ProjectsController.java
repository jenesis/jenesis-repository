package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.ui.identity.UiProperties;
import build.jenesis.repository.ui.store.CacheService;
import build.jenesis.repository.ui.store.Eviction;
import build.jenesis.repository.ui.store.VolumeReclaim;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * Lists the build-cache projects, creates them, and edits a project's settings through the catalogue. A super-admin
 * also sees the volume every tenant's projects share and runs the reclaim across them. A handler resolving settings
 * reads the project's, the tenant's and the deployment's settings documents, one object per module each. Binding names
 * are explicit because the build compiles without {@code -parameters}.
 */
@Controller
@ConsoleScreen
public class ProjectsController {

    private final CacheService service;
    private final Documents rootStorage;
    private final UiProperties properties;
    private final Format format;
    private final VolumeReclaim volumeReclaim;

    public ProjectsController(CacheService service, @Qualifier("rootStorage") Documents rootStorage,
                              UiProperties properties, Format format, VolumeReclaim volumeReclaim) {
        this.service = service;
        this.rootStorage = rootStorage;
        this.properties = properties;
        this.format = format;
        this.volumeReclaim = volumeReclaim;
    }

    /** The tenant's projects. */
    @GetMapping("/ui/projects")
    public String list(Model model) throws IOException {
        model.addAttribute("projects", service.listProjects());
        return "projects";
    }

    /** The build tools this node's cache serves and where each is pointed. A hyphen cannot occur in a project name,
     *  so the route cannot be read as a project's. */
    @GetMapping("/ui/projects/build-tools")
    public String buildTools() {
        return "project-tools";
    }

    /** The volume every tenant's projects share, and the reclaim across them: a super-admin's. */
    @GetMapping("/ui/projects/cache-volume")
    public String cacheVolume(Model model) throws IOException {
        // The store's volume: usable unbounded and total zero when the backend reports none.
        Optional<ArtifactStore.Capacity> capacity = rootStorage.store().capacity();
        model.addAttribute("usableSpace", capacity.map(ArtifactStore.Capacity::usable).orElse(Long.MAX_VALUE));
        model.addAttribute("totalSpace", capacity.map(ArtifactStore.Capacity::total).orElse(0L));
        model.addAttribute("minFreeBytes", properties.getMinFreeBytes());
        model.addAttribute("minFreePercent", properties.getMinFreePercent());
        return "cache-volume";
    }

    /** The reclaim across every tenant's projects. A hyphen cannot occur in a project name, so the route cannot be
     *  read as a project's. */
    @PostMapping("/ui/projects/volume-reclaim")
    public String reclaim(@RequestParam(name = "minFreeBytes", required = false) Long minFreeBytes,
                          @RequestParam(name = "minFreePercent", required = false) Integer minFreePercent,
                          RedirectAttributes redirect) {
        long bytes = minFreeBytes != null ? minFreeBytes : properties.getMinFreeBytes();
        int percent = minFreePercent != null ? minFreePercent : properties.getMinFreePercent();
        // The reclaim and its operator-scope audit live in VolumeReclaim.
        Eviction.Result result = volumeReclaim.reclaim(bytes, percent);
        String suffix = result.entriesDeleted() == 0 ? " (target already met or no thresholds set)" : "";
        redirect.addFlashAttribute("message", "Volume reclaim: deleted " + result.entriesDeleted()
                + " entries, freed " + format.bytes(result.bytesFreed()) + suffix + ".");
        return "redirect:/ui/projects/cache-volume";
    }

    /** One project's page and settings. */
    @GetMapping("/ui/projects/{name}")
    public String detail(@PathVariable("name") String name, Model model) throws IOException {
        model.addAttribute("project", service.project(name));
        model.addAttribute("groups", service.settings(name));
        return "project";
    }

    /** Give a project a description, or clear it with an empty one; saving what is already there says nothing. */
    @PostMapping("/ui/projects/{name}/describe")
    public String describe(@PathVariable("name") String name,
                           @RequestParam(name = "description", defaultValue = "") String description,
                           RedirectAttributes redirect) throws IOException {
        try {
            if (service.describeProject(name, description)) {
                redirect.addFlashAttribute("message", "Updated the description of '" + name + "'.");
            }
        } catch (IllegalArgumentException refused) {
            redirect.addFlashAttribute("error", refused.getMessage());
        }
        return "redirect:/ui/projects/" + name;
    }

    /** Sets or clears one of the project's settings through the catalogue. */
    @PostMapping("/ui/projects/{name}/settings/save")
    public String saveSetting(@PathVariable("name") String name, @RequestParam("key") String key,
                              @RequestParam(name = "value", defaultValue = "") String value,
                              RedirectAttributes redirect) throws IOException {
        try {
            service.saveSetting(name, key, value);
            redirect.addFlashAttribute("message", value.isBlank()
                    ? "'" + key + "' is inherited again for '" + name + "'."
                    : "Saved '" + key + "' for '" + name + "'.");
        } catch (IllegalArgumentException refused) {
            redirect.addFlashAttribute("error", refused.getMessage());
        }
        return "redirect:/ui/projects/" + name;
    }

    /** Delete the project behind the typed-name dialog, which submits the phrase as {@code confirm}; the phrase is
     *  checked here too, so a request that skipped the dialog deletes nothing. It runs in the background, and the
     *  list shows the project as being deleted until it has gone. */
    @PostMapping("/ui/projects/{name}/delete")
    public String delete(@PathVariable("name") String name,
                         @RequestParam(name = "confirm", defaultValue = "") String confirm,
                         RedirectAttributes redirect) throws IOException {
        if (!confirm.trim().equals("delete " + name)) {
            redirect.addFlashAttribute("error", "Nothing was deleted: type \"delete " + name + "\" to confirm.");
            return "redirect:/ui/projects/" + name;
        }
        if (!service.deleteProject(name)) {
            redirect.addFlashAttribute("error", "A pass is running on '" + name + "'; delete it once that has landed.");
            return "redirect:/ui/projects/" + name;
        }
        redirect.addFlashAttribute("message", "Deleting project '" + name
                + "' in the background. It is removed from the list once its entries are gone.");
        return "redirect:/ui/projects";
    }
}
