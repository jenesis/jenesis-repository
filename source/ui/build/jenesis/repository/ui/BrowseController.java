package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.PublishedAssets;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.walk.Traversal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The generic artifact browse: a breadcrumbed, lazy tree over any repository's published namespace, read through the
 * {@link ArtifactStore} listing seam (the framework-neutral "inventory" primitive - prefix listing, one level at a
 * time) so it is generic across every format. The tree is rooted at the {@code publish/} pointer tree the formats
 * write, so a browse walks the logical request paths ({@code maven/org/apache/…}), not the content-addressed
 * {@code blobs/} bucket. Each level lists only its immediate children ({@link ArtifactStore#page}); a folder's
 * children are fetched only when it is navigated into or expanded, so a browse never scans or buffers a whole tree,
 * and never reads an artifact blob - only the tiny publish pointer (its content is the blob hash) and the blob's
 * stored size feed the size column.
 *
 * <p>This lives in the free base so both consoles share one browse. It is deny-by-default authenticated (a GET
 * caught by {@code anyRequest().authenticated()}), and the {@code path} query parameter is traversal-guarded - any
 * {@code .}/{@code ..}/empty segment is dropped - so a request can never escape the {@code publish/} subtree to read
 * {@code blobs/} or a sibling's data. The reserved {@code publish/quarantine/} review subtree - artifacts the gate is
 * withholding, which a plain {@code GET} 404s and the {@code /assets} export never walks - is likewise excluded from
 * both the root listing and navigation, so the browse discloses exactly what a {@code GET} would.
 */
@Controller
public class BrowseController {

    /** The store subtree the browse is rooted at: the formats' published request-path pointer tree. */
    private static final String ROOT = "publish";

    private final ArtifactStore store;
    private final Publication publication;
    private final PublishedAssets assets;
    private final ServableNames names;

    public BrowseController(ArtifactStore store) {
        this.store = store;
        this.publication = new Publication(store);
        this.assets = new PublishedAssets(store, publication);
        this.names = new ServableNames(store, publication);
    }

    /** The full browse page: the breadcrumb trail to {@code path} and the immediate children under it. */
    @GetMapping("/browse")
    public String browse(@RequestParam(name = "path", defaultValue = "") String path, Model model) throws IOException {
        String safe = sanitize(path);
        Listing listing = children(safe);
        model.addAttribute("path", safe);
        model.addAttribute("entries", listing.entries());
        model.addAttribute("truncated", listing.truncated());
        model.addAttribute("cap", MAX_CHILDREN);
        model.addAttribute("crumbs", crumbs(safe));
        model.addAttribute("hasParent", !safe.isEmpty());
        model.addAttribute("parent", parent(safe));
        return "browse";
    }

    /** The lazy-children fragment: just the child rows under {@code path}, fetched on demand when a folder expands. */
    @GetMapping("/browse/children")
    public String children(@RequestParam(name = "path", defaultValue = "") String path, Model model) throws IOException {
        Listing listing = children(sanitize(path));
        model.addAttribute("entries", listing.entries());
        model.addAttribute("truncated", listing.truncated());
        model.addAttribute("cap", MAX_CHILDREN);
        return "browse :: rows";
    }

    /**
     * The console face of the free {@code GET /api/assets} enumeration: a downloadable, streamed export of every
     * published asset in the repository as NDJSON (one {@code {"path","size","sha256"}} object per line), the outbound
     * mirror of the import connectors so getting your data out is never an afterthought. It walks the {@code publish/}
     * pointer tree through the shared {@link PublishedAssets} walk the server's {@code /api/assets} catalogue also uses
     * - reading only the tiny publication pointer (its content <em>is</em> the blob hash) and the blob's stored size,
     * never an artifact blob - and writes each entry as it is reached, so an arbitrarily large repository exports
     * without buffering the tree. A path the store withholds (a retracted or quarantined artifact) is skipped and the
     * {@code /quarantine} review subtree is never walked (both are the shared walk's own guarantees), so the export
     * serves exactly what a {@code GET} would. It is deny-by-default authenticated like the browse (a GET any signed-in
     * user may take); the coordinate enrichment {@code /api/assets} adds needs the owning format, which this store-only
     * console does not carry, so the export carries the format-neutral pointer facts the walk emits, as NDJSON.
     */
    @GetMapping("/assets")
    public void assets(@RequestParam(name = "cursor", required = false) String cursor,
                       @RequestParam(name = "limit", required = false) String limit,
                       HttpServletResponse response) throws IOException {
        // One slice per request, never the whole repository in one walk: at most `limit` entries (the export's
        // default, capped), and when more remain a last line carrying the cursor to ask for the next slice with -
        // the walk resumes strictly past the last path emitted, so a very large repository exports as a sequence
        // of bounded requests and a single request cannot be made to walk it whole.
        int cap = slice(limit);
        String after = cursor == null || cursor.isBlank() ? null : cursor;
        response.setHeader("Content-Type", "application/x-ndjson");
        response.setHeader("Content-Disposition", "attachment; filename=\"assets.ndjson\"");
        try (Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            String[] last = {null};
            int[] emitted = {0};
            assets.walk(after, cap + 1, entry -> {
                if (emitted[0]++ < cap) {
                    emit(entry, out);
                    last[0] = entry.path();
                } else {
                    out.write("{\"cursor\":\"" + jsonEscape(relative(last[0])) + "\"}\n");
                }
            });
        }
    }

    /** The most entries one export request emits; a request asks for fewer, never for more. */
    static final int EXPORT_SLICE = 10_000;

    private static int slice(String limit) {
        if (limit == null || limit.isBlank()) {
            return EXPORT_SLICE;
        }
        try {
            return Math.clamp(Integer.parseInt(limit.trim()), 1, EXPORT_SLICE);
        } catch (NumberFormatException _) {
            return EXPORT_SLICE;
        }
    }

    /** The walk's cursor form of an emitted path: relative, no leading slash. */
    private static String relative(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /** Render one walked pointer as an NDJSON object; the domain walk already skipped withheld pointers and the
     *  quarantine subtree, so this is pure presentation - the one thing that legitimately lives in the controller. */
    private static void emit(PublishedAssets.Entry entry, Writer out) throws IOException {
        out.write("{\"path\":\"" + jsonEscape(entry.path()) + "\",\"size\":" + entry.size()
                + ",\"sha256\":\"" + jsonEscape(entry.sha256()) + "\"}\n");
    }

    /** Minimal JSON string escaping for the two fields the export carries - a request path and a hex digest - so a path
     *  segment carrying a quote, backslash or control character stays valid NDJSON. */
    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /** A page of immediate children under a browse path plus whether the directory held more than the render cap. */
    private record Listing(List<BrowseRow> entries, boolean truncated) {
    }

    /** A browse link with the path as a properly encoded query parameter - the row carries its own links, so this is
     *  where the encoding happens rather than in the template that used to build each href with {@code @{}}. */
    private static String link(String base, String path) {
        return UriComponentsBuilder.fromPath(base).queryParam("path", path).toUriString();
    }

    /** The most immediate children a single browse renders. A directory with an enormous fan-out (a repo with a
     *  million top-level packages, or a coordinate with hundreds of thousands of timestamped versions) is navigated
     *  into, not scrolled, so the console caps what it materialises rather than building - and rendering - the whole
     *  set in heap. */
    private static final int MAX_CHILDREN = 1000;

    /** How many stored children one browse request may examine to fill its {@value #MAX_CHILDREN} rows. The render cap
     *  alone does not bound the work when most children are screened away (a coordinate whose versions are all held),
     *  so the scan is capped too; reaching either cap flags the listing truncated rather than passing an incomplete
     *  page off as the whole directory. */
    private static final int CHILD_SCAN = 50_000;

    /** The immediate children under a (sanitized) browse path, each classified folder-vs-artifact with a size - paged
     *  and capped so a high-fan-out directory can never materialise a millions-entry {@code List} (or fire a store
     *  round-trip per child) and OOM the console. */
    private Listing children(String path) throws IOException {
        String prefix = path.isEmpty() ? ROOT : ROOT + "/" + path;
        int depth = path.isEmpty() ? 1 : path.split("/").length + 1;
        List<BrowseRow> entries = new ArrayList<>();
        // The browse is produced by the shared screened enumeration: ScreenedNames pages the immediate children AND
        // applies the servable-name screen under HIDE_WITHHELD_AND_GONE (published, blob present, not withheld) in one
        // call, so this controller never holds an unscreened child name and cannot page-then-forget the screen. It is
        // the same serve-parity screen the raw listing and the /assets export apply, through the one seam, so the
        // browse can never disagree with a GET on what is held: a retracted or quarantined artifact, or a pointer whose
        // blob a garbage collection reclaimed, is never leaked by name or tree position. A sub-directory is declared a
        // container and kept unconditionally (it is a listing, not a servable leaf; its own leaves carry the screen),
        // and the reserved review subtree is suppressed at the served root by the seam itself.
        //
        // The caps are the primitive's: at most MAX_CHILDREN rendered rows and at most CHILD_SCAN examined children per
        // request, so a directory with a million entries - or a million withheld ones, where a render cap alone would
        // never fire - can neither be materialised nor turned into an unbounded probe storm. Truncation is the
        // primitive's own outcome, so the flag says exactly "there is more past this page", and a screened-out leaf can
        // never make an incomplete listing look complete.
        Traversal.Result scanned = ScreenedNames.paths(names, ServableNames.Policy.HIDE_WITHHELD_AND_GONE)
                .containers(this::hasChild)
                .scanning(BoundedChildren.bounded().entries(CHILD_SCAN).page(MAX_CHILDREN + 1))
                .take(MAX_CHILDREN)
                .scan(store, prefix, (name, folder) -> {
                    String childPath = path.isEmpty() ? name : path + "/" + name;
                    String size = "—";
                    if (!folder) {
                        // Race-tolerant follow-up read: the screen and this pointer read are two round-trips, and a
                        // concurrent unpublish can remove the pointer between them. A leaf that vanished after it
                        // screened servable is dropped rather than rendered with a phantom size.
                        Optional<String> located = publication.located("/" + childPath);
                        if (located.isEmpty()) {
                            return;
                        }
                        long bytes = store.size(located.get());
                        size = bytes < 0 ? "—" : humanSize(bytes);
                    }
                    // The row carries its own links, because that is the only thing a repository-scoped browse
                    // draws differently. A leaf here is text rather than a link: this console has no artifact
                    // detail page to send a reader to.
                    entries.add(new BrowseRow(name, folder, size, depth,
                            folder ? link("/browse", childPath) : null,
                            folder ? link("/browse/children", childPath) : null));
                });
        return new Listing(entries, scanned.truncated());
    }

    /** Whether a prefix has at least one immediate child, tested with a bounded one-element page rather than listing
     *  (and discarding) the child's entire subtree just to check emptiness - so classifying a child as a folder is a
     *  single seek, not O(its own child count) round-trips (the old {@code list(...).isEmpty()} was a full subtree
     *  scan per child, quadratic across a large directory). */
    private boolean hasChild(String prefix) {
        boolean[] any = {false};
        store.page(prefix, "", 1, name -> any[0] = true);
        return any[0];
    }

    /** The breadcrumb trail: a clickable root plus one crumb per accumulated path segment (the last is the current). */
    private List<Map<String, String>> crumbs(String path) {
        List<Map<String, String>> crumbs = new ArrayList<>();
        crumbs.add(crumb("Repository", path.isEmpty() ? null : "/browse"));
        if (!path.isEmpty()) {
            String[] segments = path.split("/");
            StringBuilder accumulated = new StringBuilder();
            for (int index = 0; index < segments.length; index++) {
                if (index > 0) {
                    accumulated.append('/');
                }
                accumulated.append(segments[index]);
                boolean last = index == segments.length - 1;
                crumbs.add(crumb(segments[index], last ? null : "/browse?path=" + accumulated));
            }
        }
        return crumbs;
    }

    private static Map<String, String> crumb(String label, String href) {
        Map<String, String> crumb = new LinkedHashMap<>();
        crumb.put("label", label);
        crumb.put("href", href);
        return crumb;
    }

    /** The parent browse path (empty for a one-segment path, so the up-link returns to the root). */
    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /**
     * Drop every unsafe segment so the resulting path stays strictly under {@code publish/}: an empty, {@code .} or
     * {@code ..} segment, or one carrying a backslash, is removed rather than allowed to walk up out of the subtree.
     */
    private static String sanitize(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        StringBuilder safe = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.indexOf('\\') >= 0) {
                continue;
            }
            if (safe.length() == 0 && ServableNames.reviewSubtree(segment)) {
                // A leading "quarantine" segment would navigate into the withheld-artifact review subtree, whose paths
                // and sizes a GET does not serve; drop it (a deeper "quarantine" is a legitimate artifact-path segment
                // and is kept), so a crafted ?path=quarantine/... cannot enumerate held artifacts.
                continue;
            }
            if (safe.length() > 0) {
                safe.append('/');
            }
            safe.append(segment);
        }
        return safe.toString();
    }

    /** Bytes as a compact human-readable size (the browse size column), binary units, one decimal above a kilobyte. */
    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format("%.1f %s", value, units[unit]);
    }
}
