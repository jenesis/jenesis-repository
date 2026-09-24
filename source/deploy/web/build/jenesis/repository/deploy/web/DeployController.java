package build.jenesis.repository.deploy.web;

import module java.base;

import build.jenesis.repository.multipart.MultipartBody;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.server.kernel.PublishTenant;
import build.jenesis.repository.ui.CurrentTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Publish one artifact from the console.
 *
 * <p>The screen names a repository and a path and takes a file; the publish goes through
 * {@link RepositoryController#publish}, which is the same ingress edge a {@code PUT} to {@code /repository/**}
 * takes. So the routing decides the store and whether the target accepts a write, the discovered interceptor chain
 * screens the body once, and an accepted blob is laid out by the claiming format. Nothing here opens a store or
 * writes a blob, and there is deliberately no way to: an upload screen that did would be a hole in the compliance
 * gate rather than a feature.
 *
 * <p><b>The body is streamed, and it has to be.</b> No application in this product runs a {@code MultipartResolver}
 * - it would drain an artifact upload before the format read it, which is how twine and {@code dotnet nuget push}
 * publish - so the upload is read with the shared bounded multipart reader and handed to the edge as a stream. An
 * artifact is not metadata: unlike the settings-bundle import beside it, this deliberately imposes no size cap of
 * its own, because the caps that belong to a publish are the tenant's quota and the format's, and they are applied
 * where a client's publish meets them.
 *
 * <p>The tenant is the console session's, bound around the publish so the compliance gate resolves that tenant's
 * own policy - the same binding the request filter opens for a publish arriving over the wire. Without it a
 * console publish would be screened by the deployment-wide policy while an identical {@code mvn deploy} was
 * screened by the tenant's.
 */
@Controller
public class DeployController {

    private final RepositoryController repository;
    private final CurrentTenant tenant;

    public DeployController(RepositoryController repository, CurrentTenant tenant) {
        this.repository = repository;
        this.tenant = tenant;
    }

    @GetMapping("/deploy")
    public String form(Model model) {
        model.addAttribute("tenant", tenant.name());
        return "deploy/form";
    }

    /**
     * Read the upload and publish it, then report what the edge answered.
     *
     * <p>Every outcome is reported as itself. A held artifact is not a failure and must not read as one - the gate
     * quarantined it and a reviewer decides - and a refusal is not an error page, it is the gate working. Reporting
     * both as "upload failed" is how an operator learns to distrust the screen.
     */
    @PostMapping("/deploy")
    public String deploy(@RequestParam("repository") String target,
                         @RequestParam("path") String path,
                         HttpServletRequest request,
                         RedirectAttributes redirect) {
        String artifactPath = path.startsWith("/") ? path : "/" + path;
        try {
            String boundary = MultipartBody.boundary(request.getContentType())
                    .orElseThrow(() -> new IllegalArgumentException("Choose a file to publish."));
            MultipartBody.Part file = MultipartBody.over(request.getInputStream(), boundary)
                    .nextFile("artifact")
                    .orElseThrow(() -> new IllegalArgumentException("Choose a file to publish."));
            int status;
            try (PublishTenant.Scope scope = PublishTenant.open(tenant.name())) {
                status = repository.publish(tenant.name(), target, artifactPath, file.stream());
            }
            redirect.addFlashAttribute(status < 300 || status == 202 ? "message" : "error",
                    describe(status, target, artifactPath));
        } catch (IOException | RuntimeException failure) {
            redirect.addFlashAttribute("error", "Could not publish " + artifactPath + ": " + failure.getMessage());
        }
        return "redirect:/deploy";
    }

    /** What each status the edge answers means to an operator, in their terms rather than the protocol's. */
    private static String describe(int status, String target, String path) {
        return switch (status) {
            case 202 -> "Held for review: " + path + " was stored and screened into quarantine rather than "
                    + "published. It appears on the quarantine screen for a reviewer to release or refuse.";
            case 404 -> "Nothing published: no repository '" + target + "' holding a format this tenant can write "
                    + "to, or its format does not claim the path " + path + ".";
            case 405 -> "Nothing published: '" + target + "' does not accept direct writes. A proxy or a group "
                    + "view is served from its backings, so an artifact is published into a hosted repository.";
            case 409 -> "Nothing published: " + path + " already holds different bytes and this repository does "
                    + "not allow a release to be replaced.";
            case 422 -> "Refused by the compliance gate: " + path + " was screened and rejected, so nothing was "
                    + "laid out. The findings screens carry the verdict.";
            case 507 -> "Nothing published: the tenant's storage quota is exhausted.";
            default -> status < 300
                    ? "Published " + path + " to " + target + "."
                    : "Nothing published: the repository answered " + status + " for " + path + ".";
        };
    }
}
