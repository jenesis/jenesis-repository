package build.jenesis.repository.format.nuget;

import module java.base;
import module java.xml;
import module tools.jackson.databind;

import build.jenesis.repository.blobs.RequestBase;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.multipart.MultipartBody;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.Withheld;
import build.jenesis.repository.format.Semver;

/**
 * The NuGet v3 format, so {@code dotnet nuget push} and {@code dotnet restore} work over the same store. It owns
 * {@code /nuget/...}: the service index ({@code GET /nuget/v3/index.json}) advertises the package-base-address (flat
 * container) and the publish endpoint, their absolute URLs built from the request so the {@code /<repo>/} segment
 * survives multi-tenant routing. A push ({@code PUT /nuget/v3/package}, a multipart {@code .nupkg}) reads the
 * package id and version from the {@code .nuspec} inside the archive and stores it under
 * {@code nuget/<id>/<version>/}; the flat container enumerates a package's versions and serves each {@code .nupkg}.
 * The multipart body is read through the shared streaming reader ({@link build.jenesis.repository.multipart.MultipartBody},
 * the same cursor the PyPI upload walks), so no hand-scan walks the binary {@code .nupkg} bytes; the {@code .nuspec} is
 * read with the JDK XML.
 */
public final class NuGetFormat implements RepositoryFormat, ProxyLeg, BlobLayout, RepositoryImporter, ArtifactSignatures {

    static final JsonMapper JSON = JsonMapper.builder().build();

    // A hostile .nupkg cannot force a large allocation: a .nuspec is small metadata XML, read under the product's one
    // archive-inflation ceiling, ArchiveInflation.largestEntry(), settable at jenreg.archive.largest-entry - the same
    // bound the NuGetQualityInspector applies, because it is now one bound rather than two constants that agreed by
    // convention (RepositoryFormat contract clause 15 /, §13 cross-format parity).

    // How far the walk for the .nuspec may run is the product's one archive-walk bound, ArchiveWalk.largestWalk(),
    // settable at jenreg.archive.largest-walk - not a private constant of this format's, and not a number the
    // compliance inspector mirrors by hand either (RepositoryFormat contract clause 15 /).

    @Override
    public String name() {
        return "nuget";
    }

    /** A lifecycle mark surfaces in the metadata this format's clients read, so marks are accepted here. */
    @Override
    public boolean surfacesLifecycleMarks() {
        return true;
    }

    @Override
    public String ecosystem() {
        return "NuGet";
    }

    /**
     * The coordinate version a stored NuGet pointer serves - the backwards direction the inventory back-fill
     * rebuilds a lost {@code published/} row from.
     *
     * <p>Unusually easy here, because the version is its own path segment: {@code blobKeys} composes
     * {@code nuget/<id>/<version>/<id>.<version>.nupkg}, so the pair is read off the key rather than out of a
     * filename. The trailing filename is not parsed at all - it repeats what the two segments already said.
     *
     * <p><b>The id is lower-cased, and that is the right answer rather than a lossy one.</b> It looked like a
     * hazard: a reverse that lower-cases would rebuild a row under a different case from the one the publish
     * wrote, which is a second row rather than the missing one restored. It does not, because {@link #describe}
     * lower-cases too - so the accept path already records the row under the lower-cased id and this matches what
     * is there. The two must stay in step; that is what the shared round-trip property in the format contract
     * checks, over a really published version.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String marker = "nuget/";
        if (!key.startsWith(marker)) {
            return Optional.empty();
        }
        String[] parts = key.substring(marker.length()).split("/");
        if (parts.length != 3) {
            return Optional.empty();     // a package root, a version folder, or something deeper - not a pointer
        }
        String id = parts[0].toLowerCase(Locale.ROOT), version = parts[1];
        if (!BlobLayout.addressable(id, version) || !parts[2].endsWith(".nupkg")) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor(ecosystem(), id, version, key,
                "application/octet-stream", version.contains("-"), null, 0L));
    }

    @Override
    public List<String> blobRoots() {
        return List.of("nuget");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        // The .nupkg pointer at nuget/<id>/<version>/<id>.<version>.nupkg (the id lower-cased as the store keys it) and
        // its precomputed dependency sidecar, so evicting a version reclaims both.
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        String id = coordinate.toLowerCase(Locale.ROOT);
        List<String> keys = new ArrayList<>();
        String key = "nuget/" + id + "/" + version + "/" + id + "." + version + ".nupkg";
        if (store.readVersioned(key).isPresent()) {
            keys.add(key);
        }
        String dependencies = dependenciesKey(id, version);
        if (store.readVersioned(dependencies).isPresent()) {
            keys.add(dependencies);
        }
        return keys;
    }

    /** The request path this package version's {@code .nupkg} serves at
     *  ({@code /nuget/v3-flatcontainer/<id>/<version>/<id>.<version>.nupkg}), the inverse of {@link #describe} - a
     *  retroactive hold links a {@code /quarantine} review handle there. The dependency sidecar is not a served
     *  download and stays out. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        String id = coordinate.toLowerCase(Locale.ROOT);
        String key = "nuget/" + id + "/" + version + "/" + id + "." + version + ".nupkg";
        // The path itself is the store-free derivation below, so the live answer and the "would serve at" answer
        // cannot drift; only the liveness probe is this leg's own.
        return store.readVersioned(key).isPresent() ? servedPaths(coordinate, version) : List.of();
    }

    /**
     * The path this package version serves at, from the coordinate alone - a NuGet {@code .nupkg} is filed under its
     * own id and version, so it is derivable without a store read. That is what lets the gate key a
     * screen-time hold's audit row and held-subject record on the PACKAGE rather than on {@code /nuget/v3/package},
     * the one push endpoint every push shares and the only descriptor the format can build before the {@code .nuspec}
     * inside the body has been read: the reviewer's handle - which {@link #held} re-keys here once the coordinate IS
     * readable - and the reviewer's reasons then name the same path.
     */
    @Override
    public List<String> servedPaths(String coordinate, String version) {
        return BlobLayout.addressable(coordinate, version)
                ? List.of(flatContainerPath(coordinate.toLowerCase(Locale.ROOT), version))
                : List.of();
    }

