package build.jenesis.repository.format.pypi;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.format.Listings;

/**
 * The PEP 503 Simple pages as stored listings: a page per project whose entries are its files (a link each with its
 * SHA-256 fragment and its {@code data-yanked} from the lifecycle mark), and the root page of listed projects. A file
 * is listed when its pointer is not withheld, a project when it has a servable file or no files at all.
 */
final class PyPiListings {

    private final Blobs blobs;
    private final ArtifactStore store;

    PyPiListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String root() {
        return "pypi/simple.html";
    }

    static String project(String project) {
        return "pypi/" + project + "/simple.html";
    }

    static StoredListing.Codec page(String title) {
        return StoredListing.framed("<!DOCTYPE html><html><head><title>" + Listings.html(title) + "</title></head><body>\n",
                "</body></html>", Listings.ANCHORS);
    }

    StoredListing.Spec rootSpec() {
        return StoredListing.Spec.of(root(), page("Simple index"), this::generateRoot);
    }

    /** The stride the index is enumerated in. It drains: the Simple index names every project, so neither names nor
     *  round-trips are capped - a cap would omit projects or throw and never materialise the document - and only the
     *  names in hand are bounded. */
    private static final BoundedChildren PROJECTS = BoundedChildren.draining();

    StoredListing.Spec projectSpec(String project) {
        return StoredListing.Spec.materialising(project(project), page(project), () -> generateProject(project));
    }

    /** Emit a link per servable project in the scan's order, which is the order the sink requires:
     *  {@code BoundedChildren} delivers children in the store's lexicographic order, as every backend does natively and
     *  the store contract kit proves. Nothing is collected, since the index is every project in the repository. */
    private void generateRoot(StoredListing.Generator.Sink sink) throws IOException {
        PROJECTS.scan(store, "pypi", project -> {
            if (PyPiFormat.servable(project, blobs)) {
                sink.accept(project, projectLink(project));
            }
        });
    }

    private SortedMap<String, byte[]> generateProject(String project) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        Map<String, Lifecycle.Flag> marks = Lifecycle.versions(store, project);
        for (String file : blobs.list("pypi/" + project + "/files")) {
            byte[] link = fileLink(project, file, marks);
            if (link != null) {
                entries.put(file, link);
            }
        }
        return entries;
    }

    private static byte[] projectLink(String project) {
        return ("<a href=\"" + Listings.html(project) + "/\">" + Listings.html(project) + "</a><br/>")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The link of a servable file, or {@code null} when its pointer is withheld or gone. */
    private byte[] fileLink(String project, String file, Map<String, Lifecycle.Flag> marks) throws IOException {
        String key = "pypi/" + project + "/files/" + file;
        if (blobs.withheld(key)) {
            return null;
        }
        Optional<String> hash = blobs.hash(key);
        if (hash.isEmpty()) {
            return null;
        }
        StringBuilder link = new StringBuilder("<a href=\"").append(Listings.html(file)).append("#sha256=")
                .append(hash.get()).append('"');
        Lifecycle.Flag flag = PyPiFormat.marked(marks, project, file);
        if (flag != null) {
            link.append(" data-yanked=\"").append(Listings.html(flag.message() == null ? "" : flag.message())).append('"');
        }
        // PEP 740: a file with attestations names its provenance document, relative to the page.
        if (blobs.exists(PyPiFormat.attestationsKey(project, file))) {
            Optional<String> version = new PyPiFormat().describe("/pypi/simple/" + project + "/" + file)
                    .map(described -> described.version());
            if (version.isPresent()) {
                link.append(" data-provenance=\"../../integrity/").append(Listings.html(project)).append('/')
                        .append(Listings.html(version.get())).append('/').append(Listings.html(file))
                        .append("/provenance\"");
            }
        }
        return link.append('>').append(Listings.html(file)).append("</a><br/>").toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Regenerate the listing at this key if it is a PyPI Simple page. */
    boolean rebuild(String listing) throws IOException {
        if (listing.equals(root())) {
            StoredListing.rebuild(store, rootSpec());
            return true;
        }
        String[] segments = listing.split("/");
        if (segments.length == 3 && segments[0].equals("pypi") && segments[2].equals("simple.html")) {
            StoredListing.rebuild(store, projectSpec(segments[1]));
            return true;
        }
        return false;
    }

    /** Re-decide one file's entry and its project's from the store's current state. */
    void refresh(String project, String file) throws IOException {
        byte[] link = fileLink(project, file, Lifecycle.versions(store, project));
        if (link == null) {
            StoredListing.remove(store, projectSpec(project), file);
        } else {
            StoredListing.put(store, projectSpec(project), file, link);
        }
        if (PyPiFormat.servable(project, blobs)) {
            StoredListing.put(store, rootSpec(), project, projectLink(project));
        } else {
            StoredListing.remove(store, rootSpec(), project);
        }
    }

}
