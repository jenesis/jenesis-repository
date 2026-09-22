package build.jenesis.repository.format.pypi;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.format.Listings;

/**
 * The PEP 503 Simple pages as stored listings: one page per project, whose entries are its distribution files (a link
 * each, with the file's SHA-256 fragment and its {@code data-yanked} attribute from the lifecycle mark), and the root
 * page, whose entries are the listed projects. A file is listed exactly when its pointer is not withheld, and a
 * project exactly when it has a servable file or no files at all - the screens the on-read generation applied.
 */
final class PyPiListings {

    private static final StoredListing.Codec LINKS = StoredListing.Codec.delimited("\n", link -> {
        int start = link.indexOf('>') + 1;
        int end = link.indexOf("</a>");
        return start > 0 && end > start ? unescape(link.substring(start, end)) : "";
    });

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
                "</body></html>", LINKS);
    }

    StoredListing.Spec rootSpec() {
        return StoredListing.Spec.of(root(), page("Simple index"), this::generateRoot);
    }

    /**
     * The stride the index is enumerated in. It <b>drains</b>, because the Simple index names every project by
     * definition, so neither the names nor the round-trips that fetch them may cap it; what is bounded is how many
     * names are in hand at once. Capping either one silently omits projects - or, once the entry cap alone was lifted, stopped
     *  omitting them and started throwing instead, at exactly {@code steps x page} names. That is the ceiling the
     *  OCI tag canary hit at a million: a generator that raises {@code TraversalException} does not answer short,
     *  it never materialises the document at all. */
    private static final BoundedChildren PROJECTS = BoundedChildren.draining();

    StoredListing.Spec projectSpec(String project) {
        return StoredListing.Spec.materialising(project(project), page(project), () -> generateProject(project));
    }

    /**
     * Emit a link per servable project, in the order the scan yields them.
     *
     * <p>This used to collect every project into a sorted map and hand the map over. A Simple index names every
     * project in the repository, so that map was the repository - held whole, on the first read of an index that
     * does not exist yet, which is a request path.
     *
     * <p>The scan's order is the sink's order, which is what the generator contract requires. That is not a
     * coincidence to rely on quietly: the map was where the ordering came from before, and it is gone, so the
     * guarantee now rests on {@code BoundedChildren} delivering children in the store's lexicographic order -
     * which it documents, which every shipped backend implements natively, and which the store contract kit's
     * native-paging property proves for each of them.
     */
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

    /** The link of a servable file, or {@code null} when the file is not served (its pointer withheld or gone). */
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
        // PEP 740: a file uploaded with attestations names its provenance document, relative to the project's page.
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

    /** Re-decide one file's entry (and its project's) from the store's current state - after an upload, a hold, a
     *  release or a mark. */
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


    static String unescape(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }
}
