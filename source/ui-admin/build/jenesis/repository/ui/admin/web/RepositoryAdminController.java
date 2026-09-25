package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.inventory.DownloadTracker;
import org.springframework.beans.factory.ObjectProvider;
import build.jenesis.repository.format.FormatMarks;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import build.jenesis.repository.ui.store.RepositoryImports;
import build.jenesis.repository.ui.store.RepositoryLifecycle;
import build.jenesis.repository.ui.store.SettingsAdmin;
import build.jenesis.repository.ui.store.TenantLimits;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.ui.BrowseRow;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The repository-admin panels of the console: list the tenant's repositories, and per repository browse releases,
 * promote or drop staging, edit retention, manage pins, preview or run cleanup, and migrate another manager's
 * repository in (the installed import-source modules decide which). Reads are GET (any member of the tenant); mutations are POST and therefore require editor or admin
 * (see SecurityConfig), the same role model the cache panels use. Binding names are explicit because the Jenesis
 * javac step does not emit {@code -parameters}.
 */
@Controller
public class RepositoryAdminController {

    /** How many recent releases the overview renders - a bound so it never buffers or emits a row per release of a
     *  repository with a very large published set; the full, paged list is the browse page. */
    private static final int DETAIL_RELEASES = 200;


    /** How many staging ids the staging page shows before pointing at the staging API, which pages the rest. */
    private static final int HUB_WINDOW = 50;


    private final ObjectProvider<DownloadTracker> downloads;
    private final RepositoryAdmin repositories;
    private final RepositoryBrowse browse;
    private final TenantLimits limits;
    private final RepositoryImports migrations;
    private final RepositoryLifecycle lifecycle;
    private final SettingsAdmin settings;
    private final FormatMarks marks;

    public RepositoryAdminController(RepositoryAdmin repositories, RepositoryBrowse browse,
                                     TenantLimits limits, RepositoryImports migrations, RepositoryLifecycle lifecycle,
                                     SettingsAdmin settings, FormatMarks marks,
                                     ObjectProvider<DownloadTracker> downloads) {
        this.downloads = downloads;
        this.repositories = repositories;
        this.browse = browse;
        this.limits = limits;
        this.migrations = migrations;
        this.lifecycle = lifecycle;
        this.settings = settings;
        this.marks = marks;
    }

    @GetMapping("/ui/repositories")
    public String list(Model model) throws IOException {
        List<RepositoryRow> rows = new ArrayList<>();
        List<RepositoryWarning> warnings = new ArrayList<>();
        Map<String, String> definitions = settings.repositories();   // the settings once, not once per row
        for (String name : repositories.repositories()) {
            // The parsed shape drives the per-repository badges (item 2): writable vs read-only, and per-fallback
            // store/no-store + screen strength, from the one Definition the router routes on. Its valid-but-risky
            // warnings (mixed strength, unscreened, plaintext) feed the non-blocking console banner (item 4).
            String definition = definitions.get(name);
            SettingsAdmin.RepositoryShape shape = settings.shape(name, definition);
            Optional<RepositoryDocument> document = repositories.document(name);
            String format = document.map(RepositoryDocument::format).orElse(null);
            // Only a repository with no document can be being deleted - deleting takes the document first - so only
            // such a row pays the probe for the marker, and the list costs what it did.
            boolean removing = document.isEmpty() && repositories.removing(name);
            // A combined type has no mark of its own, so it draws the marks of what it holds, as an untyped one does.
            List<Mark> held = format == null ? repositoryMarks(name)
                    : marks.forFormat(format).map(List::of).orElseGet(() -> repositoryMarks(name));
            rows.add(new RepositoryRow(name, format, held, SettingsAdmin.hardenedDefinition(definition), shape,
                    document.map(RepositoryDocument::description).orElse(""),
                    document.map(stored -> DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
                            .format(stored.created())).orElse(""),
                    removing));
            for (String warning : shape.warnings()) {
                warnings.add(new RepositoryWarning(name, warning));
            }
        }
        model.addAttribute("repositories", rows);
        model.addAttribute("formats", offered());
        // The one document that stands for nobody, rendered for a row whose namespaces no format marks at all (see
        // repositoryMarks) - held once for the page rather than copied into every row, because it attributes nothing
        // and so carries no per-row identity.
        model.addAttribute("neutralMark", Marks.neutral());
        model.addAttribute("definitionWarnings", warnings);
        model.addAttribute("quota", limits.quota());
        model.addAttribute("rateLimit", limits.rateLimit());
        return "repositories";
    }