    /** The coordinate a flat-container request path carries
     *  ({@code /nuget/v3-flatcontainer/<id>/<version>/<file>.nupkg}, the id lower-cased exactly as the store and
     *  {@link #blobKeys} key it - the nuspec's original casing is not derivable from the path), so the inventory
     *  writes the {@code published/} sidecar the retroactive enforcement sweeps enumerate the version by. The
     *  service index, search, registrations and the flat-container version list ({@code .../index.json}) name no
     *  versioned artifact and stay empty, as does the push endpoint (whose coordinate lives in the {@code .nuspec},
     *  not the path) and a non-{@code .nupkg} file like the dependency sidecar. A {@code -} suffix in the version
     *  marks a prerelease, the same convention {@link Semver#compare} ranks by. */
    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith("/nuget/v3-flatcontainer/") || !path.endsWith(".nupkg")) {
            return Optional.empty();
        }
        String after = path.substring("/nuget/v3-flatcontainer/".length());
        int slash = after.indexOf('/');
        int next = slash < 0 ? -1 : after.indexOf('/', slash + 1);
        if (next < 0 || slash == 0 || next == slash + 1) {
            return Optional.empty();
        }
        String id = after.substring(0, slash).toLowerCase(Locale.ROOT);
        String version = after.substring(slash + 1, next);
        return Optional.of(new ArtifactDescriptor("NuGet", id, version, path,
                "application/octet-stream", version.contains("-"), null, -1L));
    }

    // An original CC0 line glyph (a package hexagon with a core) drawn for this project.
    private static final IconResource ICON = IconResource.svg("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2.5 20 7v10l-8 4.5L4 17V7z"/><circle cx="12" cy="12" r="3"/>
            </svg>""");

    @Override
    public Optional<IconResource> icon() {
        return Optional.of(ICON);
    }

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://api.nuget.org/"));
    }

    /** The one resource this format accepts a package push at, relative to {@code /nuget/} - the address
     *  {@link #index} advertises as {@code PackagePublish/2.0.0} and the address {@link #handle} routes a {@code PUT}
     *  to, named once so the two can never offer and accept different endpoints. */
    private static final String PUSH = "v3/package";

    /** The push endpoint as a request path, which is what a descriptor handed to the screen carries. */
    private static final String PUSH_ROUTE = "/nuget/" + PUSH;

    @Override
    public boolean handles(String path) {
        return path.startsWith("/nuget/");
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        Blobs blobs = new Blobs(store);
        String rest = exchange.path().substring("/nuget/".length());
        if (exchange.method().equals("PUT")) {
            // A push goes to the one resource the service index advertises as PackagePublish/2.0.0, and nowhere else
            //. The coordinate comes from the.nuspec rather than from the path, so this branch used to take
            // EVERY PUT under /nuget/ as a package push - one at a mistyped or invented endpoint, and one at a read
            // address like v3/index.json or a flat-container file, all published and all answered 201. None of them
            // is a traversal (those are 404'd above since) and none of them lands a wrong key either, because
            // the layout reads the coordinate out of the package. What it costs is the honest refusal: a client
            // configured against an endpoint this repository never offered is told its push succeeded, and only much
            // later does anyone notice the packages went somewhere nobody was pointed at. An unadvertised address
            // names nothing here, so it is this format's own 404 (contract clause 6).
            if (rest.equals(PUSH) || rest.equals(PUSH + "/")) {
                push(exchange, blobs, store);
            } else {
                exchange.respond(404);
            }
            return;
        }
        if (rest.equals("v3/index.json")) {
            index(exchange);
        } else if (rest.equals("v3/search")) {
            search(blobs, exchange);
        } else if (rest.startsWith("v3/registrations/") && rest.endsWith("/index.json")) {
            registration(rest.substring("v3/registrations/".length(), rest.length() - "/index.json".length()),
                    blobs, exchange);
        } else if (rest.startsWith("v3-flatcontainer/")) {
            String after = rest.substring("v3-flatcontainer/".length());
            if (after.endsWith("/index.json")) {
                versions(after.substring(0, after.length() - "/index.json".length()), blobs, exchange);
            } else {
                int slash = after.indexOf('/');
                int next = slash < 0 ? -1 : after.indexOf('/', slash + 1);
                if (next < 0) {
                    exchange.respond(404);
                } else {
                    serve(after.substring(0, slash), after.substring(slash + 1, next), after.substring(next + 1),
                            blobs, exchange);
                }
            }
        } else {
            exchange.respond(404);
        }
    }

    private void index(FormatExchange exchange) throws IOException {
        String uri = exchange.requestUri();
        String base = RequestBase.of(exchange) + uri.substring(0, uri.length() - "/v3/index.json".length());
        String json = JSON.writeValueAsString(Map.of(
                "version", "3.0.0",
                "resources", List.of(
                        Map.of("@id", base + "/v3-flatcontainer/", "@type", "PackageBaseAddress/3.0.0"),
                        Map.of("@id", base + "/v3/registrations/", "@type", "RegistrationsBaseUrl/3.6.0"),
                        // The official client selects a search service by exact type name from its own short list -
                        // SearchQueryService/Versioned, /3.4.0 and /3.0.0-beta - and a source advertising only the
                        // bare name or /3.0.0-rc is told to have no search service at all, which is how `dotnet
                        // package search` answered this feed until the two the client looks for were added.
                        Map.of("@id", base + "/v3/search", "@type", "SearchQueryService"),
                        Map.of("@id", base + "/v3/search", "@type", "SearchQueryService/3.0.0-beta"),
                        Map.of("@id", base + "/v3/search", "@type", "SearchQueryService/3.0.0-rc"),
                        Map.of("@id", base + "/v3/search", "@type", "SearchQueryService/3.4.0"),
                        Map.of("@id", base + "/" + PUSH, "@type", "PackagePublish/2.0.0"))));
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The republish conflict policy this format hands the hosted-publish operation as <em>data</em>, rather
     * than re-implementing "is this coordinate already taken" beside the layout: {@code OVERWRITE}, last-writer-wins.
     * That is exactly what a NuGet push does today - a {@code .nupkg} pointer lives in the {@code nuget/} blobs
     * namespace, not in {@code publish/}, so the release-immutability edge hook (which reads a
     * {@code publish/<path>} pointer) has never seen it, and a re-push has always silently re-pointed. Note also that a
     * <em>probing</em> mode could not be expressed here even if we wanted one: the operation evaluates the policy
     * before the accepted layout runs, and a NuGet coordinate is only known once the stored {@code .nuspec} has been
     * parsed <em>inside</em> that layout, so there is no pointer key to name up front.
     */
    private static final Publication.Republish REPUBLISH = Publication.Republish.overwrite();

    /**
     * A {@code dotnet nuget push} wraps its artifact in a multipart form, so this format is <b>not</b> edge-screened
     * ((a)): the request body is an <em>envelope</em>, and gating it at the shared single-body edge would hash and
     * assess the multipart while the bytes that later serve are the {@code .nupkg} inside it - a second
     * content-addressed object under a hash no interceptor ever saw, which is {@code RepositoryFormat} clause 14's
     * fail-open direction. The shared edge ({@code ScreenedDispatch}) takes the request body verbatim and offers no
     * seam to unwrap one, so this format is in the {@code screened() == false} case the clause names and screens at its
     * own documented choke point: {@link #push} peels the file part off the envelope while it streams and
     * {@link #publish} drives the shared {@code Publication.commit} - with the <em>discovered</em> interceptor chain
     * and observers - over the package's own bytes.
     *
     * <p>The declaration is about the protocol, not about the shape of one request: a client that {@code PUT}s a bare
     * {@code .nupkg} body (no multipart) goes through the same choke point, so both push shapes screen the package
     * itself and neither reaches {@link #handle} unscreened.
     */
    @Override
    public boolean screened() {
        return false;
    }

    private void push(FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        // A .nupkg is an immutable artifact of unbounded size: stream it straight into the content-addressed store
        // (hash-on-write, never buffered) and only then reopen the stored blob to parse its front (the .nuspec that
        // names it), the store-then-reopen pattern the gems push uses. There is no size cap: a multi-gigabyte package
        // that no heap could hold still publishes, because no step ever materialises the body as a byte[].
        String contentType = exchange.requestHeader("Content-Type");
        if (contentType != null && contentType.contains("multipart/form-data")) {
            Optional<String> boundary = MultipartBody.boundary(contentType);
            if (boundary.isEmpty()) {
                exchange.respond(400);   // no boundary
                return;
            }
            // The first part carrying a filename is the uploaded .nupkg, read through the shared streaming reader
            // (build.jenesis.repository.multipart - the same cursor the PyPI upload walks), which bounds the part to
            // the next boundary so the store reads exactly the package's bytes without a hand-scan of the binary body.
            //
            // The unwrap (a) turns on: the envelope is peeled here, at the format's own choke point, and the part
            // - the .nupkg itself - is what reaches the screen. Nothing content-addresses the multipart any more (this
            // format opts out of the single-body edge, see screened()), so there is no second CAS object and no hash an
            // interceptor never saw: the package is stored exactly once and the accepted hash is the package's own. The
            // part stream stays open across the commit because the layout reopens the STORED blob, not the socket. It is
            // never bounded: a multi-gigabyte package still publishes because no step ever materialises it.
            Optional<MultipartBody.Part> file =
                    MultipartBody.over(exchange.requestStream(), boundary.get()).nextFile();
            if (file.isEmpty()) {
                exchange.respond(400);   // a multipart body with no file part
                return;
            }
            try (InputStream part = file.get().stream()) {
                publish(part, exchange, blobs, store);
            }
        } else {
            // A bare .nupkg body (a client that PUTs the package directly, not as multipart form-data).
            publish(exchange.requestStream(), exchange, blobs, store);
        }
    }

    /**
     * The hosted publish, run through the one shared choreography ({@code Publication.commit}) rather than
     * hand-assembled here: the package is stored content-addressed, the accepted layout parses the {@code .nuspec} and
     * writes the dependency sidecar, and only then does the operation link the serving pointer and stamp the hosted
     * marker. <b>The commit point is the {@code .nupkg} pointer link</b> - before it nothing serves and the flat
     * container answers a miss; after it the package is downloadable and its registration reads a precomputed sidecar.
     *
     * <p>This is the ordering fix owns: the former code linked the {@code.nupkg} pointer <em>first</em> and
     * only then wrote {@code dependencies.json} and {@code nuget/.hosted}, so a crash in between left a servable
     * package whose registration read had to re-crack the archive, and a version index that had not yet been switched
     * on. Now every parse result lands before anything serves.
     *
     * <p><b>This is the format's screening choke point</b> ((a)). Because {@link #screened()} is {@code false} the
     * shared ingress edge dispatches the push straight here, so the operation is constructed with the
     * <em>discovered</em> interceptor chain and observer list rather than two empty ones: the one screen runs here, over
     * the {@code .nupkg}'s own bytes, and the one after-commit notification fires here once the package is visible.
     * That is the whole of the change - the choreography, the ordering and the layout below are unchanged; only the
     * bytes the chain sees are now the package's rather than the envelope's.
     *
     * <p>The descriptor handed to the screen carries the push path and no coordinate, because a NuGet coordinate lives
     * in the {@code .nuspec} <em>inside</em> the package and is only readable once the body has been stored - which the
     * operation does before it gates. That is not a gap: the NuGet inspector reads the pushed body itself (it sniffs
     * the ZIP magic and streams the {@code .nuspec}), so the coordinate and the declared licence the gate assesses come
     * from the artifact rather than from an envelope field a publisher controls independently of it. Once the layout
     * has parsed it, the real coordinate and the package's own download path are attached with
     * {@link Publication.Visibility#describing}, so the after-commit observers key on the artifact, not the endpoint.
     */
    private void publish(InputStream nupkg, FormatExchange exchange, Blobs blobs, ArtifactStore store)
            throws IOException {
        Publication.Commit commit = new Publication(store).commit(
                ArtifactDescriptor.at("NuGet", exchange.path()), nupkg, REPUBLISH,
                accepted -> {
                    String[] coordinate = parsed(blobs, store, accepted.hash());
                    if (coordinate == null) {
                        // No parseable .nuspec, or a nuspec-supplied id/version that would forge a pointer key with
                        // '/' or '..': nothing servable, so nothing is declared and nothing is linked.
                        return Publication.Visibility.declined();
                    }
                    String id = coordinate[0];
                    String version = coordinate[1];
                    String key = nupkgKey(id, version);
                    return Publication.Visibility
                            // The serving pointer, in this format's own namespace rather than publish/ - so it is
                            // declared through a Serving step, not named with at().
                            .through((hash, _, _) -> blobs.link(key, hash))
                            // Stamp the hosted-publish marker, so a later flat-container version-index read serves the
                            // local versions. A pull-through proxy repository (whose .nupkg is cached by proxy(), never
                            // pushed) never writes it, so its version index misses locally and the pull-through streams
                            // the authoritative upstream version list for an uncached version rather than shadowing it
                            // with only the cached versions. Mirrors the RPM hosted gate. It gates a listing surface,
                            // so it is a visibility write and is declared beside the pointer - after it, never before,
                            // so the index is never switched on ahead of the bytes it would list.
                            .andThrough((_, _, target) -> markHosted(target, HOSTED_KEY))
                            // The served version list, registration index and search record are written here, on
                            // the push, rather than enumerated and screened on every read.
                            .andThrough((_, _, _) -> new NuGetListings(blobs).refresh(id, version))
                            // The push endpoint carries no coordinate, so refine the neutral descriptor the observers
                            // are notified with to the package's own coordinate and download path now that the .nuspec
                            // has been read - the seam Publication.Visibility offers for exactly this shape.
                            .describing(new ArtifactDescriptor("NuGet", id, version, flatContainerPath(id, version),
                                    "application/octet-stream", version.contains("-"), null, -1L));
                });
        switch (commit.disposition()) {
            case ACCEPT -> exchange.respond(commit.visible() ? 201 : 400);
            // The chain HELD the package. Its layout is written all the same, behind the withhold marker (see
            // {@link #held}), so a review release is the marker clear rather than a replay of a push whose multipart
            // envelope no longer exists.
            case QUARANTINE -> {
                held(blobs, store, exchange.path(), commit.hash());
                exchange.respond(202);
            }
            // Refused outright: nothing is linked and no marker is set, so no version index lists it and the stored
            // blob is the usual unreferenced content-addressed object a collection reclaims. A refusal is never
            // released, so it is never laid out.
            case REJECT -> exchange.respond(422);
        }
    }

    /**
     * The parse-and-sidecar half of the layout, shared by the accepted leg and the held leg so the two can
     * never derive a different coordinate or a different sidecar for the same package. Reads the {@code .nuspec} out of
     * the stored blob for the coordinate, refuses a nuspec-supplied id/version that would forge a pointer key, and
     * precomputes the dependency groups at publish (the debian/rpm precompute-a-per-package-stanza pattern) so a
     * registration read concatenates stored sidecars instead of reopening and unzipping every version's {@code .nupkg}
     * on every read (read-first). The stored blob is reopened and only its {@code .nuspec} streamed out, never the whole
     * package pulled back into memory. The sidecar is written through {@link Blobs} rather than the operation's sidecar
     * seam because a blobs-namespace format stores its derived documents in the same pointer -&gt; blob representation
     * as its artifacts ({@code dependencyGroupsFor} reads it back with {@code blobs.read}), and the seam writes a raw
     * object; the ordering guarantee is the same, since on the accepted leg this runs inside the layout, strictly before
     * any declared visibility step, and on the held leg strictly before the withhold marker and the pointer.
     *
     * @return the lower-cased {@code {id, version}}, or {@code null} when nothing servable can be derived
     */
    private static String[] parsed(Blobs blobs, ArtifactStore store, String hash) throws IOException {
        String[] coordinate;
        try (InputStream stored = store.open("blobs/" + hash)) {
            coordinate = coordinate(stored);
        }
        if (coordinate == null) {
            return null;
        }
        String id = coordinate[0].toLowerCase(Locale.ROOT);
        String version = coordinate[1];
        if (Keys.unsafe(id) || Keys.unsafe(version)) {
            return null;
        }
        List<Map<String, Object>> groups;
        try (InputStream stored = store.open("blobs/" + hash)) {
            groups = dependencyGroups(stored);
        }
        blobs.write(dependenciesKey(id, version), JSON.writeValueAsBytes(groups));
        return new String[] {id, version};
    }

    /**
     * Lay a <em>held</em> package out behind its withhold marker, so the review release that follows is the same
     * marker clear a retroactive KEV/licence hold's release is - one hold-release mechanism for this format, not two.
     * The shared commit operation runs its accepted layout only on {@code ACCEPT}, so a screen-time {@code QUARANTINE}
     * would otherwise store the package, link nothing and index nothing: {@code HoldLifecycle.release} would then
     * resolve the hold and materialise no version at all, which is the regression this closes.
     *
     * <p><b>The review pointer is re-keyed onto the package, and that is load-bearing rather than cosmetic.</b> A NuGet
     * coordinate lives in the {@code .nuspec} <em>inside</em> the package, so the descriptor the screen assessed carries
     * the push ENDPOINT - one path every push shares - and {@code Publication.screen} therefore linked the review
     * pointer at {@code /quarantine/nuget/v3/package}. That was harmless while a held push laid nothing out; it is not
     * harmless now. The withhold marker is content-addressed and the {@code withheld-reconcile} backstop lifts a marker
     * that no live {@code /quarantine} pointer aliases once a live coordinate claims its bytes - which the layout below
     * makes true - so a second held push, overwriting the shared endpoint pointer, would strand the first package's
     * marker as "holderless" and the backstop would <em>un-withhold an unreviewed package</em>. Re-keying gives every
     * held package the same stable, per-artifact review handle npm, PyPI and Cargo screen under natively: the pointer
     * follows the artifact, two concurrent holds no longer share one handle, and the release resolves the coordinate
     * (so it lifts the marker instead of stranding a phantom {@code publish/} pointer at the endpoint). The endpoint
     * pointer is removed rather than kept beside it: the release's cross-alias guard treats any OTHER live pointer
     * carrying the hash as a still-standing hold, so leaving it would block the very release this fix exists to enable.
     * The {@code QuarantineLog} row is still written by the gate under the endpoint path - the reviewer's reasons and
     * the review handle disagree on the path for a held NuGet push, which is a separate, pre-existing consequence of
     * the coordinate living inside the artifact and is recorded as its own defect.
     *
     * <p>The order is the load-bearing part of the layout itself: the dependency sidecar is written first (a derived
     * document, nothing serves it), then {@link Withheld#mark} retracts the package's own hash, and only then is the
     * {@code .nupkg} pointer linked and the hosted marker stamped - so at no instant is the held package downloadable or
     * listed. The flat container, the registration index and the search all leave a version carrying that marker out of
     * their stored documents, as the download screens on it, so the version is stored, reviewable and invisible until
     * the release lifts it.
     */
    private static void held(Blobs blobs, ArtifactStore store, String endpoint, String hash) throws IOException {
        String[] coordinate = parsed(blobs, store, hash);
        if (coordinate == null) {
            return;   // nothing servable to hold open; the hold stays reviewable by its stored blob alone
        }
        String id = coordinate[0];
        String version = coordinate[1];
        Withheld.mark(store, hash, new ArtifactDescriptor("NuGet", id, version, flatContainerPath(id, version),
                "application/octet-stream", version.contains("-"), null, -1L));
        blobs.link(nupkgKey(id, version), hash);
        markHosted(store, HOSTED_KEY);
        new NuGetListings(blobs).refresh(id, version);   // held: the stored documents keep it out
        Publication publication = new Publication(store, List.of(), List.of());
        publication.link("/quarantine" + flatContainerPath(id, version), hash);
        publication.unpublish("/quarantine" + endpoint);
    }

    /** The package's own served download path - the identity the flat container serves it under, the path
     *  {@link #describe} parses back, and the path a held package's {@code /quarantine} review handle is re-keyed to. */
    private static String flatContainerPath(String id, String version) {
        return "/nuget/v3-flatcontainer/" + id + "/" + version + "/" + id + "." + version + ".nupkg";
    }

    /** The reserved store key of the deployment-wide hosted-publish marker (a bare presence flag, never a package id
     *  - a NuGet id cannot start with {@code .}). {@link #search} skips it when it enumerates ids. */
    private static final String HOSTED_KEY = "nuget/.hosted";
    private static final byte[] HOSTED = "1".getBytes(StandardCharsets.UTF_8);

    /** Whether this repository has ever taken a hosted push - it then carries {@link #HOSTED_KEY}, which a pull-through
     *  proxy never writes. The flat-container version-index gate keys on it so a proxy repository's version index
     *  always misses locally and reproxies the upstream version list (every version) for an uncached version rather
     *  than shadowing it with only the cached ones. */
    private static boolean hosted(Blobs blobs) throws IOException {
        return blobs.exists(HOSTED_KEY);
    }

    /** Stamp the hosted-publish marker once, idempotently - a compare-and-set against an absent pointer, so a
     *  concurrent push's lost race simply means a peer already set it. */
    private static void markHosted(ArtifactStore store, String key) throws IOException {
        if (store.readVersioned(key).isEmpty()) {
            store.writeVersioned(key, HOSTED, null);
        }
    }

    private static String dependenciesKey(String id, String version) {
        return "nuget/" + id + "/" + version + "/dependencies.json";
    }

    private void versions(String id, Blobs blobs, FormatExchange exchange) throws IOException {
        String lower = id.toLowerCase(Locale.ROOT);
        if (Keys.unsafe(lower) || !hosted(blobs) || (!StoredListing.present(blobs.store(), NuGetListings.versions(lower))
                && blobs.isEmpty("nuget/" + lower))) {
            exchange.respond(404);
            return;
        }
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new NuGetListings(blobs).versionsSpec(lower));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            respondListing(document, exchange, null);
        }
    }

    /** Stream a stored listing as JSON; with a base, the stored placeholder is completed on the way out (so the length
     *  is not known in advance and the body is sent without a {@code Content-Length}). The ETag is the stored document's
     *  digest, folded with the base it is completed for. */
    private static void respondListing(StoredListing.Served document, FormatExchange exchange, String base)
            throws IOException {
        String etag = '"' + document.header().sha256() + (base == null ? "" : "-" + Integer.toHexString(base.hashCode()))
                + '"';
        exchange.setResponseHeader("ETag", etag);
        if (etag.equals(exchange.requestHeader("If-None-Match"))) {
            exchange.respond(304);
            return;
        }
        exchange.setResponseHeader("Content-Type", "application/json");
        if (exchange.method().equals("HEAD")) {
            if (base == null) {
                exchange.setResponseHeader("Content-Length", Long.toString(document.header().size()));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        if (base == null) {
            // Streamed rather than materialised: the document is the size of what it lists, so handing
            // it over whole put the whole listing in heap on the request path.
            try (OutputStream out = exchange.respond(200, document.header().size())) {
                document.body().transferTo(out);
            }
        } else {
            // Streamed with the rewrite folded in, never as one byte array: a package's document is every version
            // of it, and answering it whole held the packument the publish had just streamed into - the
            // shape the npm-packument canary showed as a 500 at fifty thousand versions under 512 MiB, an OutOfMemoryError on the read
            // after the write was fixed. The length is not declared, since the rewrite changes it.
            try (OutputStream out = exchange.respond(200, -1L)) {
                document.copyTo(out, NuGetListings.BASE, base);
            }
        }
    }

    /** The {@code .nupkg} pointer key a version's bytes live at - the identity every version-enumerating surface judges
     *  an enumerated version folder by, so the stored listings and the download cannot drift apart. */
    static String nupkgKey(String id, String version) {
        return "nuget/" + id + "/" + version + "/" + id + "." + version + ".nupkg";
    }

    private void search(Blobs blobs, FormatExchange exchange) throws IOException {
        String query = exchange.queryParameter("q");
        int skip;
        int take;
        try {
            String skipped = exchange.queryParameter("skip");
            String taken = exchange.queryParameter("take");
            skip = skipped == null ? 0 : Math.max(0, Integer.parseInt(skipped));
            take = taken == null ? 20 : Math.min(Integer.parseInt(taken), 1000);
        } catch (NumberFormatException invalid) {
            exchange.respond(400);
            return;
        }
        // The search document is a stored listing every push maintains - one record per package id with its
        // servable versions. A search is one sequential pass over it through the codec's streaming reader, keeping
        // only the page asked for and a count, so the query never holds the document, whose size is the feed's. It
        // used to read the document whole and split every record into a map before filtering - three copies of the
        // feed in heap per query, which the nuget-search-memory canary showed as a 500 with an OutOfMemoryError at
        // half a million packages in a 512 MiB container.
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new NuGetListings(blobs).searchSpec());
        // A repository that has never had a package answers 404 rather than an empty result set, which is the ruling
        // for every drop-the-container route: an empty search document is the statement "there is nothing here to
        // search", and dressing it as a successful empty answer tells a client the registry exists and is simply
        // barren.
        //
        // The distinction that matters, and the one this must NOT collapse: a QUERY that matches nothing still
        // answers 200 with an empty data array. "This registry holds nothing" and "your term found nothing" are
        // different facts, and turning the second into a 404 would break search itself. So the test is on the record
        // set before the needle is applied, never after - which, streamed, is whether the pass saw any record at all.
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        long from = skip;
        long to = (long) skip + Math.max(take, 0);
        long totalHits = 0;
        long examined = 0;
        List<byte[]> data = new ArrayList<>();
        try (StoredListing.Served document = served.get();
             StoredListing.Codec.Reader reader = NuGetListings.RECORDS.read(document.body(),
                     document.header().size())) {
            for (Optional<Map.Entry<String, byte[]>> record = reader.next(); record.isPresent();
                 record = reader.next()) {
                examined++;
                if (!needle.isEmpty() && !record.get().getKey().contains(needle)) {
                    continue;
                }
                if (totalHits >= from && totalHits < to) {
                    data.add(record.get().getValue());   // only the requested page's records are retained
                }
                totalHits++;
            }
        }
        if (examined == 0) {
            exchange.respond(404);
            return;
        }
        StringBuilder json = new StringBuilder("{\"totalHits\":").append(totalHits).append(",\"data\":[");
        for (int i = 0; i < data.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(new String(data.get(i), StandardCharsets.UTF_8));
        }
        json.append("]}");
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void registration(String id, Blobs blobs, FormatExchange exchange) throws IOException {
        String lower = id.toLowerCase(Locale.ROOT);
        if (Keys.unsafe(lower) || (!StoredListing.present(blobs.store(), NuGetListings.registration(lower))
                && blobs.isEmpty("nuget/" + lower))) {
            exchange.respond(404);
            return;
        }
        String uri = exchange.requestUri();
        String nuget = RequestBase.of(exchange) + uri.substring(0, uri.indexOf("/v3/registrations/"));
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new NuGetListings(blobs).registrationSpec(lower));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            respondListing(document, exchange, nuget);
        }
    }

    static JsonNode dependencyGroupsFor(String lower, String version, Blobs blobs) throws IOException {
        ByteArrayOutputStream sidecar = new ByteArrayOutputStream();
        if (blobs.read(dependenciesKey(lower, version), sidecar)) {
            return JSON.readTree(sidecar.toByteArray());
        }
        List<Map<String, Object>> groups = List.of();
        String nupkgKey = "nuget/" + lower + "/" + version + "/" + lower + "." + version + ".nupkg";
        Optional<String> blobHash = blobs.hash(nupkgKey);
        if (blobHash.isPresent()) {
            try (InputStream nupkg = blobs.open(blobHash.get())) {
                groups = dependencyGroups(nupkg);
            } catch (IOException _) {
                groups = List.of();   // the blob is gone or unreadable - no dependency groups
            }
        }
        blobs.write(dependenciesKey(lower, version), JSON.writeValueAsBytes(groups));
        return JSON.valueToTree(groups);
    }

    private static List<Map<String, Object>> dependencyGroups(InputStream nupkg) {
        try {
            Document document = nuspec(nupkg);
            return document == null ? List.of() : groups(document);
        } catch (Exception _) {
            return List.of();
        }
    }

    /** Locate the {@code .nuspec} inside a {@code .nupkg} ZIP and parse it as a bounded XML document, or {@code null}
     *  when there is no {@code .nuspec} at all. An entry that inflates past the shared archive-inflation ceiling is a
     *  different answer and raises rather than returning null - it is not a usable manifest (the deflate-bomb shape),
     *  and it is never handed on as the prefix that was read before the ceiling. The walk to the entry runs under the
     *  shared {@link build.jenesis.repository.store.ArchiveWalk#largestWalk()} bound and at most
     *  {@link build.jenesis.repository.store.ArchiveInflation#largestEntry()} bytes reach the DOM builder, so both the
     *  publish path ({@link #coordinate}, {@link #push}) and the unauthenticated registration read
     *  ({@link #dependencyGroups} via {@link #dependencyGroupsFor}) stay bounded whatever a hostile package claims to
     *  inflate to - the NuGet compliance inspector applies the same shared ceiling. The builder disables DTDs/external
     *  entities (XXE), as the inspector and the archive-cracking peers do. */
    /**
     * A {@code .nupkg} may carry an author or repository signature as {@code .signature.p7s}, a PKCS#7 structure inside
     * the archive whose signed content names the package's hash - optional, since most packages carry none, and the
     * signed bytes are the package as it was before the signature entry was appended ({@link NuGetSignedArchive}).
     */
    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return signable(path)
                ? List.of(ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.PKCS7))
                : List.of();
    }

    /**
     * The two paths a package's own bytes are ever in hand at: the file it serves from, and the one endpoint every
     * package is pushed to.
     *
     * <p>The push is what this used to miss. A coordinate lives in the {@code .nuspec} inside the archive rather
     * than in the path, so the descriptor a push hands the screen carries the endpoint's address and nothing else -
     * {@value #PUSH_ROUTE}, the same string for every package there will ever be. Keyed on the served name alone,
     * the expectation was empty at the one moment the bytes were in front of the gate, and the signature entry
     * inside the archive went unread on every publish. Measured 2026-09-15 over the shipped image: a package whose
     * chain reached a configured anchor and one signed by an authority nobody named both served, and neither had a
     * recorded signature at all.
     */
    private static boolean signable(String path) {
        return !path.contains("..")
                && (path.endsWith(".nupkg") || path.equals(PUSH_ROUTE) || path.equals(PUSH_ROUTE + "/"));
    }

    /**
     * Yes: a NuGet signature is a {@code .signature.p7s} entry stored inside the package, so a screen deciding
     * whether to claim a push cannot see it by looking beside the artifact. Declaring it is what makes the push
     * claimed at all - see {@link ArtifactSignatures#embedsEvidence}.
     */
    @Override
    public boolean embedsEvidence(String path) {
        return signable(path);
    }

    @Override
    public List<ArtifactSignatures.Evidence> evidence(String path, ArtifactSignatures.Material material)
            throws IOException {
        if (expects(path).isEmpty()) {
            return List.of();
        }
        Optional<ArtifactSignatures.Signed> body = material.body();
        if (body.isEmpty()) {
            return List.of();
        }
        Optional<byte[]> signature;
        try (InputStream nupkg = body.get().open()) {
            signature = NuGetSignedArchive.signature(nupkg);
        }
        return signature
                .map(p7s -> List.of(new ArtifactSignatures.Evidence(ArtifactSignatures.Scheme.PKCS7, p7s,
                        NuGetSignedArchive.unsigned(body.get()), path + "!" + NuGetSignedArchive.SIGNATURE_ENTRY)))
                .orElse(List.of());
    }

    private static Document nuspec(InputStream nupkg) throws IOException {
        return ArchiveWalk.walk(nupkg, NuGetFormat::declaredNuspec).orNull();
    }

    /** The parsed {@code .nuspec} inside an already-bounded {@code .nupkg} stream, or {@code null} when it carries
     *  none. */
    private static Document declaredNuspec(InputStream nupkg) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(nupkg)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().endsWith(".nuspec")) {
                    // The .nuspec carries the package's id/version, so it is the fail-closed side of the shared bound:
                    // an entry the ceiling stopped yields no bytes at all and raises, rather than coming back as the
                    // same null a .nupkg carrying no .nuspec yields. The publish leg (coordinate) turns that into its
                    // 400; the registration read leg (dependencyGroups) catches it and serves no dependency groups,
                    // which is the degrade an already-published package's derived view is allowed.
                    byte[] xml = ArchiveInflation.entry(zip).required("NuGet package", ".nuspec");
                    try {
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                        factory.setNamespaceAware(false);
                        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
                    } catch (ParserConfigurationException | SAXException unreadable) {
                        // A .nuspec that will not parse is not a usable manifest. It is reported as the unreadable
                        // archive member it is, so the walk bound and a corrupt member stay distinguishable: the
                        // callers turn either into their 400 / no-dependency-groups degrade, but only a genuine bound
                        // is ever reported as one.
                        throw new IOException("The NuGet package's .nuspec is not a readable XML document",
                                unreadable);
                    }
                }
            }
        }
        return null;
    }

    // A .nuspec lists dependencies either grouped by target framework or flat directly under <dependencies>; both map
    // to NuGet registration dependencyGroups (the flat ones as a single group with no targetFramework).
    private static List<Map<String, Object>> groups(Document document) {
        NodeList blocks = document.getElementsByTagName("dependencies");
        if (blocks.getLength() == 0) {
            return List.of();
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        List<Map<String, String>> flat = new ArrayList<>();
        NodeList children = blocks.item(0).getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element element && element.getTagName().equals("group")) {
                Map<String, Object> group = new LinkedHashMap<>();
                String framework = element.getAttribute("targetFramework");
                if (!framework.isBlank()) {
                    group.put("targetFramework", framework);
                }
                group.put("dependencies", dependencies(element.getElementsByTagName("dependency")));
                groups.add(group);
            } else if (children.item(index) instanceof Element element && element.getTagName().equals("dependency")) {
                flat.add(dependency(element));
            }
        }
        if (!flat.isEmpty()) {
            groups.add(Map.of("dependencies", flat));
        }
        return groups;
    }

    private static List<Map<String, String>> dependencies(NodeList nodes) {
        List<Map<String, String>> dependencies = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element) {
                dependencies.add(dependency(element));
            }
        }
        return dependencies;
    }

    private static Map<String, String> dependency(Element element) {
        return Map.of("id", element.getAttribute("id"), "range", element.getAttribute("version"));
    }

    private void serve(String id, String version, String file, Blobs blobs, FormatExchange exchange)
            throws IOException {
        String key = "nuget/" + id.toLowerCase(Locale.ROOT) + "/" + version + "/" + file;
        Optional<Blobs.Located> located = blobs.locate(key);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", "application/octet-stream");
        if (exchange.method().equals("HEAD")) {
            // Answer HEAD from the stored blob size (Content-Length, 200, no body) rather than streaming the whole
            // .nupkg just to discard it - a restore client issues HEADs to probe a package's size and existence.
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    /**
     * Proxy a NuGet flat-container miss to the upstream registry (api.nuget.org). The service index stays local
     * (it advertises this registry's own flat container). A version index is mutable and a list of version strings
     * with no URLs, so it is streamed through; a {@code .nupkg} is immutable, so it is fetched, cached and served.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        if (!path.startsWith("/nuget/v3-flatcontainer/")) {
            // ProxyLeg has screened the path already - this format claims it, and it carries no traversal
            // segment, backslash or control character. What is left is this leg's own routing: only the
            // subtree below proxies, and any other claimed path lets the local 404 stand.
            return false;
        }
        String after = path.substring("/nuget/v3-flatcontainer/".length());
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        if (after.endsWith("/index.json")) {
            // The version index is a small mutable list of version strings, streamed through fresh. Forward the client's
            // conditional-request validators so a 304-capable client's revalidation reaches the upstream, and relay the
            // upstream's validators back so its next read can revalidate rather than re-downloading the index.
            // ENUMERATION: a flat-container index.json is literally {"versions":[...]} - the list NuGet restore resolves
            // a floating version against - so an absent one is the answer "no such package" and an empty one "no
            // version satisfies you". Only an upstream that ANSWERED 404/410 may reach the client as a 404.
            ProxyRelay.Answer answer = ProxyRelay.fetchFresh(fetcher, URI.create(root + "v3-flatcontainer/" + after),
                    ProxyRelay.conditionalHeaders(exchange), exchange, ProxyRelay.Document.ENUMERATION);
            if (!answer.answered()) {
                return answer.served();
            }
            ProxyRelay.relayValidators(answer.document(), exchange);
            exchange.setResponseHeader("Content-Type", "application/json");
            exchange.respond(200, answer.document().body());
            return true;
        }
        int slash = after.indexOf('/');
        int next = slash < 0 ? -1 : after.indexOf('/', slash + 1);
        if (next < 0) {
            return false;
        }
        String id = after.substring(0, slash);
        String version = after.substring(slash + 1, next);
        String key = "nuget/" + id.toLowerCase(Locale.ROOT) + "/" + version + "/" + after.substring(next + 1);
        // Point-integrity: NuGet publishes the .nupkg's SHA-512 package hash through its v3 registration/catalog, so
        // resolve it via the service index and verify the streamed package against it, refusing a mismatch - the
        // checksum parity the Maven proxy leg has. The registration chain is three SEPARATE documents from the
        // flat-container download below, so a hop this repository could not read (or one the outbound screen refuses)
        // is not "this upstream has no v3 chain" and must not downgrade the fill.
        URI target = URI.create(root + "v3-flatcontainer/" + after);
        ProxyRelay.Declared expected =
                nupkgChecksum(root, id, version, fetcher, ProxyLeg.allowInternalTargets(exchange));
        if (!expected.readable()) {
            return ProxyRelay.unverifiable(target, expected);
        }
        // A .nupkg is an immutable artifact of unbounded size: stream it from the network straight into the
        // content-addressed store rather than buffering the whole body, then re-serve it locally.
        try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                return false;
            }
            if (!ProxyRelay.fill(new Blobs(store), key, target, download.body(), expected)) {
                return false;
            }
        }
        handle(exchange, store);
        return true;
    }

    /** The SHA-512 NuGet publishes as a package's {@code packageHash} (base64, {@code packageHashAlgorithm: SHA512}),
     *  resolved through the v3 metadata chain so a proxied {@code .nupkg} can be verified against it: the service index
     *  ({@code v3/index.json}) names the {@code RegistrationsBaseUrl}, whose leaf ({@code <id>/<version>.json}) carries
     *  the hash inline or points at a catalog leaf that does. Each hop is a small bounded metadata read, only on a
     *  package miss (once per package, since the {@code .nupkg} is then cached).
     *
     *  <p>{@link ProxyRelay.Declared#NONE} - falling back to plain caching, as Maven serves a jar with no
     *  {@code .sha1} - when a hop <em>answered</em> and the chain declares nothing: a service index naming no
     *  {@code RegistrationsBaseUrl}, a {@code 404}/{@code 410} registration or catalog leaf (an upstream without the
     *  standard v3 chain), or a leaf carrying no SHA-512 {@code packageHash}.
     *  {@linkplain ProxyRelay.Declared#unreadable Unreadable} when a hop could not be read - a transport failure, a
     *  {@code 429}/{@code 5xx}/auth challenge, a body that is not JSON - and, since the earlier work, also when the outbound
     *  screen <b>refuses</b> an advertised {@code @id}: that hop is chosen by the upstream, and a refused one used to
     *  read as "this upstream publishes no hash", which is the fail-open the screen exists to prevent (the rpm leg's
     *  {@code RefusedTarget} shape). */
    private static ProxyRelay.Declared nupkgChecksum(String root, String id, String version,
            ProxyFormat.Fetcher fetcher, boolean allowInternal) throws IOException {
        Resolved index = fetchJson(root + "v3/index.json", fetcher);
        if (!index.readable()) {
            return index.declared();
        }
        String registrations = index.answered() ? resource(index.document(), "RegistrationsBaseUrl") : null;
        if (registrations == null) {
            return ProxyRelay.Declared.NONE;
        }
        if (!registrations.endsWith("/")) {
            registrations += "/";
        }
        URI upstream = URI.create(root);
        String leaf = registrations + id.toLowerCase(Locale.ROOT) + "/" + version.toLowerCase(Locale.ROOT) + ".json";
        if (unsafeUpstreamUrl(leaf, upstream, allowInternal)) {
            return ProxyRelay.Declared.unreadable("the registration leaf " + leaf + " the upstream service index "
                    + "advertises is refused by the outbound screen (set " + ProxyLeg.ALLOW_INTERNAL
                    + " to permit an internal or http target)");
        }
        Resolved registration = fetchJson(leaf, fetcher);
        if (!registration.readable()) {
            return registration.declared();
        }
        if (!registration.answered()) {
            return ProxyRelay.Declared.NONE;
        }
        byte[] direct = packageHash(registration.document());
        if (direct != null) {
            return ProxyRelay.Declared.of("SHA-512", direct);
        }
        // The hash lives in the catalog leaf the registration's catalogEntry points at (a URL, on nuget.org).
        JsonNode catalogEntry = registration.document().get("catalogEntry");
        if (catalogEntry == null || !catalogEntry.isString()) {
            return ProxyRelay.Declared.NONE;
        }
        if (unsafeUpstreamUrl(catalogEntry.asString(), upstream, allowInternal)) {
            return ProxyRelay.Declared.unreadable("the catalog leaf " + catalogEntry.asString() + " the upstream "
                    + "registration advertises is refused by the outbound screen (set " + ProxyLeg.ALLOW_INTERNAL
                    + " to permit an internal or http target)");
        }
        Resolved catalog = fetchJson(catalogEntry.asString(), fetcher);
        if (!catalog.readable()) {
            return catalog.declared();
        }
        byte[] hash = catalog.answered() ? packageHash(catalog.document()) : null;
        return hash == null ? ProxyRelay.Declared.NONE : ProxyRelay.Declared.of("SHA-512", hash);
    }

    /** Whether an upstream-ADVERTISED URL (the {@code RegistrationsBaseUrl} @id from the service index, a
     *  {@code catalogEntry} link) is unsafe to fetch. Those hops are chosen by the upstream's own responses, like a
     *  redirect Location, so they get the whole shared outbound screen
     *  ({@link OutboundTargets#advertisedRefusal}) rather than a private copy of it. A malformed URL is unsafe, and so
     *  is one this deployment could not issue a request to at all - the floor beneath the screen, which the dial does
     *  not lift.
     *
     *  <p>Two things changed here, and both tightened this leg. The exemption is now on the upstream's own
     *  ORIGIN rather than its bare host name: this method used to trust any hop naming the same HOST, so a compromised
     *  upstream could pivot the proxy onto any other PORT of its own box ({@code https://mirror.internal:9200/} under
     *  an {@code https://mirror.internal/} upstream), which is a real if narrow SSRF and is gone. And a hop naming no
     *  host is refused by the floor rather than by this leg's own null check, because whether a URL names a host is a
     *  fact about {@code HttpRequest.newBuilder} and not a policy this leg gets to hold privately. */
    private static boolean unsafeUpstreamUrl(String url, URI upstream, boolean allowInternal) {
        URI target;
        try {
            target = URI.create(url);
        } catch (IllegalArgumentException malformed) {
            return true;
        }
        return !OutboundTargets.mayFollow(target, upstream, allowInternal);
    }

    /** The {@code RegistrationsBaseUrl} (or another named resource) advertised by an already-read v3 service index, or
     *  {@code null} when it names none. Prefers a bare {@code RegistrationsBaseUrl}, else any versioned
     *  variant ({@code RegistrationsBaseUrl/3.6.0}, ...), all of which resolve the same leaf. */
    private static String resource(JsonNode index, String type) {
        if (!(index.get("resources") instanceof ArrayNode resources)) {
            return null;
        }
        String fallback = null;
        for (JsonNode entry : resources) {
            JsonNode atType = entry.get("@type");
            JsonNode atId = entry.get("@id");
            if (atType == null || atId == null || !atId.isString()) {
                continue;
            }
            String kind = atType.asString();
            if (kind.equals(type)) {
                return atId.asString();
            }
            if (kind.startsWith(type + "/") && fallback == null) {
                fallback = atId.asString();
            }
        }
        return fallback;
    }

    /** The raw SHA-512 bytes of a node's {@code packageHash} when {@code packageHashAlgorithm} is {@code SHA512} and the
     *  hash is valid base64 of 64 bytes, else {@code null}. */
    private static byte[] packageHash(JsonNode node) {
        JsonNode hash = node.get("packageHash");
        JsonNode algorithm = node.get("packageHashAlgorithm");
        if (hash == null || !hash.isString() || algorithm == null
                || !algorithm.asString().equalsIgnoreCase("SHA512")) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(hash.asString());
            return raw.length == 64 ? raw : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** One hop of the v3 metadata chain: the parsed document when the hop answered {@code 200}, {@code null} when it
     *  answered a miss (so the chain declares nothing), or an {@linkplain ProxyRelay.Declared#unreadable unreadable}
     *  verdict when it could not be read at all. The three are kept apart here rather than flattened into a
     *  {@code null} {@code JsonNode}, because that flattening is exactly what let a {@code 429} on one hop read as
     *  "this upstream publishes no package hash".
     *
     *  @param document the hop's parsed body, or {@code null} when it answered a miss or could not be read
     *  @param declared the verdict to return when {@link #readable()} is {@code false} */
    private record Resolved(JsonNode document, ProxyRelay.Declared declared) {

        /** Whether the hop could be read at all; {@code false} means the caller returns {@link #declared()}. */
        boolean readable() {
            return declared == null;
        }

        /** Whether the hop answered with a document, as against an upstream miss the chain is entitled to. */
        boolean answered() {
            return document != null;
        }
    }

    /** Fetch one bounded JSON hop of the v3 metadata chain, classified as {@link Resolved} documents. */
    private static Resolved fetchJson(String url, ProxyFormat.Fetcher fetcher) throws IOException {
        URI target = URI.create(url);
        ProxyRelay.Sidecar sidecar = ProxyRelay.declaring(fetcher, target, Map.of("Accept", "application/json"));
        if (!sidecar.answered()) {
            return sidecar.verdict().readable()
                    ? new Resolved(null, null)                       // the upstream answered: it carries no such hop
                    : new Resolved(null, sidecar.verdict());
        }
        JsonNode document = JSON.readTree(sidecar.document().body());
        return document.isObject()
                ? new Resolved(document, null)
                : new Resolved(null, ProxyRelay.Declared.unreadable("the v3 metadata document at " + target
                        + " answered 200 with a body that is not a JSON object"));
    }

    static String[] coordinate(InputStream nupkg) {
        try {
            Document document = nuspec(nupkg);   // raises past the shared inflation ceiling, so a deflate-bomb push fails closed (400)
            if (document == null) {
                return null;
            }
            String id = text(document, "id");
            String version = text(document, "version");
            return id == null || version == null ? null : new String[]{id, version};
        } catch (Exception _) {
            return null;
        }
    }

    private static String text(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }






    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link NuGetImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final NuGetImporter importer = new NuGetImporter();

    @Override
    public boolean imports(String sourceFormat) {
        return importer.imports(sourceFormat);
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        return importer.importTarget(sourcePath);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        importer.importArtifact(path, content, store);
    }
}
