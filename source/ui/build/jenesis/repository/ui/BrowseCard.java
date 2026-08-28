package build.jenesis.repository.ui;

import build.jenesis.repository.format.FormatMarks;
import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.ScreenedNames;

import module java.base;

/**
 * The bundled browse card: the console's entry point into the generic artifact browse ({@link BrowseController} at
 * {@code /browse}). It links into the breadcrumbed lazy tree and previews the repository's top-level published
 * namespaces (the formats' request-path roots) as quick links, so the console is usable out of the box and a
 * plugged-in card has a worked example to copy. It reads only the first level of the {@code publish/} pointer tree
 * through the {@link ArtifactStore} - never an artifact blob.
 *
 * <p>Each quick link carries the mark of the format that owns its namespace, resolved through the shared
 * {@link Marks} the whole product renders contributor marks with, and every one of the three answers is reachable
 * here from real store data. A namespace whose format ships a mark shows it. A namespace whose format ships none -
 * which is every format this repository bundles today - shows that format's generated figure, so the row still
 * attributes something instead of repeating one neutral box. And a namespace <b>no installed format claims at all</b>
 * shows the orphan figure, dashed: that is a repository still holding artifacts a format module used to serve, which
 * the console previously rendered as an ordinary row saying nothing about why nothing serves it.
 *
 * <p>The mark is the one value on the overview page rendered unescaped, and the reason it may be is that it is built
 * from module constants and from geometry - never from a name - so nothing repository-derived reaches the page
 * through it. The namespace name beside it is ordinary text the template escapes, and the link to it is built by
 * {@code th:href}, which percent-encodes the query parameter; both used to be this class's own job.
 */
public final class BrowseCard implements ConsoleCard {

    /** How many top-level namespace quick links the card renders (and examines) - a format's request-path root, so a
     *  realistic store has a handful; the cap is what keeps a render bounded regardless. */
    private static final int NAMESPACES = 1_000;

    private final FormatMarks marks;

    /** The discovered constructor: format discovery is static for the life of the JVM and a mark is a constant in
     *  its format's module, so the lookup is resolved once here rather than per render. */
    public BrowseCard() {
        this(FormatMarks.installed());
    }

    /** For a caller that supplies the format lookup directly (a test, or a deployment contributing the card as a
     *  bean) rather than through discovery. */
    public BrowseCard(FormatMarks marks) {
        this.marks = Objects.requireNonNull(marks, "marks");
    }

    @Override
    public String id() {
        return "browse";
    }

    @Override
    public String title() {
        return "Browse";
    }

    @Override
    public String fragment() {
        return "console/cards :: browse";
    }

    @Override
    public View model(ArtifactStore store) throws IOException {
        // The top-level namespaces come from the shared screened enumeration, so this card lists exactly what the
        // browse it links into would show. Every root child is a namespace directory (a container), so it forwards on
        // its own listing's screen rather than being screened as a leaf; the reserved publish/quarantine review subtree
        // is suppressed by the seam itself - it is not a browsable namespace (a GET withholds it and the /assets export
        // never walks it). Bounded: a quick-link row is a namespace, and a store with more than the cap has an
        // operator problem the card must not turn into an unbounded read.
        List<String> names = new ArrayList<>();
        ScreenedNames.paths(new ServableNames(store), ServableNames.Policy.HIDE_WITHHELD_AND_GONE)
                .containers(_ -> true)
                .scanning(BoundedChildren.bounded().entries(NAMESPACES).page(NAMESPACES))
                .scan(store, ServableNames.PUBLISHED, (name, _) -> names.add(name));
        List<Namespace> namespaces = new ArrayList<>(names.size());
        for (String name : names) {
            // A namespace this deployment has no format for is an orphan, not a hole: the artifacts are still
            // stored and still listed, and the dashed mark plus the "not installed" note is what says that the
            // format module that served them is gone. Only this caller can tell that from a bookkeeping bucket,
            // which is why FormatMarks answers empty rather than deciding - and the browse listing it walks has
            // already screened the reserved plumbing out, so what reaches here is published content.
            Mark mark = marks.forNamespace(name).orElseGet(() -> Marks.orphaned(name));
            namespaces.add(new Namespace(name, mark.svg(), mark.title(), mark.installed()));
        }
        return new View(List.copyOf(namespaces));
    }

    /** What the fragment renders: the namespaces, in the order the screened enumeration produced them. */
    public record View(List<Namespace> namespaces) {
    }

    /**
     * One quick link. {@code markSvg} is the only field the template renders unescaped, and it is inline SVG built
     * from constants and geometry rather than from {@code name} - see this class's note on why that is safe.
     */
    public record Namespace(String name, String markSvg, String markTitle, boolean installed) {
    }
}
