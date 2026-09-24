package build.jenesis.repository.compliance.web;

import java.io.IOException;

import static build.jenesis.repository.compliance.web.ComplianceConsoleConfig.QUALIFIER;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Every console screen that reads a screening ledger: what a scan found and what a maintainer-health sweep
 * recorded, the findings queue and its review, the review queue for what the gate held, who signed what, and the
 * blast radius a licence policy would have.
 *
 * <p>It sits here rather than in the console because the ledger it reads is this feature's: a deployment that
 * installs no screening has nothing to render, and a console carrying the screen would have to know the vocabulary
 * to say so. Contributed through {@code ConsoleModuleProvider}, the screen simply is not there - which is what a
 * caller already sees from every other absent module.
 *
 * <p>The read is bounded and takes no scan of its own ({@code ComplianceReview.VULNERABLE_PAGE} rows by cursor);
 * the rescan is the write path the render deliberately does not take.
 */
@Controller
public class ComplianceScreenController {

    /** How many holds the quarantine screen pages at a time. */
    private static final int QUARANTINE_PAGE = 200;

    private final ComplianceReview compliance;

    public ComplianceScreenController(ComplianceReview compliance) {
        this.compliance = compliance;
    }

    @GetMapping("/repositories/{repo}/vulnerabilities")
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

    /** The panel's explicit rescan action - the write path the read-only render deliberately does not take: query
     *  every enabled advisory feed for every published coordinate and persist the findings to the ledger. The scan
     *  runs in the background; the panel reports it as running until it lands. */
    @PostMapping("/repositories/{repo}/vulnerabilities/rescan")
    public String rescanVulnerabilities(@PathVariable("repo") String repo, RedirectAttributes redirect)
            throws IOException {
        boolean started = compliance.rescanVulnerabilities(repo);
        redirect.addFlashAttribute("message", started
                ? "Rescan started; the panel shows the outcome once it lands."
                : "A rescan is already running.");
        return "redirect:/repositories/" + repo + "/vulnerabilities";
    }

