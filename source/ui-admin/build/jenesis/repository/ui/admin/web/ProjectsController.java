package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.ui.identity.UiProperties;
import build.jenesis.repository.ui.store.CacheService;
import build.jenesis.repository.ui.store.Eviction;
import build.jenesis.repository.ui.store.VolumeReclaim;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Lists the projects on the volume, creates new ones, and edits a project's cache.properties (the well-known size /
 * lru / ttl values) through a typed form. A super-admin also sees the volume the projects of every tenant share and
 * runs the reclaim across them: it acts on build-cache entries, so it lives with the projects rather than on the
 * tenant chooser. Binding names are given explicitly because the Jenesis javac step does not emit
 * {@code -parameters}.
 */
@Controller
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

    @GetMapping("/ui/projects")
    public String list(Authentication authentication, Model model) throws IOException {
        model.addAttribute("projects", service.listProjects());
        boolean superadmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPERADMIN"));
        model.addAttribute("superadmin", superadmin);
        if (superadmin) {
            // The volume is the STORE's, and it is asked directly: usable is unbounded and total is zero when the
            // backend has none to report.
            Optional<ArtifactStore.Capacity> capacity = rootStorage.store().capacity();
            model.addAttribute("usableSpace", capacity.map(ArtifactStore.Capacity::usable).orElse(Long.MAX_VALUE));
            model.addAttribute("totalSpace", capacity.map(ArtifactStore.Capacity::total).orElse(0L));
            model.addAttribute("minFreeBytes", properties.getMinFreeBytes());
            model.addAttribute("minFreePercent", properties.getMinFreePercent());
        }
        return "projects";
    }

    /** The reclaim across every tenant's projects. A hyphen cannot occur in a project name, so the route cannot be
     *  read as a project's. */
    @PostMapping("/ui/projects/volume-reclaim")
    public String reclaim(@RequestParam(name = "minFreeBytes", required = false) Long minFreeBytes,
                          @RequestParam(name = "minFreePercent", required = false) Integer minFreePercent,
                          RedirectAttributes redirect) {
        long bytes = minFreeBytes != null ? minFreeBytes : properties.getMinFreeBytes();
        int percent = minFreePercent != null ? minFreePercent : properties.getMinFreePercent();
        // The reclaim spans every tenant's cache, so the mutation and its operator-scope audit live in the domain
        // (VolumeReclaim); the controller only resolves the effective thresholds and renders the outcome.
        Eviction.Result result = volumeReclaim.reclaim(bytes, percent);
        String suffix = result.entriesDeleted() == 0 ? " (target already met or no thresholds set)" : "";
        redirect.addFlashAttribute("message", "Volume reclaim: deleted " + result.entriesDeleted()
                + " entries, freed " + format.bytes(result.bytesFreed()) + suffix + ".");
        return "redirect:/ui/projects";
    }

    @PostMapping("/ui/projects")
    public String create(@RequestParam("name") String name, RedirectAttributes redirect) throws IOException {
        service.createProject(name);
        redirect.addFlashAttribute("message",
                "Created project '" + name + "'. Grant access by adding it to a credential.");
        return "redirect:/ui/projects/" + name;
    }

    @GetMapping("/ui/projects/{name}")
    public String detail(@PathVariable("name") String name, Model model) throws IOException {
        model.addAttribute("project", service.project(name));
        return "project";
    }

    @PostMapping("/ui/projects/{name}/cache")
    public String saveCache(@PathVariable("name") String name,
                            @RequestParam(name = "size", required = false) String size,
                            @RequestParam(name = "lru", required = false) String lru,
                            @RequestParam(name = "ttl", required = false) String ttl,
                            RedirectAttributes redirect) throws IOException {
        service.saveCacheConfig(name, size, lru, ttl);
        redirect.addFlashAttribute("message", "Saved cache settings for '" + name + "'.");
        return "redirect:/ui/projects/" + name;
    }
}
