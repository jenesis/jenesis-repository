package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.ui.SuperadminRole;
import build.jenesis.repository.store.JobState;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.inventory.DownloadTracker;
import org.springframework.beans.factory.ObjectProvider;
import build.jenesis.repository.format.FormatMarks;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import build.jenesis.repository.ui.store.RepositoryImports;
import build.jenesis.repository.ui.store.RepositoryLifecycle;
import build.jenesis.repository.ui.store.SettingsAdmin;
import build.jenesis.repository.ui.store.TenantLimits;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.ui.BrowseRow;
import build.jenesis.repository.ui.CurrentTenant;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * The repository-admin panels of the console: list the tenant's repositories, and per repository browse releases,
 * promote or drop staging, edit retention, manage pins, preview or run cleanup, and migrate another manager's
 * repository in. Reads are GET, open to any member of the tenant; mutations are POST and need editor or admin
 * ({@code ConsoleAuthorization}). Binding names are explicit because the build compiles without {@code -parameters}.
 *
 * <p>A handler that resolves settings reads the repository's, the tenant's and the deployment's settings documents:
 * one object per module under a constant prefix each.
 */
@Controller
@ConsoleScreen
public class RepositoryAdminController {

    /** How many recent holdings the browse root lists for a format with no folder tree; the full, paged list is the
     *  browse page's search. */
    private static final int DETAIL_HOLDINGS = 200;
    /** How many of the newest holdings a repository's overview lists. */
    private static final int OVERVIEW_HOLDINGS = 10;


    /** How many staging ids the staging page shows before saying more exist; the staging API answers a wider window. */
    private static final int HUB_WINDOW = 50;


    private final ObjectProvider<DownloadTracker> downloads;
    private final RepositoryAdmin repositories;
    private final RepositoryBrowse browse;
    private final TenantLimits limits;
    private final RepositoryImports migrations;
    private final RepositoryLifecycle lifecycle;
    private final SettingsAdmin settings;
    private final FormatMarks marks;
    private final CurrentTenant tenant;

    public RepositoryAdminController(RepositoryAdmin repositories, RepositoryBrowse browse,
                                     TenantLimits limits, RepositoryImports migrations, RepositoryLifecycle lifecycle,
                                     SettingsAdmin settings, FormatMarks marks,
                                     ObjectProvider<DownloadTracker> downloads, CurrentTenant tenant) {
        this.downloads = downloads;
        this.tenant = tenant;
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
        // The deployment's definitions once, not once per row; each repository's own routing is one point read.
        Map<String, String> definitions = settings.repositories();
        for (String name : repositories.repositories()) {
            // The parsed routing drives the row's badges, and its valid-but-risky warnings feed the console banner.
            SettingsAdmin.Routing routing = settings.routing(tenant.name(), name, definitions);
            String definition = routing.specification();
            SettingsAdmin.RepositoryShape shape = routing.shape();
            Optional<RepositoryDocument> document = repositories.document(name);
            String format = document.map(RepositoryDocument::format).orElse(null);
            // Deleting takes the document first, so only a row without one pays the marker probe.
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
        // Drawn for a row no format marks (see repositoryMarks), once per page.
        model.addAttribute("neutralMark", Marks.neutral());
        model.addAttribute("definitionWarnings", warnings);
        return "repositories";
    }

    /** What the tenant's repositories may use together - its storage quota and request rate ceiling - with the
     *  deployment's ceiling it falls back to. */
    @GetMapping("/ui/limits")
    public String limits(Model model) throws IOException {
        model.addAttribute("quota", limits.quota());
        model.addAttribute("groups", limits.groups());
        return "limits";
    }

    /**
     * The marks a repository shows in the list: one per top-level namespace an installed format owns, deduplicated by
     * format, or none, for which the view draws the neutral mark. An unclaimed namespace is not drawn as an orphan:
     * {@link RepositoryAdmin#namespaces} lists the store's own bookkeeping ({@code publish/}, {@code blobs/}, index
     * spaces) far more often than a removed format's root.
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
     * The mark a browse-search hit draws: the installed format declaring the hit's ecosystem, or the orphan mark when
     * none does, since an ecosystem is the label its format stamped at publish. {@code null}, drawn as the neutral mark,
     * for a hit carrying no ecosystem.
     */
    private Mark ecosystemMark(String ecosystem) {
        if (ecosystem.isBlank()) {
            return null;
        }
        return marks.forEcosystem(ecosystem).orElseGet(() -> Marks.orphaned(ecosystem));
    }

    /** Gives a repository that holds content but no format its type (the list's Give format form), through the one
     *  creation every surface makes, which also moves one to a type holding everything its old one did. A new
     *  repository is created by its wizard ({@link RepositoryWizardController}). */
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
            // Saving what is already there writes nothing, records nothing and says nothing.
            String line = RepositoryDocument.description(description);
            if (repositories.document(name).map(document -> document.description().equals(line)).orElse(false)) {
                return "redirect:/ui/repositories/" + name;
            }
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
     * A repository's settings: every repository setting the catalogue carries, grouped, with its own value and what it
     * would inherit, saved through the catalogue.
     */
    @GetMapping("/ui/repositories/{repo}/settings")
    public String settings(@PathVariable("repo") String repo, Authentication authentication, Model model)
            throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("groups", settings.repositoryGroups(tenant.name(), repo, SuperadminRole.held(authentication)));
        // The tenant's other repositories, which the routing form offers as fallbacks.
        model.addAttribute("routingRepositories", repositories.repositories().stream()
                .filter(name -> !name.equals(repo)).toList());
        return "repository-settings";
    }