    /**
     * The marks a repository shows in the list: one per top-level storage namespace an installed format owns,
     * deduped by the format the mark attributes to, and none at all when no format claims any of them - the view then
     * draws the single neutral mark, so every row renders a uniform figure.
     *
     * <p><b>Why emptiness is plumbing here and not an orphan.</b> {@link RepositoryAdmin#namespaces} is a raw
     * top-level listing of the repository's key space, so what it returns is overwhelmingly the storage primitives'
     * own bookkeeping - {@code publish/} pointers, content-addressed {@code blobs/}, a module's declared index space -
     * and only incidentally a format's request-path root. A namespace no installed format claims is therefore
     * ordinarily a bucket that never had a format, not a bucket whose format was removed, and dashing every one of
     * them as "not installed" would mark plumbing as loss on every row of every deployment. The orphan state is
     * raised where the recorded name really is a contributor's - the ecosystem an artifact was published under
     * ({@link #browse}) and the plug-in a finding was reported by - and never guessed from a bucket name.
     */
    private List<Mark> repositoryMarks(String repository) {
        List<Mark> resolved = new ArrayList<>();
        for (String namespace : repositories.namespaces(repository)) {
            marks.forNamespace(namespace)
                    .filter(mark -> resolved.stream().noneMatch(shown -> shown.name().equals(mark.name())))
                    .ifPresent(resolved::add);
        }
        return resolved;
    }

    /**
     * The mark a browse-search hit draws: the mark of the installed format that declares the hit's ecosystem, and
     * <b>the orphan mark when none does</b>. That emptiness is unambiguous here in a way it is not for a storage
     * namespace: an ecosystem is not a bucket name the store happens to hold, it is the label the <em>owning
     * format</em> stamped on the coordinate when it was published, so a coordinate recorded under an ecosystem no
     * installed format declares is content whose format module has left this deployment. The row keeps the identity
     * it was recorded with - the same figure the format would have generated, dashed - rather than quietly reading
     * as an ordinary hit that simply cannot be placed in the tree.
     *
     * <p>{@code null} - drawn as the neutral mark - is reserved for a hit that carries no ecosystem at all: there is
     * then no name to attribute anything to, which is neither a contributor nor an orphan.
     */
    private Mark ecosystemMark(String ecosystem) {
        if (ecosystem.isBlank()) {
            return null;
        }
        return marks.forEcosystem(ecosystem).orElseGet(() -> Marks.orphaned(ecosystem));
    }

    /** Create a repository to hold one type - the only way a repository comes into being - give one that holds content
     *  but no format the type it holds, or move one to a type that holds everything its old one did. */
    @PostMapping("/ui/repositories/create")
    public String create(@RequestParam("name") String name, @RequestParam("format") String format,
                         @RequestParam(name = "description", defaultValue = "") String description,
                         RedirectAttributes redirect) throws IOException {
        String repository = name.trim();
        RepositoryType.Creation creation;
        try {
            creation = lifecycle.create(repository, format, description);
        } catch (IllegalArgumentException refused) {
            redirect.addFlashAttribute("error", refused.getMessage());
            return "redirect:/ui/repositories";
        }
        switch (creation) {
            case CREATED -> redirect.addFlashAttribute("message",
                    "Created " + format + " repository '" + repository + "'.");
            case RETYPED -> redirect.addFlashAttribute("message",
                    "Repository '" + repository + "' now holds " + format + "; everything it served answers as before.");
            case UNCHANGED -> redirect.addFlashAttribute("message",
                    "Repository '" + repository + "' already holds " + format + ".");
            case CONFLICT -> {
                redirect.addFlashAttribute("error", "Repository '" + repository + "' holds a type " + format
                        + " does not hold everything of, so what is stored there would stop answering.");
                return "redirect:/ui/repositories";
            }
        }
        return "redirect:/ui/repositories/" + repository;
    }

    /** Give a repository a description, or clear it with an empty one. */
    @PostMapping("/ui/repositories/{repo}/describe")
    public String describe(@PathVariable("repo") String name,
                           @RequestParam(name = "description", defaultValue = "") String description,
                           RedirectAttributes redirect) throws IOException {
        try {
            if (lifecycle.describe(name, description)) {
                redirect.addFlashAttribute("message", "Updated the description of '" + name + "'.");
            } else {
                redirect.addFlashAttribute("error", "There is no repository '" + name + "'.");
                return "redirect:/ui/repositories";
            }
        } catch (IllegalArgumentException refused) {
            redirect.addFlashAttribute("error", refused.getMessage());
        }
        return "redirect:/ui/repositories/" + name;
    }