    /** The retroactive-license-enforcement blast-radius panel: a read-only preview of what turning enforcement on
     *  would newly hold in this repository under the current license policy (the console face of
     *  {@code GET /api/licenses/retro/plan}). {@code unknown} widens the preview to the riskier {@code denied+unknown}
     *  mode. The panel degrades to a "not installed" note when no license-policy module contributes a planner. */
    @GetMapping("/repositories/{repo}/blast-radius")
    public String blastRadius(@PathVariable("repo") String repo,
                              @RequestParam(name = "unknown", defaultValue = "false") boolean unknown,
                              Model model) throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("unknown", unknown);
        model.addAttribute("blast", compliance.blastRadius(repo, unknown));
        return QUALIFIER + "/blast-radius";
    }

    @PostMapping("/repositories/{repo}/blast-radius")
    public String recomputeBlastRadius(@PathVariable("repo") String repo,
                                       @RequestParam(name = "unknown", defaultValue = "false") boolean unknown,
                                       RedirectAttributes redirect) throws IOException {
        // The pass assesses every release, so the request starts it and the screen shows it running.
        redirect.addFlashAttribute("message", compliance.computeBlastRadius(repo, unknown)
                ? "Blast-radius pass started; this screen shows its result when it finishes."
                : "A blast-radius pass is already running, or no license policy is installed.");
        return "redirect:/repositories/" + repo + "/blast-radius?unknown=" + unknown;
    }

    /** The maintainer-health panel: the durable OpenSSF Scorecard-style health the sweep persisted for a repository's
     *  coordinates, read from the store with no deps.dev probe on the render path (Principle 10). The staleness stamp is
     *  always shown; the rescan button is offered only to a caller who may take the write path (below). */
    @GetMapping("/repositories/{repo}/health")
    public String health(@PathVariable("repo") String repo,
                         @RequestParam(name = "after", defaultValue = "") String after, Model model)
            throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("report", compliance.maintainerHealth(repo, after.isBlank() ? null : after));
        return QUALIFIER + "/health";
    }

    /** The health panel's explicit rescan action - the write path the read-only render deliberately does not take:
     *  probe the live maintainer-health source for every published coordinate, upsert what it scores into the ledger,
     *  stamp the freshness, then land back on the freshly-served panel. */
    @PostMapping("/repositories/{repo}/health/rescan")
    public String rescanHealth(@PathVariable("repo") String repo, RedirectAttributes redirect) throws IOException {
        // Started, not awaited: the pass asks a live source about every published coordinate. The screen shows it
        // running and the result when it lands, the same way the vulnerability and blast-radius passes report.
        redirect.addFlashAttribute("message", compliance.rescanMaintainerHealth(repo)
                ? "Maintainer-health rescan started; this panel shows its result when it finishes."
                : "A rescan is already running, or no maintainer-health module is installed.");
        return "redirect:/repositories/" + repo + "/health";
    }

    /** The findings screen: the persisted findings ledger for a repository, filterable by coordinate (the
     *  per-artifact view), kind, source, category and severity - read from the store, no feed queried. */
    @GetMapping("/repositories/{repo}/findings")
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
        return QUALIFIER + "/findings";
    }

    /** The AI review queue's confirm/dismiss on an AI-produced finding - a label on the still-present row through
     *  the shared review contract, never a deletion, and reversible. */
    @PostMapping("/repositories/{repo}/findings/review")
    public String reviewFinding(@PathVariable("repo") String repo,
                                @RequestParam("ecosystem") String ecosystem,
                                @RequestParam("coordinate") String coordinate,
                                @RequestParam("version") String version,
                                @RequestParam("source") String source,
                                @RequestParam("id") String id,
                                @RequestParam("decision") String decision,
                                @RequestParam(name = "note", defaultValue = "") String note,
                                RedirectAttributes redirect) throws IOException {
        compliance.review(repo, ecosystem, coordinate, version, source, id, decision,
                note.isBlank() ? null : note);
        redirect.addFlashAttribute("message", ("confirmed".equalsIgnoreCase(decision) ? "Confirmed " : "Dismissed ")
                + id + " on " + coordinate + ":" + version + ".");
        return "redirect:/repositories/" + repo + "/findings";
    }

    @GetMapping("/repositories/{repo}/quarantine")
    public String quarantine(@PathVariable("repo") String repo,
                             @RequestParam(name = "after", required = false) String after,
                             Model model) throws IOException {
        // The review queue, one page at a time: the hub shows its head, this screen pages the rest by pointer key.
        ComplianceReview.QuarantinePage page = compliance.quarantine(repo, after, QUARANTINE_PAGE);
        model.addAttribute("repo", repo);
        model.addAttribute("quarantine", page.holds());
        model.addAttribute("next", page.next());
        model.addAttribute("pageSize", QUARANTINE_PAGE);
        return QUALIFIER + "/quarantine";
    }

    @GetMapping("/repositories/{repo}/signers")
    public String signers(@PathVariable("repo") String repo,
                          @RequestParam(name = "after", required = false) String after,
                          Model model) throws IOException {
        // Who signed the accepted versions, a page at a time; each opens what that signer signed.
        ComplianceReview.SignersPage page = compliance.signers(repo, after, QUARANTINE_PAGE);
        model.addAttribute("repo", repo);
        model.addAttribute("signers", page.signers());
        model.addAttribute("next", page.next());
        model.addAttribute("pageSize", QUARANTINE_PAGE);
        return QUALIFIER + "/signers";
    }

    @GetMapping("/repositories/{repo}/signers/signed")
    public String signedBy(@PathVariable("repo") String repo,
                           @RequestParam("signer") String signer,
                           @RequestParam(name = "after", required = false) String after,
                           Model model) throws IOException {
        // Everything one signer signed here - the blast radius of the key when it is revoked.
        ComplianceReview.SignedPage page = compliance.signedBy(repo, signer, after, QUARANTINE_PAGE);
        model.addAttribute("repo", repo);
        model.addAttribute("signer", page.signer());
        model.addAttribute("abbreviated", page.abbreviated());
        // The three a keyless identity comes apart into, which the page was already being handed and was dropping
        // on the floor: its template renders them behind an issuer != null test, so the screen showed the escaped
        // wire token and nothing an operator could read - the issuer that certified the workflow, and the link to
        // the workflow itself. Found 2026-09-15 by the first browser test to open the screen.
        model.addAttribute("issuer", page.issuer());
        model.addAttribute("subject", page.subject());
        model.addAttribute("link", page.link());
        model.addAttribute("coordinates", page.coordinates());
        model.addAttribute("next", page.next());
        model.addAttribute("pageSize", QUARANTINE_PAGE);
        return QUALIFIER + "/signer";
    }

    @PostMapping("/repositories/{repo}/quarantine/release")
    public String releaseQuarantined(@PathVariable("repo") String repo,
                                     @RequestParam("path") String path,
                                     RedirectAttributes redirect) throws IOException {
        compliance.releaseQuarantined(repo, path);
        redirect.addFlashAttribute("message", "Released " + path + " into the layout.");
        return "redirect:/repositories/" + repo;
    }

    @PostMapping("/repositories/{repo}/quarantine/discard")
    public String discardQuarantined(@PathVariable("repo") String repo,
                                     @RequestParam("path") String path,
                                     RedirectAttributes redirect) throws IOException {
        boolean discarded = compliance.discardQuarantined(repo, path);
        redirect.addFlashAttribute("message", discarded
                ? "Discarded " + path + "."
                : "Nothing is held at " + path + " - it was already released or discarded.");
        return "redirect:/repositories/" + repo;
    }


    /** How many rows the hub's two panels show before pointing at the screen that pages the rest. */
    private static final int HUB_WINDOW = 50;

    /** How many refusals the hub's panel shows - a bounded read of the durable ledger, never a re-screen. */
    private static final int REFUSALS = 20;

    /**
     * The hub's quarantine panel, fetched rather than rendered into the page.
     *
     * <p>The hub used to compute this on every render of a repository's first screen, whether or not anything was
     * installed to answer it - a cost that grows with what the feature holds, on exactly the screen a large
     * deployment opens most. It is an {@code hx-get} now: asked for only when this module is installed, and paid
     * for only then.
     */
    @GetMapping("/repositories/{repo}/panels/quarantine")
    public String quarantinePanel(@PathVariable("repo") String repo, Model model) throws IOException {
        ComplianceReview.QuarantinePage page = compliance.quarantine(repo, null, HUB_WINDOW);
        model.addAttribute("repo", repo);
        model.addAttribute("quarantine", page.holds());
        model.addAttribute("quarantineMore", page.next() != null);
        model.addAttribute("hubWindow", HUB_WINDOW);
        return QUALIFIER + "/hub-quarantine :: panel";
    }

    /**
     * The hub's refusals panel, on the same terms.
     *
     * <p>A refused body keeps no bytes and links no pointer, so it is never in the hold queue and the durable log
     * row is its only record - which makes this panel the operator's only sight of a denied publish.
     */
    @GetMapping("/repositories/{repo}/panels/refusals")
    public String refusalsPanel(@PathVariable("repo") String repo, Model model) throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("refusals", compliance.refusals(repo, REFUSALS));
        return QUALIFIER + "/hub-refusals :: panel";
    }

}
