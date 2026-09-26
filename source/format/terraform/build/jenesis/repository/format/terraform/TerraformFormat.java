package build.jenesis.repository.format.terraform;

import module java.base;

import build.jenesis.repository.blobs.BlobExport;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.blobs.RequestBase;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import build.jenesis.repository.format.Listings;

/**
 * The Terraform / OpenTofu registry: both of its protocols, hosted.
 *
 * <p>It is two protocols and not one, which is most of the shape of this format. The <b>module registry</b> answers
 * a version list and then a {@code download} that carries the archive's URL in an {@code X-Terraform-Get} header on
 * a {@code 204} - the body is empty by specification. The <b>provider registry</b> answers a version list whose
 * entries name the platforms held, and then a per-platform document naming the zip, its {@code SHA256SUMS}, that
 * file's <em>signature</em>, and the public key the signature verifies against. OpenTofu speaks both, so one format
 * serves both clients.
 *
 * <p><b>This is the format that brings signing material with it.</b> A provider release is only installable if the
 * client can verify the {@code SHA256SUMS} that names its zip's digest, so the first provider publish generates the
 * repository's OpenPGP key, every {@code SHA256SUMS} write derives a fresh detached signature, and the package
 * document declares the public half inline as the protocol requires. The signature is <b>binary</b> rather than
 * armoured, which was measured against {@code registry.terraform.io} rather than assumed.
 *
 * <p><b>{@code /v1/} is Terraform's, not ours.</b> The protocol fixes those paths, the way the OCI, NuGet and
 * crates prefixes are fixed by their specifications. The artifacts themselves are stored outside it, because the
 * protocol says a download URL is whatever the registry chooses and tying a stored key to a foreign protocol
 * revision would outlive the revision.
 *
 * <p><b>There is no publish protocol to implement.</b> Terraform registries are populated out of band - upstream a
 * module is a git tag and a provider release is a GitHub release - so publishing here is a {@code PUT} of the
 * artifact, exactly as the Debian, RPM, Helm and apk formats publish what their clients can only read.
 *
 * <p><b>What a client cannot reach is discovery.</b> Terraform finds a registry by fetching
 * {@code /.well-known/terraform.json} from the <em>host root</em>, which is not under any format's prefix - so that
 * document is contributed by a separate module rather than served here. A deployment that does not install it can
 * still be read by anything addressing these paths directly, but not by {@code terraform init}.
 */
