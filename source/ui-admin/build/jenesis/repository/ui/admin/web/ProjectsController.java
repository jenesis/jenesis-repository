package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.ui.store.CacheService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Lists the projects on the volume, creates new ones, and edits a project's cache.properties
 * (the well-known size / lru / ttl values) through a typed form. Binding names are given explicitly
 * because the Jenesis javac step does not emit {@code -parameters}.
 */
@Controller
public class ProjectsController {

    private final CacheService service;

    public ProjectsController(CacheService service) {
        this.service = service;
    }

    @GetMapping("/ui/projects")
    public String list(Model model) throws IOException {
        model.addAttribute("projects", service.listProjects());
        return "projects";
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