    /**
     * Delete a repository and everything it holds, and forget what it was defined as. Only through the deletion
     * dialog: the request must carry the phrase the dialog has the reader type, {@code delete <name>}, so a form
     * posted without it - or for another name - deletes nothing. The objects go off the request path; the list shows
     * the repository as being deleted until they are gone.
     *
     * <p>Forgetting the definition reads the settings document - one object per module under a constant prefix, as
     * every settings handler does - and nothing on the request path reads the repository's objects.
     */
    @PostMapping("/ui/repositories/{repo}/delete")
    public String delete(@PathVariable("repo") String name,
                         @RequestParam(name = "confirm", defaultValue = "") String confirm,
                         RedirectAttributes redirect) throws IOException {
        if (!confirm.trim().equals("delete " + name)) {
            redirect.addFlashAttribute("error", "Nothing was deleted: type \"delete " + name + "\" to confirm.");
            return "redirect:/ui/repositories";
        }
        if (settings.repositories().containsKey(name)) {
            settings.removeRepository(name);
        }
        switch (lifecycle.delete(name)) {
            case ABSENT -> redirect.addFlashAttribute("error", "There is no repository '" + name + "'.");
            case STARTED -> redirect.addFlashAttribute("message", "Deleting repository '" + name
                    + "'. It no longer answers, and it is removed from the list once everything it held is gone.");
            case RESUMED -> redirect.addFlashAttribute("message", "Resumed deleting repository '" + name + "'.");
        }
        return "redirect:/ui/repositories";
    }

    /** The types a repository can be created as - a format, or a combined type of several. */
    private static List<String> offered() {
        return RepositoryType.offerable();
    }

    @PostMapping("/ui/repositories/quota")
    public String setQuota(@RequestParam(name = "maxBytes", defaultValue = "0") long maxBytes,
                           RedirectAttributes redirect) throws IOException {
        limits.setQuota(maxBytes);
        redirect.addFlashAttribute("message", maxBytes > 0 ? "Storage quota updated." : "Storage quota cleared.");
        return "redirect:/ui/repositories";
    }

    @PostMapping("/ui/repositories/rate-limit")
    public String setRateLimit(@RequestParam(name = "permitsPerMinute", defaultValue = "0") long permitsPerMinute,
                               RedirectAttributes redirect) throws IOException {
        limits.setRateLimit(permitsPerMinute);
        redirect.addFlashAttribute("message",
                permitsPerMinute > 0 ? "Rate limit updated." : "Rate limit cleared.");
        return "redirect:/ui/repositories";
    }

    /**
     * A repository's overview: how it is routed, whether it hardens its proxy, what stands between it and its
     * collector, its published index and its most recent releases. Everything else about a repository is a page of
     * its own beside this one, listed in the sidebar - it was one screen of thirteen collapsible sections, on which a
     * healthy repository and one needing attention looked the same.
     *
     * <p>Every read here is a bounded window or a stored result, so the first screen of a repository renders in the
     * same time over a million releases as over ten. Nothing walks the published set.
     */
    /**
     * The identity every one of a repository's pages opens with - the format it holds and where a client reaches
     * it - so a page about the repository's staging or retention says which repository it is about in the same terms
     * a client uses. Absent on the pages that name no repository.
     */
    @ModelAttribute
    public void identity(@PathVariable(value = "repo", required = false) String repo, Model model) throws IOException {
        if (repo != null) {
            model.addAttribute("identity", repositories.identity(repo).orElse(null));
        }
    }