    /**
     * Set or clear one repository setting through the catalogue, from the settings page, the retention page or the
     * overview's routing, and return there. An operator-only setting - the routing - is refused unless the session is a
     * super-admin's, whatever the form offered.
     */
    @PostMapping("/ui/repositories/{repo}/settings/save")
    public String saveSetting(@PathVariable("repo") String repo, @RequestParam("key") String key,
                              @RequestParam(name = "value", defaultValue = "") String value,
                              @RequestParam(name = "return", defaultValue = "") String back,
                              Authentication authentication, RedirectAttributes redirect) throws IOException {
        try {
            settings.saveRepository(tenant.name(), repo, Map.of(key, value), SuperadminRole.held(authentication));
            redirect.addFlashAttribute("message", value.isBlank()
                    ? "'" + key + "' is inherited again for '" + repo + "'."
                    : "Saved '" + key + "' for '" + repo + "'.");
        } catch (IllegalArgumentException refused) {
            redirect.addFlashAttribute("error", refused.getMessage());
        }
        return "redirect:" + within(repo, back, "/settings");
    }

    /**
     * Deletes a repository and everything it holds, its settings included; the deployment's definition of its name is
     * every tenant's and stays. The request must carry the dialog's phrase {@code delete <name>} for this name. The
     * objects go off the request path, and the list shows the repository as being deleted until they are gone.
     */
    @PostMapping("/ui/repositories/{repo}/delete")
    public String delete(@PathVariable("repo") String name,
                         @RequestParam(name = "confirm", defaultValue = "") String confirm,
                         RedirectAttributes redirect) throws IOException {
        if (!confirm.trim().equals("delete " + name)) {
            redirect.addFlashAttribute("error", "Nothing was deleted: type \"delete " + name + "\" to confirm.");
            return "redirect:/ui/repositories/" + name;
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

    /**
     * Set or clear one of the tenant's limits - a setting of the Limits group, through the catalogue.
     */
    @PostMapping("/ui/limits/save")
    public String saveLimit(@RequestParam("key") String key,
                            @RequestParam(name = "value", defaultValue = "") String value,
                            RedirectAttributes redirect) throws IOException {
        limits.save(key, value);
        redirect.addFlashAttribute("message", value.isBlank() ? "'" + key + "' follows the deployment again."
                : "Saved '" + key + "' for this tenant.");
        return "redirect:/ui/limits";
    }

    /**
     * A repository's overview: what it took in most recently, its routing, whether it hardens its proxy, what stands
     * between it and its collector and its published index. Every read is a point read, a stored result or a bounded
     * page of a newest-first index.
     */
    @GetMapping("/ui/repositories/{repo}")
    public String detail(@PathVariable("repo") String repo, Model model) throws IOException {
        model.addAttribute("repo", repo);
        Optional<RepositoryDocument> document = repositories.document(repo);
        model.addAttribute("description", document.map(RepositoryDocument::description).orElse(""));
        // The routing in force for this tenant, its own or the deployment's, and which, so the overview can say whether
        // editing it changes this tenant alone.
        SettingsAdmin.Routing routing = settings.routing(tenant.name(), repo);
        model.addAttribute("routing", routing);
        model.addAttribute("hardened", SettingsAdmin.hardenedDefinition(routing.specification()));
        model.addAttribute("shape", routing.shape());
        // With no definition, a repository fetches what it lacks through its format's upstream - the tenant's own,
        // else the deployment's - so "fetches from nowhere" is true only where neither names one.
        String format = document.map(RepositoryDocument::format).orElse(null);
        String upstream = null;
        if (format != null) {
            upstream = tenant.name() == null ? null : settings.upstreams(tenant.name()).get(format);
            if (upstream == null) {
                upstream = settings.upstreams().get(format);
            }
        }
        model.addAttribute("formatUpstream", upstream);
        // The ecosystems no installed format can place, which hold up the collector, with the last retirement of each,
        // since a retirement runs off the request.
        SortedSet<String> unplaceable = lifecycle.unplaceableEcosystems(repo);
        model.addAttribute("unplaceable", unplaceable);
        Map<String, StoredReport.Report> retirements = new LinkedHashMap<>();
        for (String ecosystem : unplaceable) {
            lifecycle.forgetOutcome(repo, ecosystem).ifPresent(report -> retirements.put(ecosystem, report));
        }
        model.addAttribute("retirements", retirements);
        model.addAttribute("index", browse.publishedIndex(repo));
        RepositoryAdmin.Held recent = repositories.recentHoldings(repo, OVERVIEW_HOLDINGS);
        model.addAttribute("recent", recent.shown());
        model.addAttribute("recentMore", recent.more());
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

    /** The retention policy, and the stored results of the last cleanup preview and sweep. */
    @GetMapping("/ui/repositories/{repo}/retention")
    public String retention(@PathVariable("repo") String repo, Model model) throws IOException {
        RetentionPolicy policy = lifecycle.retention(repo);
        model.addAttribute("repo", repo);
        model.addAttribute("retention", new RetentionView(policy.keepLast(),
                text(policy.maxAge()), text(policy.prereleaseExpiry()), text(policy.notDownloadedFor())));
        // The rules are repository settings, edited here with the settings page's own rows and save.
        model.addAttribute("groups", settings.repositoryGroups(tenant.name(), repo, true).stream()
                .filter(group -> group.settings().stream()
                        .anyMatch(setting -> RetentionPolicy.KEYS.contains(setting.key())))
                .toList());
        model.addAttribute("plan", lifecycle.retentionAvailable() ? lifecycle.plan(repo).orElse(null) : null);
        model.addAttribute("lastCleanup", lifecycle.retentionAvailable() ? lifecycle.lastCleanup(repo).orElse(null)
                : null);
        return "repository-retention";
    }

    /**
     * A level of the repository's browse tree, or one page of its search; the search bar says whether the repository
     * answers by name or full text ({@code full-text-search}).
     */
    @GetMapping("/ui/repositories/{repo}/browse")
    public String browse(@PathVariable("repo") String repo,
                         @RequestParam(name = "prefix", defaultValue = "") String prefix,
                         @RequestParam(name = "q", defaultValue = "") String query,
                         @RequestParam(name = "cursor", required = false) String cursor,
                         @RequestParam(name = "sort", defaultValue = "name") String sort,
                         @RequestParam(name = "dir", defaultValue = "asc") String dir,
                         Model model) throws IOException {
        String safe = RepositoryBrowse.safePrefix(prefix);
        boolean searching = !query.isBlank();
        model.addAttribute("repo", repo);
        model.addAttribute("prefix", safe);
        model.addAttribute("query", query);
        model.addAttribute("searching", searching);
        UnaryOperator<String> config = settings.repositoryConfig(tenant.name(), repo);
        model.addAttribute("fullText", SearchMode.of(config) == SearchMode.FULL_TEXT);
        if (searching) {
            RepositoryBrowse.SearchPage page = browse.search(repo, config, query, cursor);
            List<SearchRow> results = new ArrayList<>();
            for (RepositoryBrowse.SearchResult hit : page.results()) {
                results.add(new SearchRow(hit.display(), hit.coordinate(), hit.version(), hit.ecosystem(),
                        hit.location(), ecosystemMark(hit.ecosystem())));
            }
            model.addAttribute("results", results);
            model.addAttribute("indexed", page.indexed());
            model.addAttribute("truncated", page.truncated());
            model.addAttribute("nextCursor", page.nextCursor());
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
            // An empty root holding versions is a format with no folder tree, whose holdings are listed instead.
            RepositoryAdmin.Held holdings = level.entries().isEmpty() && safe.isEmpty()
                    ? repositories.recentHoldings(repo, DETAIL_HOLDINGS) : null;
            model.addAttribute("holdings", holdings == null ? List.of() : holdings.shown());
            model.addAttribute("holdingsMore", holdings != null && holdings.more());
        }
        return "browse";
    }

    /** The child rows under a prefix, fetched when a folder is expanded in place, so the tree loads a level at a time.
     *  Carries the current sort and the page's own prefix ({@code base}), so children keep the order and indent one
     *  step per level below the page. */
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
     * The level's entries as the shared {@code base :: browseRows} fragment draws them: a folder links back into this
     * browse and offers its children, a leaf links to the artifact detail.
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

    /** A coordinate's own page, whichever way its format stores: the one place a blobs-namespace package (npm, PyPI,
     *  NuGet), which has no folder in the tree, opens from a search hit or a release row. Versions are paged by
     *  cursor. */
    @GetMapping("/ui/repositories/{repo}/coordinate")
    public String coordinate(@PathVariable("repo") String repo,
                             @RequestParam("ecosystem") String ecosystem,
                             @RequestParam("coordinate") String coordinate,
                             @RequestParam(name = "after", defaultValue = "") String after,
                             Model model) throws IOException {
        RepositoryBrowse.CoordinateDetail detail = browse.coordinate(repo, ecosystem, coordinate,
                after.isBlank() ? null : after, RepositoryBrowse.VERSIONS_PAGE);
        model.addAttribute("repo", repo);
        model.addAttribute("detail", detail);
        model.addAttribute("mark", ecosystemMark(ecosystem));
        downloads(model);
        return "coordinate";
    }

    /** One version's own page: everything the repository records about it, from what its manifest says and the
     *  licences it declares to its signature, provenance, dependencies and files - reached from its package's page. A
     *  version the repository holds no document for is a {@code 404}. */
    @GetMapping("/ui/repositories/{repo}/version")
    public String version(@PathVariable("repo") String repo,
                          @RequestParam("ecosystem") String ecosystem,
                          @RequestParam("coordinate") String coordinate,
                          @RequestParam("version") String version,
                          Model model) throws IOException {
        RepositoryBrowse.VersionDetail detail = browse.version(repo, ecosystem, coordinate, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        coordinate + " " + version + " is not held in " + repo));
        model.addAttribute("repo", repo);
        model.addAttribute("detail", detail);
        model.addAttribute("mark", ecosystemMark(ecosystem));
        downloads(model);
        return "version";
    }

    /**
     * What the page may say about downloads: nothing while tracking is off, since zero would read as a fact, and
     * otherwise the count with how far behind it can be, the tracker's flush interval.
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

    /** The detail of one published artifact - checksum, size, coordinate and version, other versions, any gate verdict,
     *  provenance and origin - from small objects, never the body. Reached from a browse leaf. */
    @GetMapping("/ui/repositories/{repo}/artifact")
    public String artifact(@PathVariable("repo") String repo,
                           @RequestParam(name = "path", defaultValue = "") String path,
                           Model model) throws IOException {
        RepositoryBrowse.ArtifactDetail detail = browse.artifact(repo, path);
        model.addAttribute("repo", repo);
        model.addAttribute("detail", detail);
        // The API names an artifact by the path a client uses within the repository, so its links do too.
        model.addAttribute("servedPath", repositories.servedPath(repo, detail.path()));
        // Where these bytes came from (uploaded, or through which fallback), empty when nothing was recorded.
        model.addAttribute("origin", browse.origin(repo, detail.path()));
        downloads(model);
        // The folder the back link returns to; parent() guards a path with no slash.
        model.addAttribute("parent", parent(detail.path()));
        return "artifact";
    }

    /**
     * The origin rows of a published artifact path as JSON, the machine-readable twin of the detail page's origin panel,
     * from the one small section {@link RepositoryBrowse#origin} reads. Gated as every repository read is; empty when
     * nothing was recorded.
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
        } catch (IllegalArgumentException | JobState.Dismissed | JobState.Running refused) {
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

    /** Retires one unplaceable ecosystem's records, an absent format's data. Refused while an installed format places
     *  the ecosystem; the flash line carries the refusal. */
    @PostMapping("/ui/repositories/{repo}/forget-ecosystem")
    public String forgetEcosystem(@PathVariable("repo") String repo,
                                  @RequestParam("ecosystem") String ecosystem,
                                  RedirectAttributes redirect) throws IOException {
        try {
            // Started, not awaited; the refusal is synchronous, being the operator's mistake.
            redirect.addFlashAttribute("message", lifecycle.forgetEcosystem(repo, ecosystem)
                    ? "Retiring ecosystem " + ecosystem + "; this screen shows what it removed when it finishes."
                    : "A retirement of " + ecosystem + " is already running.");
        } catch (IllegalStateException stillPlaced) {
            redirect.addFlashAttribute("message", stillPlaced.getMessage());
        }
        return "redirect:/ui/repositories/" + repo;
    }

    /** Pin a version - from the Pins page's form, or from the version's own row on a coordinate or artifact page,
     *  which names itself in {@code returnTo} so the reader stays where they pinned. */
    @PostMapping("/ui/repositories/{repo}/pins")
    public String pin(@PathVariable("repo") String repo,
                      @RequestParam("ecosystem") String ecosystem,
                      @RequestParam("coordinate") String coordinate,
                      @RequestParam("version") String version,
                      @RequestParam(name = "returnTo", defaultValue = "") String returnTo,
                      RedirectAttributes redirect) throws IOException {
        lifecycle.pin(repo, ecosystem, coordinate, version);
        redirect.addFlashAttribute("message", "Pinned " + coordinate + ":" + version + ".");
        return "redirect:" + within(repo, returnTo, "/pins");
    }

    @PostMapping("/ui/repositories/{repo}/pins/remove")
    public String unpin(@PathVariable("repo") String repo,
                        @RequestParam("ecosystem") String ecosystem,
                        @RequestParam("coordinate") String coordinate,
                        @RequestParam("version") String version,
                        @RequestParam(name = "returnTo", defaultValue = "") String returnTo,
                        RedirectAttributes redirect) throws IOException {
        lifecycle.unpin(repo, ecosystem, coordinate, version);
        redirect.addFlashAttribute("message", "Unpinned " + coordinate + ":" + version + ".");
        return "redirect:" + within(repo, returnTo, "/pins");
    }

    /** {@code requested} when it is this repository's overview or another page of its own console, else its
     *  {@code fallback} page: a form names where to return, and a return address outside the repository - another
     *  host above all - is not followed. */
    static String within(String repo, String requested, String fallback) {
        String overview = "/ui/repositories/" + repo;
        boolean own = requested.equals(overview)
                || requested.startsWith(overview + "/") && !requested.contains("//") && !requested.contains("\\");
        return own ? requested : overview + fallback;
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

    /** Starts a cleanup sweep; the page shows its stored result. */
    @PostMapping("/ui/repositories/{repo}/cleanup")
    public String cleanup(@PathVariable("repo") String repo, RedirectAttributes redirect) throws IOException {
        // The sweep walks every release, so the request starts it and returns; the hub shows the stored result.
        redirect.addFlashAttribute("message", lifecycle.cleanup(repo)
                ? "Cleanup started; its result appears under Cleanup when it finishes."
                : "A cleanup is already running; its result appears under Cleanup when it finishes.");
        return "redirect:/ui/repositories/" + repo + "/retention";
    }

    /** Starts a cleanup preview; the page shows its stored result. */
    @PostMapping("/ui/repositories/{repo}/cleanup/preview")
    public String previewCleanup(@PathVariable("repo") String repo, RedirectAttributes redirect) throws IOException {
        redirect.addFlashAttribute("message", lifecycle.previewCleanup(repo)
                ? "Cleanup preview started; it appears under Cleanup when it finishes."
                : "A cleanup preview is already running.");
        return "redirect:/ui/repositories/" + repo + "/retention";
    }







    /** A repository as the list renders it: its name, its format ({@code null} for one holding none, which answers
     *  nothing until given one), the marks of what it holds (empty draws the neutral mark), and whether it is a
     *  hardened proxy screening every upstream body in full. */
    public record RepositoryRow(String name, String format, List<Mark> marks, boolean hardened,
                                SettingsAdmin.RepositoryShape shape, String description, String created,
                                boolean removing) {
    }

    /** A valid but risky routing the parse warned about (mixed screening strength, an unscreened or plaintext
     *  upstream), shown as a non-blocking banner, unlike a refused parse. */
    public record RepositoryWarning(String repository, String message) {
    }

    /** A browse search hit with the mark of the format owning its ecosystem ({@code null} for none, drawn neutral). */
    public record SearchRow(String display, String coordinate, String version, String ecosystem, String location,
                            Mark mark) {
    }

    /** A repository's retention rendered for the form: durations as ISO-8601 text, blank when a dial is unset. */
    public record RetentionView(int keepLast, String maxAge, String prereleaseExpiry, String notDownloadedFor) {
    }

    /** One breadcrumb of the browse trail; the current (last) one renders as plain text. */
    public record Crumb(String label, String prefix, boolean current) {
    }

    /** One crumb per accumulated path segment, the last current; the view supplies the trail's fixed head, so this is
     *  empty at the browse root. */
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
