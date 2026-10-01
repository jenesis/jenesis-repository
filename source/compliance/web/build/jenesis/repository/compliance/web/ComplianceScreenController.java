package build.jenesis.repository.compliance.web;

import java.io.IOException;
import java.util.List;

import static build.jenesis.repository.compliance.web.ComplianceConsoleConfig.QUALIFIER;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * Every console screen that reads a screening ledger - scan findings, maintainer health, the findings and AI review
 * queues, the gate's review queue and refusals, signers, the licence blast radius - contributed through
 * {@code ConsoleModuleProvider}, so a deployment without screening has none. Reads are bounded pages; a rescan is a
 * separate write started in the background.
 */
@Controller
@ConsoleScreen
public class ComplianceScreenController {

    /** How many holds the quarantine screen pages at a time. */
    private static final int QUARANTINE_PAGE = 200;

    private final ComplianceReview compliance;

    public ComplianceScreenController(ComplianceReview compliance) {
        this.compliance = compliance;
    }

    @GetMapping("/ui/repositories/{repo}/vulnerabilities")
    public String vulnerabilities(@PathVariable("repo") String repo,
                                  @RequestParam(name = "reachability", defaultValue = "") String reachability,
                                  @RequestParam(name = "applicability", defaultValue = "") String applicability,
                                  @RequestParam(name = "after", defaultValue = "") String after,
                                  Model model) throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("reachability", reachability);
        model.addAttribute("applicability", applicability);
        model.addAttribute("report", compliance.vulnerabilities(repo, reachability, applicability,
                after.isBlank() ? null : after, ComplianceReview.VULNERABLE_PAGE));
        return QUALIFIER + "/vulnerabilities";
    }

    /** Starts the explicit vulnerability rescan in the background; the panel reports it running. */
    @PostMapping("/ui/repositories/{repo}/vulnerabilities/rescan")
    public String rescanVulnerabilities(@PathVariable("repo") String repo, RedirectAttributes redirect)
            throws IOException {
        boolean started = compliance.rescanVulnerabilities(repo);
        redirect.addFlashAttribute("message", started
                ? "Rescan started; the panel shows the outcome once it lands."
                : "A rescan is already running.");
        return "redirect:/ui/repositories/" + repo + "/vulnerabilities";
    }

    /** The enforcement preview: what retroactive licence enforcement would newly hold, as
     *  {@code GET /api/licenses/retro/plan} answers; {@code unknown} selects the {@code denied+unknown} mode. */
    @GetMapping("/ui/repositories/{repo}/enforcement-preview")
    public String enforcementPreview(@PathVariable("repo") String repo,
                                     @RequestParam(name = "unknown", defaultValue = "false") boolean unknown,
                                     Model model) throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("unknown", unknown);
        model.addAttribute("blast", compliance.blastRadius(repo, unknown));
        return QUALIFIER + "/enforcement-preview";
    }

    @PostMapping("/ui/repositories/{repo}/enforcement-preview")
    public String recomputeEnforcementPreview(@PathVariable("repo") String repo,
                                              @RequestParam(name = "unknown", defaultValue = "false") boolean unknown,
                                              RedirectAttributes redirect) throws IOException {
        redirect.addFlashAttribute("message", compliance.computeBlastRadius(repo, unknown)
                ? "Enforcement preview started; this screen shows its result when it finishes."
                : "An enforcement preview is already running, or no license policy is installed.");
        return "redirect:/ui/repositories/" + repo + "/enforcement-preview?unknown=" + unknown;
    }

    /** The maintainer-health panel from the persisted ledger, with its staleness stamp. */
    @GetMapping("/ui/repositories/{repo}/health")
    public String health(@PathVariable("repo") String repo,
                         @RequestParam(name = "after", defaultValue = "") String after, Model model)
            throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("report", compliance.maintainerHealth(repo, after.isBlank() ? null : after));
        return QUALIFIER + "/health";
    }

    /** Starts the explicit maintainer-health rescan in the background. */
    @PostMapping("/ui/repositories/{repo}/health/rescan")
    public String rescanHealth(@PathVariable("repo") String repo, RedirectAttributes redirect) throws IOException {
        redirect.addFlashAttribute("message", compliance.rescanMaintainerHealth(repo)
                ? "Maintainer-health rescan started; this panel shows its result when it finishes."
                : "A rescan is already running, or no maintainer-health module is installed.");
        return "redirect:/ui/repositories/" + repo + "/health";
    }

    /** The finding kind a code audit records its candidates under, which is what the AI review queue lists. */
    private static final String AI_CANDIDATE = "ai-candidate";

    /** The findings screen, filterable by coordinate, kind, source, category and severity. */
    @GetMapping("/ui/repositories/{repo}/findings")
    public String findings(@PathVariable("repo") String repo,
                           @RequestParam(name = "coordinate", defaultValue = "") String coordinate,
                           @RequestParam(name = "kind", defaultValue = "") String kind,
                           @RequestParam(name = "source", defaultValue = "") String source,
                           @RequestParam(name = "category", defaultValue = "") String category,
                           @RequestParam(name = "severity", defaultValue = "") String severity,
                           Model model) throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("coordinate", coordinate);
        model.addAttribute("kind", kind);
        model.addAttribute("source", source);
        model.addAttribute("category", category);
        model.addAttribute("severity", severity);
        model.addAttribute("panel", compliance.findings(repo, coordinate, kind, source, category, severity));
        model.addAttribute("reviewQueue", false);
        return QUALIFIER + "/findings";
    }

    /** The AI review queue: the findings ledger filtered to a code audit's candidates, listed beside the other review
     *  queues. */
    @GetMapping("/ui/repositories/{repo}/ai-review")
    public String aiReview(@PathVariable("repo") String repo, Model model) throws IOException {
        findings(repo, "", AI_CANDIDATE, "", "", "", model);
        model.addAttribute("reviewQueue", true);
        return QUALIFIER + "/findings";
    }

    /** Confirms or dismisses an AI-produced finding through the review contract. */
    @PostMapping("/ui/repositories/{repo}/findings/review")
    public String reviewFinding(@PathVariable("repo") String repo,
                                @RequestParam("ecosystem") String ecosystem,
                                @RequestParam("coordinate") String coordinate,
                                @RequestParam("version") String version,
                                @RequestParam("source") String source,
                                @RequestParam("id") String id,
                                @RequestParam("decision") String decision,
                                @RequestParam(name = "note", defaultValue = "") String note,
                                @RequestParam(name = "queue", defaultValue = "") String queue,
                                RedirectAttributes redirect) throws IOException {
        compliance.review(repo, ecosystem, coordinate, version, source, id, decision,
                note.isBlank() ? null : note);
        redirect.addFlashAttribute("message", ("confirmed".equalsIgnoreCase(decision) ? "Confirmed " : "Dismissed ")
                + id + " on " + coordinate + ":" + version + ".");
        return "redirect:/ui/repositories/" + repo + ("ai-review".equals(queue) ? "/ai-review" : "/findings");
    }

    @GetMapping("/ui/repositories/{repo}/quarantine")
    public String quarantine(@PathVariable("repo") String repo,
                             @RequestParam(name = "after", required = false) String after,
                             Model model) throws IOException {
        ComplianceReview.QuarantinePage page = compliance.quarantine(repo, after, QUARANTINE_PAGE);
        model.addAttribute("repo", repo);
        model.addAttribute("quarantine", page.versions());
        model.addAttribute("next", page.next());
        model.addAttribute("pageSize", QUARANTINE_PAGE);
        return QUALIFIER + "/quarantine";
    }

    @GetMapping("/ui/repositories/{repo}/signers")
    public String signers(@PathVariable("repo") String repo,
                          @RequestParam(name = "after", required = false) String after,
                          Model model) throws IOException {
        ComplianceReview.SignersPage page = compliance.signers(repo, after, QUARANTINE_PAGE);
        model.addAttribute("repo", repo);
        model.addAttribute("signers", page.signers());
        model.addAttribute("next", page.next());
        model.addAttribute("pageSize", QUARANTINE_PAGE);
        return QUALIFIER + "/signers";
    }

    @GetMapping("/ui/repositories/{repo}/signers/signed")
    public String signedBy(@PathVariable("repo") String repo,
                           @RequestParam("signer") String signer,
                           @RequestParam(name = "after", required = false) String after,
                           Model model) throws IOException {
        ComplianceReview.SignedPage page = compliance.signedBy(repo, signer, after, QUARANTINE_PAGE);
        model.addAttribute("repo", repo);
        model.addAttribute("signer", page.signer());
        model.addAttribute("abbreviated", page.abbreviated());
        // A keyless identity's parts, which the template renders apart.
        model.addAttribute("issuer", page.issuer());
        model.addAttribute("subject", page.subject());
        model.addAttribute("link", page.link());
        model.addAttribute("coordinates", page.coordinates());
        model.addAttribute("next", page.next());
        model.addAttribute("pageSize", QUARANTINE_PAGE);
        return QUALIFIER + "/signer";
    }

    /** Release the held files of one version - each {@code path} the queue's row names - into the layout. */
    @PostMapping("/ui/repositories/{repo}/quarantine/release")
    public String releaseQuarantined(@PathVariable("repo") String repo,
                                     @RequestParam("path") List<String> paths,
                                     @RequestParam(name = "coordinate", defaultValue = "") String coordinate,
                                     RedirectAttributes redirect) throws IOException {
        for (String path : paths) {
            compliance.releaseQuarantined(repo, path);
        }
        redirect.addFlashAttribute("message", "Released " + subject(paths, coordinate) + " into the layout.");
        return "redirect:/ui/repositories/" + repo + "/quarantine";
    }

    /** Discards the held files of one version, saying which were still held. */
    @PostMapping("/ui/repositories/{repo}/quarantine/discard")
    public String discardQuarantined(@PathVariable("repo") String repo,
                                     @RequestParam("path") List<String> paths,
                                     @RequestParam(name = "coordinate", defaultValue = "") String coordinate,
                                     RedirectAttributes redirect) throws IOException {
        int discarded = 0;
        for (String path : paths) {
            discarded += compliance.discardQuarantined(repo, path) ? 1 : 0;
        }
        redirect.addFlashAttribute("message", discarded > 0
                ? "Discarded " + subject(paths, coordinate) + "."
                : "Nothing of " + subject(paths, coordinate) + " is held - it was already released or discarded.");
        return "redirect:/ui/repositories/" + repo + "/quarantine";
    }

    /** What a release or a discard acted on, as its confirmation names it: the one path, or the version and how many
     *  of its files. */
    private static String subject(List<String> paths, String coordinate) {
        if (paths.size() == 1) {
            return paths.getFirst();
        }
        String files = paths.size() + " files";
        return coordinate.isBlank() ? files : coordinate + " (" + files + ")";
    }


    /** How many refusals the Refused page shows - a bounded read of the durable ledger, never a re-screen. */
    private static final int REFUSALS = 200;

    /**
     * What the gate refused outright, most recent first: a refusal's log row is its only record.
     */
    @GetMapping("/ui/repositories/{repo}/refusals")
    public String refusals(@PathVariable("repo") String repo, Model model) throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("refusals", compliance.refusals(repo, REFUSALS));
        model.addAttribute("pageSize", REFUSALS);
        return QUALIFIER + "/refusals";
    }
}