    @GetMapping("/ui/repositories/{repo}")
    public String detail(@PathVariable("repo") String repo, Model model) throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("description",
                repositories.document(repo).map(RepositoryDocument::description).orElse(""));
        RepositoryAdmin.Releases releases = repositories.recentReleases(repo, DETAIL_RELEASES);
        model.addAttribute("releases", releases.shown());
        model.addAttribute("releasesMore", releases.more());
        // The hardened proxy leg: whether this repository screens every upstream body in full. Its typed structural
        // refusals are rows of the Refused page rather than a list of their own.
        model.addAttribute("hardened", settings.hardened(repo));
        model.addAttribute("shape", settings.shape(repo));
        // The ecosystems this repository records that no installed format can place - what stands between the
        // repository and its collector - named with the explicit way out, and what the last retirement of each did,
        // since a retirement runs off the request and the button would otherwise look as though it did nothing.
        SortedSet<String> unplaceable = lifecycle.unplaceableEcosystems(repo);
        model.addAttribute("unplaceable", unplaceable);
        Map<String, StoredReport.Report> retirements = new LinkedHashMap<>();
        for (String ecosystem : unplaceable) {
            lifecycle.forgetOutcome(repo, ecosystem).ifPresent(report -> retirements.put(ecosystem, report));
        }
        model.addAttribute("retirements", retirements);
        model.addAttribute("index", browse.publishedIndex(repo));
        return "repository";
    }

    /** The repository's staging ids, a bounded window of them, each promoted or dropped from here. */
    @GetMapping("/ui/repositories/{repo}/staging")
    public String staging(@PathVariable("repo") String repo, Model model) throws IOException {
        model.addAttribute("repo", repo);
        RepositoryLifecycle.StagingWindow staging = lifecycle.stagingWindow(repo, HUB_WINDOW);
        model.addAttribute("staging", staging.views());
        model.addAttribute("stagingMore", staging.more());
        model.addAttribute("stagedCountCap", RepositoryLifecycle.STAGED_COUNT_CAP);
        model.addAttribute("hubWindow", HUB_WINDOW);
        return "repository-staging";
    }

    /** The versions pinned against eviction, and the form that pins another. */
    @GetMapping("/ui/repositories/{repo}/pins")
    public String pins(@PathVariable("repo") String repo, Model model) throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("pins", lifecycle.pins(repo));
        model.addAttribute("ecosystems", lifecycle.ecosystems(repo));
        return "repository-pins";
    }

    /** The retention policy, and the cleanup it drives: both stored results, the last preview and the last sweep. */
    @GetMapping("/ui/repositories/{repo}/retention")
    public String retention(@PathVariable("repo") String repo, Model model) throws IOException {
        RetentionPolicy policy = lifecycle.retention(repo);
        model.addAttribute("repo", repo);
        model.addAttribute("retention", new RetentionView(policy.keepLast(),
                text(policy.maxAge()), text(policy.prereleaseExpiry()), text(policy.notDownloadedFor())));
        model.addAttribute("plan", lifecycle.retentionAvailable() ? lifecycle.plan(repo).orElse(null) : null);
        model.addAttribute("lastCleanup", lifecycle.retentionAvailable() ? lifecycle.lastCleanup(repo).orElse(null)
                : null);
        return "repository-retention";
    }

    @GetMapping("/ui/repositories/{repo}/browse")
    public String browse(@PathVariable("repo") String repo,
                         @RequestParam(name = "prefix", defaultValue = "") String prefix,
                         @RequestParam(name = "q", defaultValue = "") String query,
                         @RequestParam(name = "sort", defaultValue = "name") String sort,
                         @RequestParam(name = "dir", defaultValue = "asc") String dir,
                         Model model) throws IOException {
        String safe = RepositoryBrowse.safePrefix(prefix);
        boolean searching = !query.isBlank();
        model.addAttribute("repo", repo);
        model.addAttribute("prefix", safe);
        model.addAttribute("query", query);
        model.addAttribute("searching", searching);
        if (searching) {
            RepositoryBrowse.SearchPage page = browse.search(repo, query);
            List<SearchRow> results = new ArrayList<>();
            for (RepositoryBrowse.SearchResult hit : page.results()) {
                results.add(new SearchRow(hit.coordinate(), hit.version(), hit.ecosystem(), hit.location(),
                        ecosystemMark(hit.ecosystem())));
            }
            model.addAttribute("results", results);
            // The bound is visible: a clamped hit list says so rather than reading as the whole match set.
            model.addAttribute("truncated", page.truncated());
            model.addAttribute("neutralMark", Marks.neutral());
        } else {
            boolean descending = "desc".equals(dir);
            RepositoryBrowse.BrowseLevel level = browse.browseLevel(repo, safe, sort, descending);
            model.addAttribute("entries", rows(repo, level.entries(), safe, sort,
                    descending ? "desc" : "asc", 0));
            model.addAttribute("truncated", level.truncated());
            model.addAttribute("crumbs", crumbs(safe));
            model.addAttribute("hasParent", !safe.isEmpty());
            model.addAttribute("parent", parent(safe));
            model.addAttribute("sort", sort);
            model.addAttribute("dir", descending ? "desc" : "asc");
            model.addAttribute("base", safe);
            model.addAttribute("depth", 0);
        }
        return "browse";
    }

    /** The lazy-children fragment: just the child rows under a prefix, fetched on demand when a folder is expanded
     *  in place (htmx), so the tree loads one level at a time and never scans the whole layout. Carries the current
     *  sort so an expanded folder's children keep the order the level above them chose, and the page's own prefix
     *  ({@code base}) so an injected row indents one step per level below the page - children read as nested under
     *  their folder, not as flat siblings. */
    @GetMapping("/ui/repositories/{repo}/browse/children")
    public String browseChildren(@PathVariable("repo") String repo,
                                 @RequestParam(name = "prefix", defaultValue = "") String prefix,
                                 @RequestParam(name = "base", defaultValue = "") String base,
                                 @RequestParam(name = "sort", defaultValue = "name") String sort,
                                 @RequestParam(name = "dir", defaultValue = "asc") String dir,
                                 Model model) throws IOException {
        boolean descending = "desc".equals(dir);
        String safe = RepositoryBrowse.safePrefix(prefix);
        String safeBase = RepositoryBrowse.safePrefix(base);
        model.addAttribute("repo", repo);
        RepositoryBrowse.BrowseLevel level = browse.browseLevel(repo, safe, sort, descending);
        model.addAttribute("entries", rows(repo, level.entries(), safeBase, sort, descending ? "desc" : "asc",
                Math.max(0, segments(safe) - segments(safeBase))));
        model.addAttribute("truncated", level.truncated());
        model.addAttribute("sort", sort);
        model.addAttribute("dir", descending ? "desc" : "asc");
        model.addAttribute("base", safeBase);
        return "browse :: rows";
    }


    /**
     * The level's entries as the shared browse tree draws them.
     *
     * <p>The tree body is {@code base :: browseRows}, one fragment for both consoles, and the only thing a
     * repository-scoped browse draws differently is where a row links - so the rows carry their links. A folder
     * links back into this browse and offers its children to htmx; a leaf links to the artifact detail this console
     * has and the base one does not.
     */
    private static List<BrowseRow> rows(String repo, List<RepositoryBrowse.BrowseEntry> entries, String base,
                                        String sort, String dir, int depth) {
        List<BrowseRow> rows = new ArrayList<>();
        for (RepositoryBrowse.BrowseEntry entry : entries) {
            String href = entry.folder()
                    ? UriComponentsBuilder.fromPath("/ui/repositories/{repo}/browse")
                            .queryParam("prefix", entry.path()).queryParam("sort", sort).queryParam("dir", dir)
                            .buildAndExpand(repo).toUriString()
                    : UriComponentsBuilder.fromPath("/ui/repositories/{repo}/artifact")
                            .queryParam("path", entry.path()).buildAndExpand(repo).toUriString();
            String children = entry.folder()
                    ? UriComponentsBuilder.fromPath("/ui/repositories/{repo}/browse/children")
                            .queryParam("prefix", entry.path()).queryParam("base", base)
                            .queryParam("sort", sort).queryParam("dir", dir)
                            .buildAndExpand(repo).toUriString()
                    : null;
            rows.add(new BrowseRow(entry.name(), entry.folder(), entry.size(), depth, href, children));
        }
        return rows;
    }

    /** The segment count of a safe (leading-slash or empty) browse prefix; the difference between a folder's and
     *  the page's own prefix is the indent depth its injected children render at. */
    private static int segments(String prefix) {
        return (int) prefix.chars().filter(c -> c == '/').count();
    }

    /** The detail of one published artifact: its content-addressed checksum, size, the coordinate/version the owning
     *  format describes for the path, the other versions of that coordinate, any compliance-gate verdict, and a link
     *  to its provenance attestation - all from small objects, never the artifact body. Reached from a browse leaf. */
    @GetMapping("/ui/repositories/{repo}/coordinate")
    public String coordinate(@PathVariable("repo") String repo,
                             @RequestParam("ecosystem") String ecosystem,
                             @RequestParam("coordinate") String coordinate,
                             @RequestParam(name = "after", defaultValue = "") String after,
                             Model model) throws IOException {
        // A coordinate's own screen, whichever way its format stores: the one place a blobs-namespace format's
        // package (npm, PyPI, NuGet and their kind) can be opened from a search hit or a release row, since it has
        // no folder in the browse tree, and a second way in for a tree format. The versions are paged by cursor.
        RepositoryBrowse.CoordinateDetail detail = browse.coordinate(repo, ecosystem, coordinate,
                after.isBlank() ? null : after, RepositoryBrowse.VERSIONS_PAGE);
        model.addAttribute("repo", repo);
        model.addAttribute("detail", detail);
        model.addAttribute("mark", ecosystemMark(ecosystem));
        downloads(model);
        return "coordinate";
    }

    /**
     * What the page may say about downloads: nothing at all while tracking is off (a count of zero would read as a
     * fact), and beside the count how far behind it can be, which is the tracker's flush interval - the hits held
     * in memory since the last flush are not in the number yet.
     */
    private void downloads(Model model) {
        DownloadTracker tracker = downloads.getIfAvailable(() -> DownloadTracker.NONE);
        model.addAttribute("downloadsTracked", tracker.enabled());
        model.addAttribute("downloadsLag",
                tracker.enabled() ? tracker.flushInterval().map(RepositoryAdminController::lag).orElse("") : "");
    }

    /** A duration as a person reads it - the largest unit it divides into, so a six-hour window says "6 hours". */
    static String lag(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds > 0 && seconds % 86_400 == 0) {
            return counted(seconds / 86_400, "day");
        }
        if (seconds > 0 && seconds % 3_600 == 0) {
            return counted(seconds / 3_600, "hour");
        }
        if (seconds > 0 && seconds % 60 == 0) {
            return counted(seconds / 60, "minute");
        }
        return counted(seconds, "second");
    }

    private static String counted(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }

    @GetMapping("/ui/repositories/{repo}/artifact")
    public String artifact(@PathVariable("repo") String repo,
                           @RequestParam(name = "path", defaultValue = "") String path,
                           Model model) throws IOException {
        RepositoryBrowse.ArtifactDetail detail = browse.artifact(repo, path);
        model.addAttribute("repo", repo);
        model.addAttribute("detail", detail);
        // The API names an artifact by the path a client uses within the repository, so its links do too.
        model.addAttribute("servedPath", repositories.servedPath(repo, detail.path()));
        // The origin acquisition rows (item 3, over the earlier OriginSection): where this deployment's bytes came
        // from - uploaded vs via which fallback, stored/passed-through, screening, serves. A neutral display the gate
        // does not consume; empty when the coordinate carries no recorded origin (or no metadata module is installed).
        model.addAttribute("origin", browse.origin(repo, detail.path()));
        downloads(model);
        // The browse folder the "back to folder" link returns to, computed here (not in the view): parent() guards a
        // path with no slash so an artifact requested without a path does not throw in the template.
        model.addAttribute("parent", parent(detail.path()));
        return "artifact";
    }

    /**
     * The origin API (item 3): the {@code origin} acquisition rows of a published artifact path as JSON - the
     * machine-readable twin of the artifact-detail origin panel, for an operator tool or audit export. Reads only the
     * one small {@code origin} section ({@link RepositoryBrowse#origin}), never the artifact body (§1). A
     * read-role GET gated exactly as the surrounding {@code /repositories/**} console reads are (any member of the
     * tenant, by SecurityConfig - operator/admin-appropriate for this neutral, gate-neutral display); a caller who
     * cannot read the repository never reaches it. Empty when the path carries no recorded origin.
     */
    @GetMapping("/ui/repositories/{repo}/artifact/origin")
    @ResponseBody
    public List<RepositoryBrowse.OriginRow> artifactOrigin(@PathVariable("repo") String repo,
                                                           @RequestParam(name = "path", defaultValue = "") String path)
            throws IOException {
        return browse.origin(repo, path);
    }







    @GetMapping("/ui/repositories/{repo}/import")
    public String imports(@PathVariable("repo") String repo,
                          @RequestParam(name = "after", defaultValue = "") String after, Model model)
            throws IOException {
        RepositoryImports.JobPage page = migrations.imports(repo, after.isBlank() ? null : after,
                RepositoryImports.PAGE);
        List<RepositoryImports.JobView> jobs = page.jobs();
        model.addAttribute("repo", repo);
        model.addAttribute("jobs", jobs);
        model.addAttribute("next", page.next());
        model.addAttribute("paged", !after.isBlank());
        model.addAttribute("running", jobs.stream().anyMatch(job -> "running".equals(job.state())));
        return "import";
    }

    @PostMapping("/ui/repositories/{repo}/import")
    public String startImport(@PathVariable("repo") String repo,
                              @RequestParam(name = "source", defaultValue = "") String source,
                              @RequestParam(name = "url", defaultValue = "") String url,
                              @RequestParam(name = "repository", defaultValue = "") String repository,
                              @RequestParam(name = "format", defaultValue = "") String format,
                              @RequestParam(name = "username", defaultValue = "") String username,
                              @RequestParam(name = "password", defaultValue = "") String password,
                              @RequestParam(name = "resume", defaultValue = "") String resume,
                              RedirectAttributes redirect) throws IOException {
        String job;
        try {
            job = migrations.startImport(repo, source, url, repository,
                    format.isBlank() ? null : format, username.isBlank() ? null : username,
                    password.isBlank() ? null : password, resume.isBlank() ? null : resume);
        } catch (IllegalArgumentException refused) {
            redirect.addFlashAttribute("error", refused.getMessage());
            return "redirect:/ui/repositories/" + repo + "/import";
        }
        redirect.addFlashAttribute("message",
                (resume.isBlank() ? "Started migration " : "Resumed migration ") + job + ".");
        return "redirect:/ui/repositories/" + repo + "/import";
    }

    @PostMapping("/ui/repositories/{repo}/import/{job}/dismiss")
    public String dismissImport(@PathVariable("repo") String repo, @PathVariable("job") String job,
                                RedirectAttributes redirect) throws IOException {
        boolean dismissed = migrations.dismiss(repo, job);
        redirect.addFlashAttribute("message", dismissed
                ? "Dismissed migration " + job + "."
                : "Migration " + job + " is still running and was left in place.");
        return "redirect:/ui/repositories/" + repo + "/import";
    }

    /** Forget one unplaceable ecosystem's records - the hub's explicit retirement of an absent format's data. The
     *  primitive refuses while an installed format still places the ecosystem, so the button can never retire live
     *  records; the flash line carries the refusal instead. */
    @PostMapping("/ui/repositories/{repo}/forget-ecosystem")
    public String forgetEcosystem(@PathVariable("repo") String repo,
                                  @RequestParam("ecosystem") String ecosystem,
                                  RedirectAttributes redirect) throws IOException {
        try {
            // Started, not awaited - the retirement deletes a page at a time until the ecosystem's key spaces are
            // empty. The refusal below is still synchronous, because it is the operator's mistake and belongs in
            // front of them rather than in a background job's outcome.
            redirect.addFlashAttribute("message", lifecycle.forgetEcosystem(repo, ecosystem)
                    ? "Retiring ecosystem " + ecosystem + "; this screen shows what it removed when it finishes."
                    : "A retirement of " + ecosystem + " is already running.");
        } catch (IllegalStateException stillPlaced) {
            redirect.addFlashAttribute("message", stillPlaced.getMessage());
        }
        return "redirect:/ui/repositories/" + repo;
    }

    @PostMapping("/ui/repositories/{repo}/retention")
    public String setRetention(@PathVariable("repo") String repo,
                               @RequestParam(name = "keepLast", defaultValue = "0") int keepLast,
                               @RequestParam(name = "maxAge", defaultValue = "") String maxAge,
                               @RequestParam(name = "prereleaseExpiry", defaultValue = "") String prereleaseExpiry,
                               @RequestParam(name = "notDownloadedFor", defaultValue = "") String notDownloadedFor,
                               RedirectAttributes redirect) throws IOException {
        lifecycle.setRetention(repo, RetentionPolicy.parse(keepLast, maxAge, prereleaseExpiry, notDownloadedFor));
        redirect.addFlashAttribute("message", "Retention updated.");
        return "redirect:/ui/repositories/" + repo + "/retention";
    }

    @PostMapping("/ui/repositories/{repo}/pins")
    public String pin(@PathVariable("repo") String repo,
                      @RequestParam("ecosystem") String ecosystem,
                      @RequestParam("coordinate") String coordinate,
                      @RequestParam("version") String version,
                      RedirectAttributes redirect) throws IOException {
        lifecycle.pin(repo, ecosystem, coordinate, version);
        redirect.addFlashAttribute("message", "Pinned " + coordinate + ":" + version + ".");
        return "redirect:/ui/repositories/" + repo + "/pins";
    }

    @PostMapping("/ui/repositories/{repo}/pins/remove")
    public String unpin(@PathVariable("repo") String repo,
                        @RequestParam("ecosystem") String ecosystem,
                        @RequestParam("coordinate") String coordinate,
                        @RequestParam("version") String version,
                        RedirectAttributes redirect) throws IOException {
        lifecycle.unpin(repo, ecosystem, coordinate, version);
        redirect.addFlashAttribute("message", "Unpinned " + coordinate + ":" + version + ".");
        return "redirect:/ui/repositories/" + repo + "/pins";
    }

    @PostMapping("/ui/repositories/{repo}/staging/{id}/promote")
    public String promote(@PathVariable("repo") String repo, @PathVariable("id") String id,
                          RedirectAttributes redirect) throws IOException {
        lifecycle.promote(repo, id);
        redirect.addFlashAttribute("message", "Promoted staging " + id + ".");
        return "redirect:/ui/repositories/" + repo + "/staging";
    }

    @PostMapping("/ui/repositories/{repo}/staging/{id}/drop")
    public String drop(@PathVariable("repo") String repo, @PathVariable("id") String id,
                       RedirectAttributes redirect) throws IOException {
        lifecycle.drop(repo, id);
        redirect.addFlashAttribute("message", "Dropped staging " + id + ".");
        return "redirect:/ui/repositories/" + repo + "/staging";
    }

    @PostMapping("/ui/repositories/{repo}/cleanup")
    public String cleanup(@PathVariable("repo") String repo, RedirectAttributes redirect) throws IOException {
        // The sweep walks every release, so the request starts it and returns; the hub shows the stored result.
        redirect.addFlashAttribute("message", lifecycle.cleanup(repo)
                ? "Cleanup started; its result appears under Cleanup when it finishes."
                : "A cleanup is already running; its result appears under Cleanup when it finishes.");
        return "redirect:/ui/repositories/" + repo + "/retention";
    }

    @PostMapping("/ui/repositories/{repo}/cleanup/preview")
    public String previewCleanup(@PathVariable("repo") String repo, RedirectAttributes redirect) throws IOException {
        redirect.addFlashAttribute("message", lifecycle.previewCleanup(repo)
                ? "Cleanup preview started; it appears under Cleanup when it finishes."
                : "A cleanup preview is already running.");
        return "redirect:/ui/repositories/" + repo + "/retention";
    }







    /** A repository as the list renders it: its name, the format it holds ({@code null} for one created before
     *  repositories held a format, which answers nothing until it is given one), the marks of what it holds - the
     *  format's own, else one per format namespace, empty when the console can mark none (the view then draws the
     *  neutral mark) - and whether it is a
     *  hardened proxy (EPIC 23), which the list badges so an operator sees at a glance which repositories enforce
     *  full-body upstream screening. */
    public record RepositoryRow(String name, String format, List<Mark> marks, boolean hardened,
                                SettingsAdmin.RepositoryShape shape, String description, String created,
                                boolean removing) {
    }

    /** One valid-but-risky definition warning for the console banner (item 4): the repository it applies to and
     *  the loud ⚑ the parse logged (mixed screening strength, an unscreened or plaintext upstream) - surfaced as a
     *  non-blocking notice on the repository admin view, distinct from a refused-and-not-stored parse error. */
    public record RepositoryWarning(String repository, String message) {
    }

    /** A browse search hit as the results list renders it: the {@link RepositoryBrowse.SearchResult} it wraps plus
     *  the mark of the format that owns its ecosystem, so a hit shows its format's mark beside the coordinate -
     *  {@code null} only for a hit that carries no ecosystem at all, which the view draws as the neutral mark. */
    public record SearchRow(String coordinate, String version, String ecosystem, String location, Mark mark) {
    }

    /** A repository's retention rendered for the form: durations as ISO-8601 text, blank when a dial is unset. */
    public record RetentionView(int keepLast, String maxAge, String prereleaseExpiry, String notDownloadedFor) {
    }

    /** One breadcrumb in the browse trail: its label, the accumulated prefix it navigates to, and whether it is the
     *  current (last) crumb - rendered as plain text rather than a link. */
    public record Crumb(String label, String prefix, boolean current) {
    }

    /** The path-segment crumbs of the browse trail: one crumb per accumulated path segment, the last marked current
     *  so the view renders it inert. The fixed head of the trail (the repositories list, this repository's detail hub,
     *  and the browse root) is supplied by the view, so at the browse root this list is empty and the "Browse" crumb
     *  the view renders is the current one. */
    private static List<Crumb> crumbs(String prefix) {
        List<Crumb> crumbs = new ArrayList<>();
        if (!prefix.isEmpty()) {
            String[] segments = prefix.substring(1).split("/");
            StringBuilder accumulated = new StringBuilder();
            for (int index = 0; index < segments.length; index++) {
                accumulated.append('/').append(segments[index]);
                boolean last = index == segments.length - 1;
                crumbs.add(new Crumb(segments[index], accumulated.toString(), last));
            }
        }
        return crumbs;
    }

    /** The parent browse prefix ({@code ""} for a one-segment prefix, so the up-link returns to the repository root). */
    private static String parent(String prefix) {
        int slash = prefix.lastIndexOf('/');
        return slash <= 0 ? "" : prefix.substring(0, slash);
    }

    private static String text(Duration duration) {
        return duration == null ? "" : duration.toString();
    }
}
