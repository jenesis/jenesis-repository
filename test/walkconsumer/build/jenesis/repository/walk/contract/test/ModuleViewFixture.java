package build.jenesis.repository.walk.contract.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServedAliases;
import build.jenesis.repository.walk.testkit.WalkConsumerFixture;

import module java.base;

/**
 * The fixture for the first <em>shipped</em> walk consumer: the Maven format's {@code ModuleViewRebuild}, which
 * re-derives the {@code /module/} view of every published modular jar from the durable store.
 *
 * <p>It is the first fixture whose corpus is the free {@code publish/} namespace rather than a format's own
 * blobs-namespace root, and the first whose derived state is itself a <em>serving pointer</em> - a module view has to
 * be servable, so it can live nowhere else. That has one consequence the kit's arithmetic has to be told about rather
 * than have it guessed: the views this consumer writes are themselves retained pointers under the walked root, so one
 * full pass over {@code n} modular jars delivers {@code 2n + 1} pointers, not {@code n}. The order is what makes that
 * count stable rather than racy - {@code publish/maven} sorts before {@code publish/module}, so every view is written
 * before the walk lists the subtree it lands in, and a pass over an unconverged corpus therefore delivers exactly as
 * many pointers as a pass over a converged one.
 *
 * <p>The {@code + 1} is a first-hand Jenesis publish: a {@code /module/} pointer with no Maven jar behind it, seeded
 * because that is the shape the consumer must never touch (deleting it would be an orphan purge over another format's
 * artifacts), and because it makes the {@code publish/module} subtree exist from the pass's first listing - without it
 * a first pass would not enumerate the views it had just written and a second one would, so the delivery count would
 * depend on which pass you asked. It is deliberately <em>outside</em> {@link #projection}: the projection is this
 * consumer's own derived state, and a pointer another format published is not that. Keeping it in would also make the
 * kit's "before its first pass the projection is empty" check vacuous. That it survives untouched is asserted where
 * the rest of the repair's refusals are, in {@code MavenCrossPublishSequenceTest}.
 */
final class ModuleViewFixture implements WalkConsumerFixture {

    /** The module-view repair's key space - the view provider's own pointers, which is where a servable view lives. */
    private static final String SPACE = "publish/module";

    /** The first-hand Jenesis publish the corpus seeds: in the key space, never in the projection (see above). */
    private static final String FIRST_HAND = "/module/kit.direct/1.0/kit.direct.jar";

    @Override
    public String consumer() {
        return "module-view";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.maven.ModuleViewRebuild";
    }

    @Override
    public List<String> pointerRoots() {
        return List.of("publish");
    }

    @Override
    public List<String> namespaces() {
        // Two, because a rebuilt view is two facts. SPACE is the pointer the view serves from; the alias record is
        // the statement that this /module/ path and the Maven coordinate it was cross-published from are ONE
        // artifact under two names - which is what lets a reviewer's release lift both. A rebuild that restored the
        // pointer and not the relation would leave a view that serves and a release that still strands it, so the
        // repair owns both or it does not repair the view.
        return List.of(SPACE, ServedAliases.NAMESPACE);
    }

    @Override
    public Delivery delivery() {
        // The view write completes inside onRetained, before it returns: the cursor can only ever be behind the
        // derived state, so every crash point converges on the resume.
        return Delivery.PER_ITEM_DURABLE;
    }

    @Override
    public Corpus seed(ArtifactStore store, int artifacts) throws IOException {
        Map<String, String> converged = new HashMap<>();
        // A module view published first-hand by the Jenesis format: not derived from any Maven coordinate, so the
        // repair must leave it exactly as it is - and it is what makes publish/module a subtree from the start.
        link(store, "publish" + FIRST_HAND,
                store.writeBlob(new ByteArrayInputStream("a first-hand module publish".getBytes(UTF_8))));
        for (int index = 0; index < artifacts; index++) {
            // A Maven publish whose cross-publish never happened: the coordinate is linked and the module view is
            // missing, which is exactly the residue MavenFormat.layout leaves when a step after the commit point fails
            // - and exactly what a repository whose jars predate any ModuleView provider looks like.
            String module = "kit.artifact" + index;
            String hash = link(store, "publish/maven/kit/artifact" + index + "/1.0/artifact" + index + "-1.0.jar",
                    store.writeBlob(new ByteArrayInputStream(modularJar(module))));
            converged.put("/module/" + module + "/1.0/" + module + ".jar", hash);
        }
        // A leaf that names no hash is metadata and is never delivered, so it is in neither count.
        store.writeVersioned("publish/maven/kit/notes", "2026-08-18T00:00:00Z rebuild".getBytes(UTF_8), null);
        return new Corpus(2 * artifacts + 1, converged);
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        Map<String, String> views = new HashMap<>();
        collect(store, SPACE, views);
        return views;
    }

    /** Every module-view pointer in the store, keyed by the request path it serves - read back out of the store, so
     *  the projection is a statement about what a client would resolve rather than about what the consumer remembers. */
    private static void collect(ArtifactStore store, String prefix, Map<String, String> views) throws IOException {
        List<String> children = store.list(prefix);
        if (children.isEmpty()) {
            String body = KitCorpus.text(store, prefix);
            String path = prefix.substring("publish".length());
            if (body != null && !path.equals(FIRST_HAND)) {
                views.put(path, body);
            }
            return;
        }
        for (String child : children) {
            collect(store, prefix + "/" + child, views);
        }
    }

    private static String link(ArtifactStore store, String key, String hash) throws IOException {
        store.writeVersioned(key, hash.getBytes(UTF_8), null);
        return hash;
    }

    /** A jar declaring a module name, the way the layout reads one back out of a stored blob. */
    private static byte[] modularJar(String module) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", module);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.flush();
        }
        return bytes.toByteArray();
    }

    private static final Charset UTF_8 = StandardCharsets.UTF_8;
}
