package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.Maintainers;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * The keys a deployment discovered rather than configured: what the key-discovery pass fetched from the sources an
 * operator named, kept per repository as one armoured bundle ({@code discovered/openpgp}) beside the queue of keys
 * still wanted ({@code discover/openpgp/<key id>}, one marker per signer the screen met and could not place) and,
 * for a key found through a maintainer rather than by its own id, the binding that says whose it is
 * ({@code discovered/bound/<key id>}).
 *
 * <p>Discovery is off by default, and a discovered key <em>verifies</em> a signature but does not make it trusted:
 * as a {@link SignerTrust} part this holds the bundle as its material and answers {@link #trusts} only when the
 * operator set {@code signature-key-discovery-accept}, so the outcome of a signature by a discovered key stays
 * UNTRUSTED - with the finding saying where the key came from - until the operator admits the key by pasting it
 * into {@code signature-trusted-keys}, or accepts the source outright. That is what keeps "we could verify this"
 * and "we believe this" apart, which is the whole point of discovering keys without trusting them.
 *
 * <p>Accepting the sources does not pool a key found through a maintainer. A key looked up by its own id speaks
 * for nobody in particular and, accepted, is trusted wherever it signs; a key found in the Web Key Directory of an
 * e-mail address or among a GitHub login's published keys was found <em>because an artifact's metadata named that
 * person</em>, and it is trusted only for a coordinate whose recorded {@link Maintainers} name them. A stranger's
 * key discovered for one package therefore never admits that stranger's signature on another.
 *
 * <p>Nothing here makes an outbound call: the bundle, the bindings and the maintainer records are stored documents
 * read by point read at verification time, and the pass that fills them runs on the maintenance cadence, off every
 * request path.
 */
final class DiscoveredKeys implements SignerTrust {

    /** The one armoured bundle of every discovered OpenPGP key, in the order they were fetched. */
    static final String BUNDLE = "discovered/openpgp";

    /** The queue: one marker per key id the screen met and could not place, named by the key id. */
    static final String WANTED = "discover/openpgp/";

    /** The bindings: one document per key found through a maintainer, naming the maintainers it was found for. */
    static final String BOUND = "discovered/bound/";

    /** The source name a signature verified by a discovered key is reported with. */
    static final String SOURCE = "discovered";

    private final ArtifactStore store;
    private final boolean accepted;

    DiscoveredKeys(ArtifactStore store, boolean accepted) {
        this.store = store;
        this.accepted = accepted;
    }

    @Override
    public Optional<byte[]> material(String scheme) {
        if (!SignerIdentity.OPENPGP.equals(scheme)) {
            return Optional.empty();
        }
        try {
            return store.readVersioned(BUNDLE).map(ArtifactStore.Versioned::content).filter(keys -> keys.length > 0);
        } catch (IOException unreadable) {
            return Optional.empty();   // no material is the fail-closed direction: nothing verifies, nothing is trusted
        }
    }

    /** Accepted, and - for a key found through a maintainer - named by this coordinate's own metadata. */
    @Override
    public boolean trusts(SignerIdentity signer, String ecosystem, String coordinate) {
        if (!accepted) {
            return false;
        }
        Optional<String> keyId = keyId(signer);
        if (keyId.isEmpty()) {
            return false;
        }
        try {
            Set<String> bound = binding(store, keyId.get());
            if (bound.isEmpty()) {
                return true;   // looked up by its own id: the operator accepted the source for everything it serves
            }
            return !Collections.disjoint(bound, Maintainers.named(store, ecosystem, coordinate));
        } catch (IOException unreadable) {
            return false;   // could not read the binding: not trusted, which is the safe answer
        }
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public Optional<Expectation> expected(String ecosystem, String coordinate, String scheme) {
        return Optional.empty();
    }

    @Override
    public void observed(String ecosystem, String coordinate, String version, SignerIdentity signer, Instant when) {
    }

    /** A signer no source could place is what the pass fetches: its key id joins the wanted queue. */
    @Override
    public void wanted(SignerIdentity signer, String path, Instant when) throws IOException {
        wanted(signer, path, Set.of(), when);
    }

    /** As above, with the maintainers the artifact names, so a source that looks a key up by its owner has whom
     *  to ask for. */
    @Override
    public void wanted(SignerIdentity signer, String path, Set<String> maintainers, Instant when) throws IOException {
        Optional<String> keyId = keyId(signer);
        if (keyId.isPresent()) {
            want(store, keyId.get(), path, maintainers, when);
        }
    }

    /** Whether the bundle already holds a key by this id, so the pass fetches nothing it has - asked of the
     *  installed OpenPGP verifier, and false where none is installed to read the bundle. */
    static boolean holds(ArtifactStore store, String keyId) throws IOException {
        Optional<byte[]> bundle = store.readVersioned(BUNDLE).map(ArtifactStore.Versioned::content);
        return bundle.isPresent() && SignatureScheme.installed(ArtifactSignatures.Scheme.OPENPGP_DETACHED)
                .map(openpgp -> openpgp.holdsKey(keyId, bundle.get())).orElse(false);
    }

    /** Append a fetched armoured key to the bundle, under compare-and-set so two nodes' passes both land. */
    static void add(ArtifactStore store, byte[] armoured) throws IOException {
        Retries.update(store, BUNDLE, current -> {
            byte[] existing = current.map(ArtifactStore.Versioned::content).orElse(new byte[0]);
            byte[] joined = new byte[existing.length + (existing.length > 0 ? 1 : 0) + armoured.length];
            System.arraycopy(existing, 0, joined, 0, existing.length);
            int at = existing.length;
            if (at > 0) {
                joined[at++] = '\n';
            }
            System.arraycopy(armoured, 0, joined, at, armoured.length);
            return joined;
        });
    }

    /** Bind a key found through a maintainer to that maintainer - unioned, since one key may be found for the
     *  same person named two ways - with the source it was found at. */
    static void bind(ArtifactStore store, String keyId, String maintainer, String source) throws IOException {
        Retries.update(store, BOUND + keyId, current -> {
            Marker bound = Marker.parse(current.map(ArtifactStore.Versioned::content).orElse(new byte[0]));
            Set<String> maintainers = new TreeSet<>(bound.maintainers());
            maintainers.add(maintainer);
            return new Marker(Map.of("source", source), maintainers).render();
        });
    }

    /** The maintainers a key was found for, or none for a key looked up by its own id (or never discovered). */
    static Set<String> binding(ArtifactStore store, String keyId) throws IOException {
        return store.readVersioned(BOUND + keyId)
                .map(bound -> Marker.parse(bound.content()).maintainers())
                .orElse(Set.of());
    }

    /**
     * Record that the screen met a signature by this key id and could not place it, so the pass fetches it. The
     * marker names the path that wanted it, when, and the maintainers that artifact names; a marker already present
     * keeps what it knew and gains the maintainers it did not, so a key looked for and not found ({@link #missed})
     * is not asked for again on every publish, while a later publish that names whom to ask still reaches the pass.
     */
    static void want(ArtifactStore store, String keyId, String path, Set<String> maintainers, Instant when)
            throws IOException {
        String key = WANTED + keyId;
        Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
        if (current.isEmpty()) {
            Map<String, String> fresh = new LinkedHashMap<>();
            fresh.put("path", path);
            fresh.put("wanted", when.toString());
            // A create, never an overwrite: a marker a peer wrote in the same moment stays, and carries the same want.
            store.writeVersioned(key, new Marker(fresh, maintainers).render(), null);
            return;
        }
        Marker marker = Marker.parse(current.get().content());
        if (marker.maintainers().containsAll(maintainers)) {
            return;
        }
        Set<String> union = new LinkedHashSet<>(marker.maintainers());
        union.addAll(maintainers);
        // A lost race is a peer's union landing first, which the next want completes.
        store.writeVersioned(key, new Marker(marker.lines(), union).render(), current.get().token());
    }

    /** The key id a signer identity names: the low 64 bits of a fingerprint, or the key id itself. */
    static Optional<String> keyId(SignerIdentity signer) {
        if (signer == null || !SignerIdentity.OPENPGP.equals(signer.scheme())) {
            return Optional.empty();
        }
        String hex = signer.value();
        if (hex.length() < 16 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return Optional.empty();
        }
        return Optional.of(hex.substring(hex.length() - 16).toUpperCase(Locale.ROOT));
    }

    /** Mark a wanted key no source had, with when it was asked, so the pass asks again only after a day - keeping
     *  the path and the maintainers the marker named, since the next asking wants them as much as this one did. */
    static void missed(ArtifactStore store, String keyId, Instant when) throws IOException {
        String key = WANTED + keyId;
        Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
        Marker marker = current.map(stored -> Marker.parse(stored.content())).orElse(Marker.EMPTY);
        Map<String, String> lines = new LinkedHashMap<>(marker.lines());
        lines.put("missed", when.toString());
        store.writeVersioned(key, new Marker(lines, marker.maintainers()).render(),
                current.map(ArtifactStore.Versioned::token).orElse(null));
    }

    /** When the source last answered that it had no such key, or empty for a marker never missed. */
    static Optional<Instant> missedAt(byte[] marker) {
        String missed = Marker.parse(marker).lines().get("missed");
        if (missed == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(missed));
        } catch (DateTimeException malformed) {
            return Optional.empty();
        }
    }

    /** The maintainers a wanted marker names - whom a source that looks keys up by their owner asks for. */
    static Set<String> maintainers(byte[] marker) {
        return Marker.parse(marker).maintainers();
    }

    /** A marker or binding document: {@code name=value} lines, single-valued but for the repeated
     *  {@code maintainer} line, which is the one list a document carries. */
    private record Marker(Map<String, String> lines, Set<String> maintainers) {

        static final Marker EMPTY = new Marker(Map.of(), Set.of());

        static Marker parse(byte[] document) {
            Map<String, String> lines = new LinkedHashMap<>();
            Set<String> maintainers = new LinkedHashSet<>();
            for (String line : new String(document, StandardCharsets.UTF_8).split("\n")) {
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String name = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                if (name.equals("maintainer")) {
                    if (!value.isEmpty()) {
                        maintainers.add(value);
                    }
                } else {
                    lines.putIfAbsent(name, value);
                }
            }
            return new Marker(lines, maintainers);
        }

        byte[] render() {
            StringBuilder document = new StringBuilder();
            lines.forEach((name, value) -> document.append(name).append('=').append(value).append('\n'));
            for (String maintainer : maintainers) {
                document.append("maintainer=").append(maintainer).append('\n');
            }
            return document.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