public final class TerraformFormat implements RepositoryFormat, ArtifactLayout, BlobLayout, RepositoryExporter,
        RepositoryImporter, ProxyLeg {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Logger LOGGER = LoggerFactory.getLogger(TerraformFormat.class);

    /** The package-ecosystem name Terraform coordinates report. */
    public static final String ECOSYSTEM = "Terraform";

    private static final String PREFIX = "/terraform/";

    private static final String MODULE_ARCHIVE = ".tar.gz";

    private static final String PROVIDER_ARCHIVE = ".zip";

    private static final String IDENTITY = "Jenesis Repository <repository@jenesis.build>";

    /** How long a generated signing key is valid before it must be rotated. */
    private static final Duration KEY_VALIDITY = Duration.ofDays(730);

    /** How long before expiry a fresh key takes over, leaving an overlap for a client to refetch. */
    private static final Duration ROTATION_WINDOW = Duration.ofDays(90);

    private static final String SECRET_KEY = "terraform/keys/secret.asc";

    private static final String PUBLIC_KEY = "terraform/keys/public.asc";

    @Override
    public String name() {
        return "terraform";
    }

    private final TerraformImporter importer = new TerraformImporter();

    @Override
    public boolean imports(String format) {
        return importer.imports(format);
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        return importer.importTarget(path);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        importer.importArtifact(path, content, store);
    }

    /** A lifecycle mark surfaces in the metadata this format's clients read, so marks are accepted here. */
    @Override
    public boolean surfacesLifecycleMarks() {
        return true;
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        Blobs blobs = new Blobs(store);
        String rest = exchange.path().substring(PREFIX.length());
        String[] segments = rest.split("/");
        if (segments.length < 2 || Keys.unsafe(segments[0])) {
            exchange.respond(404);
            return;
        }
        String repo = segments[0];
        String method = exchange.method();
        String[] tail = Arrays.copyOfRange(segments, 1, segments.length);
        if (method.equals("POST") && tail.length == 1 && tail[0].equals("keys")) {
            provisionKey(exchange, blobs);
        } else if (method.equals("PUT")) {
            push(exchange, blobs, repo, tail);
        } else if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.respond(405);
        } else if (tail.length == 1 && tail[0].equals("keys")) {
            servePublicKey(exchange, blobs);
        } else if (tail[0].equals("v1")) {
            protocol(exchange, blobs, repo, Arrays.copyOfRange(tail, 1, tail.length));
        } else {
            artifact(exchange, blobs, repo, tail);
        }
    }

    // ---- the protocol surface ----

    private void protocol(FormatExchange exchange, Blobs blobs, String repo, String[] path) throws IOException {
        TerraformListings listings = listings(blobs);
        if (path.length == 5 && path[0].equals("modules") && path[4].equals("versions")) {
            serveListing(exchange, blobs, listings.moduleVersionsSpec(repo, path[1], path[2], path[3]),
                    TerraformCoordinates.ROOT + repo + "/modules/" + path[1] + "/" + path[2] + "/" + path[3]);
        } else if (path.length == 6 && path[0].equals("modules") && path[5].equals("download")) {
            moduleDownload(exchange, blobs, repo, path[1], path[2], path[3], path[4]);
        } else if (path.length == 4 && path[0].equals("providers") && path[3].equals("versions")) {
            serveListing(exchange, blobs, listings.providerVersionsSpec(repo, path[1], path[2]),
                    TerraformCoordinates.ROOT + repo + "/providers/" + path[1] + "/" + path[2]);
        } else if (path.length == 7 && path[0].equals("providers") && path[4].equals("download")) {
            providerPackage(exchange, blobs, repo, path[1], path[2], path[3], path[5], path[6]);
        } else {
            exchange.respond(404);
        }
    }

    /**
     * {@code GET .../download} for a module: a {@code 204} whose {@code X-Terraform-Get} names the archive.
     *
     * <p>The empty body is the protocol, not an omission - the client reads the header and fetches the URL itself.
     * A version this repository does not serve is a {@code 404} rather than a header pointing at nothing, because a
     * client that followed such a header would report a download failure instead of an absent version.
     */
    private void moduleDownload(FormatExchange exchange, Blobs blobs, String repo, String namespace, String name,
                                String system, String version) throws IOException {
        String key = TerraformCoordinates.moduleArchive(repo, namespace, name, system, version);
        if (!blobs.exists(key) || blobs.withheld(key)) {
            exchange.respond(404);
            return;
        }
        exchange.setResponseHeader("X-Terraform-Get", base(exchange, repo) + "/modules/" + namespace + "/" + name
                + "/" + system + "/" + version + MODULE_ARCHIVE);
        exchange.respond(204);
    }

    /**
     * The per-platform provider package document: where the zip is, where its checksums are, where their signature
     * is, and the key that signature verifies against.
     *
     * <p>The public key is declared inline because the protocol says so - a client verifies the signature before it
     * has any other way to learn which key signed it, so a document that only linked to the key would be trusting
     * the same channel it is verifying.
     */
    private void providerPackage(FormatExchange exchange, Blobs blobs, String repo, String namespace, String type,
                                 String version, String os, String arch) throws IOException {
        String file = TerraformCoordinates.providerFile(type, version, os, arch);
        String key = TerraformCoordinates.providerArchive(repo, namespace, type, version, file);
        Optional<Blobs.Located> located = blobs.locate(key);
        if (located.isEmpty() || blobs.withheld(key)) {
            exchange.respond(404);
            return;
        }
        String base = base(exchange, repo) + "/providers/" + namespace + "/" + type + "/" + version;
        StringBuilder document = new StringBuilder("{\"protocols\":[\"5.0\"],\"os\":")
                .append(MAPPER.writeValueAsString(os)).append(",\"arch\":").append(MAPPER.writeValueAsString(arch))
                .append(",\"filename\":").append(MAPPER.writeValueAsString(file))
                .append(",\"download_url\":").append(MAPPER.writeValueAsString(base + "/" + file))
                .append(",\"shasums_url\":").append(MAPPER.writeValueAsString(base + "/SHA256SUMS"))
                .append(",\"shasums_signature_url\":").append(MAPPER.writeValueAsString(base + "/SHA256SUMS.sig"))
                .append(",\"shasum\":").append(MAPPER.writeValueAsString(located.get().hash()))
                .append(",\"signing_keys\":{\"gpg_public_keys\":[");
        Optional<byte[]> publicKey = storedKey(blobs, PUBLIC_KEY);
        if (publicKey.isPresent()) {
            document.append("{\"key_id\":").append(MAPPER.writeValueAsString(keyId(blobs)))
                    .append(",\"ascii_armor\":")
                    .append(MAPPER.writeValueAsString(new String(publicKey.get(), StandardCharsets.UTF_8)))
                    .append(",\"trust_signature\":\"\",\"source\":\"Jenesis\",\"source_url\":\"\"}");
        }
        document.append("]}}");
        respondJson(exchange, document.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---- artifacts ----

    private void artifact(FormatExchange exchange, Blobs blobs, String repo, String[] path) throws IOException {
        TerraformListings listings = listings(blobs);
        if (path.length == 5 && path[0].equals("providers") && path[4].equals("SHA256SUMS")) {
            serveListing(exchange, blobs, listings.shaSumsSpec(repo, path[1], path[2], path[3]),
                    TerraformCoordinates.ROOT + repo + "/providers/" + path[1] + "/" + path[2] + "/" + path[3]);
        } else if (path.length == 5 && path[0].equals("providers") && path[4].equals("SHA256SUMS.sig")) {
            serveDerived(exchange, blobs, listings, repo, path[1], path[2], path[3]);
        } else if (path.length == 5 && path[0].equals("providers") && path[4].endsWith(PROVIDER_ARCHIVE)) {
            stream(exchange, blobs,
                    TerraformCoordinates.providerArchive(repo, path[1], path[2], path[3], path[4]),
                    "application/zip");
        } else if (path.length == 5 && path[0].equals("modules") && path[4].endsWith(MODULE_ARCHIVE)) {
            stream(exchange, blobs, TerraformCoordinates.ROOT + repo + "/modules/" + path[1] + "/" + path[2] + "/"
                    + path[3] + "/" + path[4], "application/gzip");
        } else {
            exchange.respond(404);
        }
    }

    private void stream(FormatExchange exchange, Blobs blobs, String key, String contentType) throws IOException {
        Optional<Blobs.Located> located = blobs.locate(key);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        exchange.setResponseHeader("Content-Type", contentType);
        if (exchange.method().equals("HEAD")) {
            if (located.get().size() >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(located.get().size()));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    // ---- the write path ----

    /**
     * Publish one artifact.
     *
     * <p>The bytes stream into the content-addressed store, and the store's own SHA-256 becomes the digest the
     * {@code SHA256SUMS} line declares - so what a client verifies is a fact about the bytes this repository will
     * serve rather than anything a publisher asserted alongside them.
     */
    private void push(FormatExchange exchange, Blobs blobs, String repo, String[] path) throws IOException {
        for (String segment : path) {
            if (Keys.unsafe(segment)) {
                exchange.respond(400);
                return;
            }
        }
        if (path.length == 5 && path[0].equals("modules") && path[4].endsWith(MODULE_ARCHIVE)) {
            String version = path[4].substring(0, path[4].length() - MODULE_ARCHIVE.length());
            if (version.isEmpty()) {
                exchange.respond(400);
                return;
            }
            String hash = blobs.store(exchange.requestStream());
            blobs.link(TerraformCoordinates.moduleArchive(repo, path[1], path[2], path[3], version), hash);
            listings(blobs).moduleRefresh(repo, path[1], path[2], path[3], version);
            exchange.respond(201);
        } else if (path.length == 5 && path[0].equals("providers") && path[4].endsWith(PROVIDER_ARCHIVE)) {
            if (TerraformCoordinates.platformOf(path[2], path[3], path[4]).isEmpty()) {
                // The file name IS the platform declaration - it is what the SHA256SUMS line names and what the
                // package document reports - so a name that does not carry one would publish a release no version
                // entry could describe.
                exchange.respond(400);
                return;
            }
            String hash = blobs.store(exchange.requestStream());
            blobs.link(TerraformCoordinates.providerArchive(repo, path[1], path[2], path[3], path[4]), hash);
            listings(blobs).providerRefresh(repo, path[1], path[2], path[3], path[4]);
            exchange.respond(201);
        } else {
            exchange.respond(400);
        }
    }

    // ---- the signing key ----

    TerraformListings listings(Blobs blobs) {
        return new TerraformListings(blobs, this::signerOrNull);
    }

    private OpenPgpSigner signerOrNull(Blobs blobs) {
        try {
            return signer(blobs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The repository's signer, generating a key when there is none.
     *
     * <p>Generated on the write path (a {@code SHA256SUMS} derivation follows a publish), never on a read: a
     * provider release that cannot be verified cannot be installed, so a repository with providers and no key is
     * not a state worth being able to reach.
     */
    private OpenPgpSigner signer(Blobs blobs) throws IOException {
        Optional<byte[]> stored = storedKey(blobs, SECRET_KEY);
        if (stored.isEmpty()) {
            // Established rather than written, and the published half derived from whichever secret won: two first
            // publishes racing would otherwise store one pair's secret beside another pair's public, and every
            // signature this repository makes would be refused by a client doing its job.
            OpenPgpSigner signer = new OpenPgpSigner(
                    blobs.establish(SECRET_KEY, () -> OpenPgpSigner.generate(IDENTITY, KEY_VALIDITY).secretKey()));
            blobs.write(PUBLIC_KEY, signer.publicKeyring());
            return signer;
        }
        OpenPgpSigner signer = new OpenPgpSigner(stored.get());
        return signer.dueForRotation(Instant.now(), ROTATION_WINDOW) ? rotate(blobs) : signer;
    }

    /** Rotate to a fresh key, keeping the retiring public half in the served keyring until it expires so a client
     *  that already fetched it still verifies a {@code SHA256SUMS} signed during the overlap. */
    private OpenPgpSigner rotate(Blobs blobs) throws IOException {
        OpenPgpSigner.KeyMaterial fresh = OpenPgpSigner.generate(IDENTITY, KEY_VALIDITY);
        Optional<byte[]> existing = storedKey(blobs, PUBLIC_KEY);
        blobs.write(PUBLIC_KEY, existing.isPresent()
                ? OpenPgpSigner.mergePublicKeyrings(existing.get(), fresh.publicKey(), Instant.now())
                : fresh.publicKey());
        blobs.write(SECRET_KEY, fresh.secretKey());
        return new OpenPgpSigner(fresh.secretKey());
    }

    private void provisionKey(FormatExchange exchange, Blobs blobs) throws IOException {
        signer(blobs);
        Optional<byte[]> key = storedKey(blobs, PUBLIC_KEY);
        if (key.isEmpty()) {
            exchange.respond(500);
            return;
        }
        exchange.setResponseHeader("Content-Type", "application/pgp-keys");
        exchange.respond(200, key.get());
    }

    private void servePublicKey(FormatExchange exchange, Blobs blobs) throws IOException {
        Optional<byte[]> key = storedKey(blobs, PUBLIC_KEY);
        if (key.isEmpty()) {
            exchange.respond(404);
            return;
        }
        exchange.setResponseHeader("Content-Type", "application/pgp-keys");
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Long.toString(key.get().length));
            exchange.respond(200, -1L).close();
            return;
        }
        exchange.respond(200, key.get());
    }

    private static Optional<byte[]> storedKey(Blobs blobs, String key) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        return blobs.read(key, buffer) ? Optional.of(buffer.toByteArray()) : Optional.empty();
    }

    /** The long key id the package document reports, as OpenPGP writes it: sixteen upper-case hex digits. */
    private static String keyId(Blobs blobs) throws IOException {
        Optional<byte[]> secret = storedKey(blobs, SECRET_KEY);
        return secret.isEmpty() ? "" : new OpenPgpSigner(secret.get()).keyId();
    }

    // ---- shared response shapes ----

    /**
     * Serve a stored listing, with the {@code 404} keyed on the <b>raw</b> container rather than on the servable
     * subset.
     *
     * <p>That distinction is the whole of this method. A coordinate this repository has never held is absent and
     * says so; a coordinate whose every version is withheld is <em>present with nothing to offer</em>, and answers
     * an empty document. Collapsing the two into a {@code 404} would assert "no such module" about something the
     * repository does hold - a different fact, and one a client caches - which is the same reasoning the PyPI and
     * Debian surfaces here already carry.
     */
    private void serveListing(FormatExchange exchange, Blobs blobs, StoredListing.Spec spec, String container)
            throws IOException {
        if (blobs.isEmpty(container)) {
            exchange.respond(404);   // the structural probe: nothing was ever published under this coordinate
            return;
        }
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(), spec);
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            respond(exchange, document, spec.listing().endsWith("versions")
                    ? "application/json" : "text/plain; charset=utf-8");
        }
    }

    /** The {@code SHA256SUMS.sig}, derived off the write and brought up to its source when a read arrives inside
     *  that window - a client verifies the signature against the list, so serving one older than the other is a
     *  failed install rather than a stale page. */
    private void serveDerived(FormatExchange exchange, Blobs blobs, TerraformListings listings, String repo,
                              String namespace, String type, String version) throws IOException {
        String derived = TerraformListings.shaSumsSignature(repo, namespace, type, version);
        Optional<StoredListing.Header> source = StoredListing.header(blobs.store(),
                TerraformListings.shaSums(repo, namespace, type, version));
        Optional<StoredListing.Served> served = StoredListing.openDerived(blobs.store(), derived);
        if (served.isEmpty() || source.isEmpty() || served.get().header().seq() < source.get().seq()) {
            if (served.isPresent()) {
                served.get().close();
            }
            StoredListing.rebuild(blobs.store(), listings.shaSumsSpec(repo, namespace, type, version));
            served = StoredListing.openDerived(blobs.store(), derived);
        }
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            respond(exchange, document, "application/octet-stream");
        }
    }

    private static void respond(FormatExchange exchange, StoredListing.Served served, String contentType)
            throws IOException {
        Listings.serve(exchange, served, contentType);
    }

    private static void respondJson(FormatExchange exchange, byte[] body) throws IOException {
        exchange.setResponseHeader("Content-Type", "application/json");
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Long.toString(body.length));
            exchange.respond(200, -1L).close();
            return;
        }
        exchange.respond(200, body);
    }

    // ---- proxy ----

    /** Where an upstream registry serves each protocol, as its discovery document names them. */
    private record Services(URI providers, URI modules) {
    }

    /**
     * Proxy a miss to an upstream Terraform registry. The registry is found the way a client finds one: its
     * {@code /.well-known/terraform.json} names where each protocol is served, and a registry that publishes none is
     * read at {@code v1/providers/} and {@code v1/modules/} under the configured root. A location the discovery
     * document names, and every URL a package document names, is chosen by the upstream, so each is screened
     * ({@link OutboundTargets}) before it is fetched.
     *
     * <p>The version lists of a provider and of a module are ENUMERATIONS, fetched fresh on each read; only an upstream
     * that answered 404/410 reaches the client as a 404. They name no URLs and are relayed as they are.
     *
     * <p>A provider's package document is PINNED and rewritten on the way out. Its {@code download_url} names this
     * repository's own path for the zip, and its {@code shasums_url} and {@code shasums_signature_url} name this
     * repository's paths for the sums and their signature, each carrying the platform the document was read for. The
     * sums and the signature are the upstream's, relayed fresh, and the {@code signing_keys} are left as they are,
     * so a client verifies the upstream's signature over the upstream's sums, and the zip against them. The zip
     * itself is held to the {@code shasum} of the same package document that locates it, so an unreadable document
     * leaves nothing to fetch.
     *
     * <p>A module's download names a source in {@code X-Terraform-Get}. When that source is a {@code .tar.gz} archive
     * over HTTPS, the answer names this repository's own path for it, and the archive is cached as fetched - the
     * protocol declares no checksum for a module. A git source on a host the operator lists
     * ({@link TerraformGitSource}) is fetched as its ref's archive and served the same way, with the directory inside
     * the archive named for the client; its digest is recorded on the first fetch, and a later fetch of different
     * bytes - a moved tag - is refused. Any other source is relayed as it is, or, for a git source, refused when the
     * operator has switched that on.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String[] segments = exchange.path().substring(PREFIX.length()).split("/");
        if (segments.length < 2 || Keys.unsafe(segments[0])) {
            return false;
        }
        String repo = segments[0];
        String[] path = Arrays.copyOfRange(segments, 1, segments.length);
        for (String segment : path) {
            if (Keys.unsafe(segment)) {
                return false;
            }
        }
        boolean allowInternal = ProxyLeg.allowInternalTargets(exchange);
        Services services = services(upstream, fetcher, allowInternal);
        if (path.length == 5 && path[0].equals("v1") && path[1].equals("providers") && path[4].equals("versions")) {
            return relay(exchange, fetcher, services.providers().resolve(path[2] + "/" + path[3] + "/versions"),
                    "application/json", ProxyRelay.Document.ENUMERATION);
        }
        if (path.length == 6 && path[0].equals("v1") && path[1].equals("modules") && path[5].equals("versions")) {
            return relay(exchange, fetcher,
                    services.modules().resolve(path[2] + "/" + path[3] + "/" + path[4] + "/versions"),
                    "application/json", ProxyRelay.Document.ENUMERATION);
        }
        if (path.length == 8 && path[0].equals("v1") && path[1].equals("providers") && path[5].equals("download")) {
            return proxiedPackage(exchange, fetcher, services, repo, path[2], path[3], path[4], path[6], path[7]);
        }
        if (path.length == 7 && path[0].equals("v1") && path[1].equals("modules") && path[6].equals("download")) {
            return proxiedModuleDownload(exchange, store, fetcher, services, repo, path[2], path[3], path[4], path[5],
                    allowInternal, upstream);
        }
        if (path.length == 5 && path[0].equals("providers")) {
            String namespace = path[1], type = path[2], version = path[3], file = path[4];
            if (file.equals("SHA256SUMS") || file.equals("SHA256SUMS.sig")) {
                String os = exchange.queryParameter("os"), arch = exchange.queryParameter("arch");
                if (os == null || arch == null || Keys.unsafe(os) || Keys.unsafe(arch)) {
                    return false;
                }
                JsonNode document = packageDocument(fetcher, services, namespace, type, version, os, arch);
                URI sums = document == null ? null : advertised(document, file.equals("SHA256SUMS")
                        ? "shasums_url" : "shasums_signature_url", upstream, allowInternal);
                return sums != null && relay(exchange, fetcher, sums, file.equals("SHA256SUMS")
                        ? "text/plain; charset=utf-8" : "application/octet-stream", ProxyRelay.Document.PINNED);
            }
            Optional<String[]> platform = TerraformCoordinates.platformOf(type, version, file);
            if (platform.isEmpty()) {
                return false;
            }
            JsonNode document = packageDocument(fetcher, services, namespace, type, version, platform.get()[0],
                    platform.get()[1]);
            URI zip = document == null || !file.equals(document.path("filename").asString(""))
                    ? null : advertised(document, "download_url", upstream, allowInternal);
            if (zip == null) {
                return false;
            }
            String shasum = document.path("shasum").asString("");
            ProxyRelay.Declared declared = shasum.matches("[0-9a-fA-F]{64}")
                    ? ProxyRelay.Declared.of("SHA-256", HexFormat.of().parseHex(shasum)) : ProxyRelay.Declared.NONE;
            return fill(exchange, store, fetcher, zip,
                    TerraformCoordinates.providerArchive(repo, namespace, type, version, file), declared,
                    "application/zip");
        }
        if (path.length == 5 && path[0].equals("modules") && path[4].endsWith(MODULE_ARCHIVE)) {
            String version = path[4].substring(0, path[4].length() - MODULE_ARCHIVE.length());
            URI download = services.modules().resolve(path[1] + "/" + path[2] + "/" + path[3] + "/" + version
                    + "/download");
            String source = moduleSource(fetcher, download);
            if (source == null) {
                return false;
            }
            String key = TerraformCoordinates.moduleArchive(repo, path[1], path[2], path[3], version);
            URI archive = archiveSource(download, source, allowInternal, upstream);
            if (archive != null) {
                return fill(exchange, store, fetcher, archive, key, ProxyRelay.Declared.NONE, "application/gzip");
            }
            Optional<TerraformGitSource> git = gitSource(exchange, source, allowInternal, upstream);
            return git.isPresent() && fillPinned(exchange, store, fetcher, git.get(), key, repo);
        }
        return false;
    }

    /** The upstream's protocol locations, from its discovery document or at the default paths when it has none. */
    private static Services services(URI upstream, ProxyFormat.Fetcher fetcher, boolean allowInternal)
            throws IOException {
        URI root = URI.create(upstream.toString().endsWith("/") ? upstream.toString() : upstream + "/");
        URI providers = root.resolve("v1/providers/"), modules = root.resolve("v1/modules/");
        Optional<ProxyFormat.Fetched> discovery = fetcher.fetch(root.resolve(".well-known/terraform.json"), Map.of());
        if (discovery.isPresent() && discovery.get().status() == 200) {
            try {
                JsonNode document = MAPPER.readTree(discovery.get().body());
                providers = service(document, "providers.v1", root, upstream, allowInternal, providers);
                modules = service(document, "modules.v1", root, upstream, allowInternal, modules);
            } catch (RuntimeException unreadable) {
                // not a discovery document: the default locations stand
            }
        }
        return new Services(providers, modules);
    }

    private static URI service(JsonNode document, String name, URI root, URI upstream, boolean allowInternal,
                               URI fallback) {
        String location = document.path(name).asString("");
        if (location.isEmpty()) {
            return fallback;
        }
        try {
            URI resolved = root.resolve(location.endsWith("/") ? location : location + "/");
            return OutboundTargets.mayFollow(resolved, upstream, allowInternal) ? resolved : fallback;
        } catch (IllegalArgumentException invalid) {
            return fallback;
        }
    }

    /** A provider version's package document for one platform, or {@code null} when it could not be read. */
    private static JsonNode packageDocument(ProxyFormat.Fetcher fetcher, Services services, String namespace,
                                            String type, String version, String os, String arch) throws IOException {
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(services.providers().resolve(namespace + "/" + type
                + "/" + version + "/download/" + os + "/" + arch), Map.of());
        if (fetched.isEmpty() || fetched.get().status() != 200) {
            return null;
        }
        try {
            return MAPPER.readTree(fetched.get().body());
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** A URL a package document names, screened, or {@code null}. */
    private static URI advertised(JsonNode document, String field, URI upstream, boolean allowInternal) {
        String text = document.path(field).asString("");
        if (text.isEmpty()) {
            return null;
        }
        try {
            URI url = URI.create(text);
            return url.isAbsolute() && OutboundTargets.mayFollow(url, upstream, allowInternal) ? url : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** The package document, with its URLs naming this repository's own paths. */
    private boolean proxiedPackage(FormatExchange exchange, ProxyFormat.Fetcher fetcher, Services services,
                                   String repo, String namespace, String type, String version, String os,
                                   String arch) throws IOException {
        URI url = services.providers().resolve(namespace + "/" + type + "/" + version + "/download/" + os + "/"
                + arch);
        ProxyRelay.Answer answer = ProxyRelay.fetchFresh(fetcher, url, Map.of(), exchange, ProxyRelay.Document.PINNED);
        if (!answer.answered()) {
            return answer.served();
        }
        JsonNode document;
        try {
            document = MAPPER.readTree(answer.document().body());
        } catch (RuntimeException unreadable) {
            return false;
        }
        String file = TerraformCoordinates.providerFile(type, version, os, arch);
        if (!(document instanceof ObjectNode rewritten) || !file.equals(document.path("filename").asString(""))) {
            return false;
        }
        String base = base(exchange, repo) + "/providers/" + namespace + "/" + type + "/" + version;
        String platform = "?os=" + URLEncoder.encode(os, StandardCharsets.UTF_8) + "&arch="
                + URLEncoder.encode(arch, StandardCharsets.UTF_8);
        rewritten.put("download_url", base + "/" + file);
        rewritten.put("shasums_url", base + "/SHA256SUMS" + platform);
        rewritten.put("shasums_signature_url", base + "/SHA256SUMS.sig" + platform);
        respondJson(exchange, MAPPER.writeValueAsBytes(rewritten));
        return true;
    }

    /** A module version's download: this repository's own archive path when the source is an archive it can hold. */
    private boolean proxiedModuleDownload(FormatExchange exchange, ArtifactStore store, ProxyFormat.Fetcher fetcher,
                                          Services services,
                                          String repo, String namespace, String name, String system, String version,
                                          boolean allowInternal, URI upstream) throws IOException {
        URI url = services.modules().resolve(namespace + "/" + name + "/" + system + "/" + version + "/download");
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(url, Map.of());
        if (fetched.isEmpty() || (fetched.get().status() != 204 && fetched.get().status() != 200)) {
            return false;
        }
        String source = fetched.get().header("X-Terraform-Get");
        if (source == null || source.isBlank()) {
            return false;
        }
        String served = base(exchange, repo) + "/modules/" + namespace + "/" + name + "/" + system + "/" + version
                + MODULE_ARCHIVE;
        if (archiveSource(url, source, allowInternal, upstream) == null) {
            Optional<TerraformGitSource> git = gitSource(exchange, source, allowInternal, upstream);
            if (git.isPresent()) {
                // The archive holds one top-level directory whose name the host chooses, and Terraform does not
                // expand a glob in a registry module's subdirectory - so the archive is fetched now, which the client
                // is about to ask for anyway, and the directory it holds is named.
                String key = TerraformCoordinates.moduleArchive(repo, namespace, name, system, version);
                Blobs blobs = new Blobs(store);
                Optional<String> directory = blobs.exists(key) || pinned(blobs, fetcher, git.get(), key, repo)
                        ? topDirectory(blobs, key) : Optional.empty();
                if (directory.isEmpty()) {
                    return false;
                }
                served += "//" + directory.get()
                        + (git.get().subdirectory().isEmpty() ? "" : "/" + git.get().subdirectory());
            } else if (TerraformGitSource.git(source)
                    && Boolean.parseBoolean(exchange.setting(TerraformGitSource.REFUSE))) {
                LOGGER.warn("Refusing the download of {}/{}/{} {}: its source {} is a git repository this repository "
                                + "cannot fetch - its host is not in {}, or it names no single ref - and {} is on.",
                        namespace, name, system, version, source, TerraformGitSource.HOSTS, TerraformGitSource.REFUSE);
                return false;
            } else {
                served = source;
            }
        }
        exchange.setResponseHeader("X-Terraform-Get", served);
        exchange.respond(204);
        return true;
    }

    /** {@code source} as a listed git host's archive, when it is one and the archive's URL passes the screen. */
    private static Optional<TerraformGitSource> gitSource(FormatExchange exchange, String source, boolean allowInternal,
                                                          URI upstream) {
        return TerraformGitSource.of(source, exchange.setting(TerraformGitSource.HOSTS))
                .filter(git -> OutboundTargets.mayFollow(git.archive(), upstream, allowInternal));
    }

    /** The source a module version's {@code download} names upstream, or {@code null} when it names none. */
    private static String moduleSource(ProxyFormat.Fetcher fetcher, URI download) throws IOException {
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(download, Map.of());
        if (fetched.isEmpty() || (fetched.get().status() != 204 && fetched.get().status() != 200)) {
            return null;
        }
        String source = fetched.get().header("X-Terraform-Get");
        return source == null || source.isBlank() ? null : source;
    }

    /**
     * Fetch a git ref's archive into the store under {@code key}, held to the digest its first fetch recorded, and
     * serve it. Nothing upstream declares a digest for a ref, and a tag can be moved, so the first fetch's is kept -
     * established once, so two nodes fetching at once cannot record two - and every later fetch of the same host,
     * repository and ref must produce the same bytes. One that does not is refused and said to be a moved ref.
     */
    private boolean fillPinned(FormatExchange exchange, ArtifactStore store, ProxyFormat.Fetcher fetcher,
                               TerraformGitSource git, String key, String repo) throws IOException {
        Blobs blobs = new Blobs(store);
        if (!pinned(blobs, fetcher, git, key, repo)) {
            return false;
        }
        stream(exchange, blobs, key, "application/gzip");
        return true;
    }

    /** Fetch a git ref's archive into {@code key}, held to its recorded digest as {@link #fillPinned} describes. */
    private static boolean pinned(Blobs blobs, ProxyFormat.Fetcher fetcher, TerraformGitSource git, String key,
                                  String repo) throws IOException {
        String record = TerraformCoordinates.gitDigest(repo, git.identity());
        ByteArrayOutputStream recorded = new ByteArrayOutputStream();
        boolean known = blobs.read(record, recorded);
        try (ProxyFormat.Download download = fetcher.download(git.archive(), Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                return false;
            }
            if (!known) {
                blobs.write(key, download.body());
            } else if (!blobs.writeVerified(key, download.body(), "SHA-256",
                    HexFormat.of().parseHex(recorded.toString(StandardCharsets.UTF_8).strip()))) {
                LOGGER.warn("Refusing {} fetched from {}: its bytes differ from the SHA-256 {} recorded on its first "
                                + "fetch, so the ref has moved upstream. Nothing was cached or served.", git.identity(),
                        git.archive(), recorded.toString(StandardCharsets.UTF_8).strip());
                return false;
            }
        }
        if (!known) {
            String hash = blobs.hash(key).orElseThrow(() -> new IOException("nothing was stored at " + key));
            String established = new String(blobs.establish(record, () -> hash.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8).strip();
            if (!established.equals(hash)) {
                blobs.delete(key);
                LOGGER.warn("Refusing {} fetched from {}: another fetch of the same ref recorded different bytes "
                        + "({}) a moment earlier.", git.identity(), git.archive(), established);
                return false;
            }
        }
        return true;
    }

    /** The one directory a git host's archive of a ref holds everything under, read off its first entry. */
    private static Optional<String> topDirectory(Blobs blobs, String key) throws IOException {
        Optional<Blobs.Located> located = blobs.locate(key);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GZIPInputStream(blobs.open(located.get().hash())), "UTF-8")) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry == null) {
                return Optional.empty();
            }
            int slash = entry.getName().indexOf('/');
            String directory = slash < 0 ? entry.getName() : entry.getName().substring(0, slash);
            return Keys.unsafe(directory) ? Optional.empty() : Optional.of(directory);
        } catch (IOException unreadable) {
            LOGGER.warn("The archive cached at {} is not a readable tar.gz: {}", key, unreadable.toString());
            return Optional.empty();
        }
    }

    /** {@code source} resolved against the download it came from, when it is an HTTPS {@code .tar.gz} with no getter
     *  prefix, subdirectory or query - the one shape that is simply a file to fetch - and passes the screen. */
    private static URI archiveSource(URI download, String source, boolean allowInternal, URI upstream) {
        int scheme = source.indexOf("://");
        if (scheme < 0 || source.contains("::") || source.indexOf("//", scheme + 3) >= 0 || source.contains("?")) {
            return null;
        }
        try {
            URI resolved = download.resolve(source.strip());
            String archivePath = resolved.getPath();
            return archivePath != null && (archivePath.endsWith(MODULE_ARCHIVE) || archivePath.endsWith(".tgz"))
                    && OutboundTargets.mayFollow(resolved, upstream, allowInternal) ? resolved : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Relay one document fresh. */
    private static boolean relay(FormatExchange exchange, ProxyFormat.Fetcher fetcher, URI url, String contentType,
                                 ProxyRelay.Document document) throws IOException {
        ProxyRelay.Answer answer = ProxyRelay.fetchFresh(fetcher, url, Map.of(), exchange, document);
        if (!answer.answered()) {
            return answer.served();
        }
        exchange.setResponseHeader("Content-Type", contentType);
        exchange.respond(200, answer.document().body());
        return true;
    }

    /** Fetch an artifact into the store under {@code key}, held to {@code declared}, and serve it. */
    private boolean fill(FormatExchange exchange, ArtifactStore store, ProxyFormat.Fetcher fetcher, URI url,
                         String key, ProxyRelay.Declared declared, String contentType) throws IOException {
        Blobs blobs = new Blobs(store);
        try (ProxyFormat.Download download = fetcher.download(url, Map.of()).orElse(null)) {
            if (download == null || download.status() != 200
                    || !ProxyRelay.fill(blobs, key, url, download.body(), declared)) {
                return false;
            }
        }
        stream(exchange, blobs, key, contentType);
        return true;
    }

    /** This registry's external base for the URLs the protocol documents carry. */
    private static String base(FormatExchange exchange, String repo) {
        return RequestBase.of(exchange) + exchange.external(PREFIX + repo);
    }

    // ---- layout ----

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] segments = path.substring(PREFIX.length()).split("/");
        if (segments.length == 6 && segments[1].equals("modules") && segments[5].endsWith(MODULE_ARCHIVE)) {
            return Optional.of(new ArtifactDescriptor(ECOSYSTEM,
                    TerraformCoordinates.moduleCoordinate(segments[2], segments[3], segments[4]),
                    segments[5].substring(0, segments[5].length() - MODULE_ARCHIVE.length()),
                    path, "application/gzip", false, null, -1L));
        }
        if (segments.length == 6 && segments[1].equals("providers") && segments[5].endsWith(PROVIDER_ARCHIVE)) {
            return Optional.of(new ArtifactDescriptor(ECOSYSTEM,
                    TerraformCoordinates.providerCoordinate(segments[2], segments[3]), segments[4],
                    path, "application/zip", false, null, -1L));
        }
        return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // A Terraform artifact's pointer lives in the blobs namespace rather than under publish/, so the coordinate
        // seam this format really has is BlobLayout's - see blobKeys below.
        return List.of();
    }

    @Override
    public List<String> blobRoots() {
        return List.of("terraform");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        String[] parts = coordinate.split("/");
        List<String> keys = new ArrayList<>();
        for (String repo : store.list("terraform")) {
            if (parts.length == 3) {
                String key = TerraformCoordinates.moduleArchive(repo, parts[0], parts[1], parts[2], version);
                if (store.readVersioned(key).isPresent()) {
                    keys.add(key);
                }
            } else if (parts.length == 2) {
                String prefix = TerraformCoordinates.ROOT + repo + "/providers/" + parts[0] + "/" + parts[1]
                        + "/" + version;
                for (String file : store.list(prefix)) {
                    if (store.readVersioned(prefix + "/" + file).isPresent()) {
                        keys.add(prefix + "/" + file);
                    }
                }
            }
        }
        return keys;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This layout's pointer key <em>is</em> its served path without the leading slash - {@link #servedPaths}
     * composes one from the other - so the request-path describer is already the parse, and writing a second one
     * here would be two spellings of one grammar with nothing holding them together. The description is re-keyed to
     * the pointer, because what a repair rebuilding the inventory row holds is the key, not the request path.
     *
     * <p><b>Only when the description actually names a version.</b> The two describers have different contracts:
     * {@code describe} answers about any path this format serves and falls back to a coordinate-less descriptor for
     * the indexes and checksums beside the artifacts, while this one must answer <em>empty</em> for those - a
     * repair walking the blob root asks about every key it meets, and a present descriptor with no coordinate is
     * an absence dressed as a claim. The filter is what keeps the delegation honest.
     *
     * <p>{@code BlobLayoutCoordinateSeamTest} drives this over keys this layout really wrote and over the folders
     * above them, so both halves are checked rather than asserted: if the two shapes ever stop coinciding the round
     * trip names the wrong coordinate, and if the filter goes the parent of a pointer is claimed as one.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        return describe("/" + key)
                .filter(described -> described.coordinate() != null && described.version() != null)
                .map(described -> described.withPath(key));
    }

    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        List<String> paths = new ArrayList<>();
        for (String key : blobKeys(coordinate, version, store)) {
            paths.add("/" + key);
        }
        return paths;
    }

    /** The version's module archive, or each of a provider version's platform zips, is put at its path; the
     *  target derives and signs its own {@code SHA256SUMS}. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        return BlobExport.put(repository, mount(), blobKeys(coordinate, version, repository), target);
    }
}
