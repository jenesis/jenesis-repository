package build.jenesis.repository.cli;

import module java.base;
import module java.net.http;
import module tools.jackson.databind;

/**
 * Where what a repository holds came from and what depends on it: signers and signatures, the signed build
 * attestation, a published version's resolved closure, the reverse dependencies, the bill of materials, the origin of
 * a served path, the attribution notices and the VEX statements.
 *
 * <p>Reached through {@link RepositoryClient#provenance()}.
 */
public final class ProvenanceClient extends ClientCalls {

    ProvenanceClient(ClientCalls calls) {
        super(calls);
    }

    /** A signer seen on a repository's accepted versions: its wire identity, the hash the index files it under,
     *  and for a keyless identity the issuer and subject the server shows apart. */
    public record Signer(String signer, String id, String issuer, String subject) {
    }

    /** One coordinate a signer signed: how many of its versions, since when, and the last one counted. */
    public record SignedCoordinate(String ecosystem, String coordinate, int versions, String since, String last) {
    }

    private record SignersView(List<Signer> signers, String next) {
    }

    private record SignedView(String signer, List<SignedCoordinate> coordinates, String next) {
    }

    /** The signers whose trusted signatures the gate accepted on a repository's versions - the first page of the
     *  index, as the console lists them. */
    public List<Signer> signers(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/signers?repo=" + enc(repo), null, null);
        require(response, 200, "read the signers of " + repo);
        return JSON.readValue(response.body(), SignersView.class).signers();
    }

    /** Everything one signer signed in a repository - a key's reach before it is revoked; {@code signer} is the
     *  wire form, {@code <scheme>:<value>}. */
    public List<SignedCoordinate> signedBy(String repo, String signer) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/signers/signed?repo=" + enc(repo) + "&signer=" + enc(signer),
                null, null);
        require(response, 200, "read what " + signer + " signed in " + repo);
        return JSON.readValue(response.body(), SignedView.class).coordinates();
    }

    /** What depends on {@code coordinate} of {@code ecosystem} in {@code repo} - given a {@code version}, the
     *  published versions built against it - each half one page resumed after its own cursor where one is given. */
    public Dependents dependents(String repo, String ecosystem, String coordinate, String version, String cursor,
                                 String declaredCursor) throws IOException, InterruptedException {
        String path = "/api/repository/dependents?repo=" + enc(repo) + "&ecosystem=" + enc(ecosystem)
                + "&coordinate=" + enc(coordinate);
        if (version != null && !version.isBlank()) {
            path += "&version=" + enc(version);
        }
        if (cursor != null && !cursor.isBlank()) {
            path += "&after=" + enc(cursor);
        }
        if (declaredCursor != null && !declaredCursor.isBlank()) {
            path += "&declaredAfter=" + enc(declaredCursor);
        }
        HttpResponse<String> response = send("GET", path, null, null);
        require(response, 200, "read what depends on " + ecosystem + " " + coordinate + " in " + repo);
        return JSON.readValue(response.body(), Dependents.class);
    }

    /**
     * The generated SBOM for a hosted coordinate ({@code path} set) or a whole repository ({@code path} null), in the
     * requested {@code format} (CycloneDX by default). The document is a small metadata index, so it is returned as a
     * string for the caller to print or write to a file.
     */
    public String sbom(String repo, String path, String format) throws IOException, InterruptedException {
        String query = "/api/sbom?repo=" + enc(repo);
        if (path != null && !path.isBlank()) {
            query += "&path=" + enc(path);
        }
        if (format != null && !format.isBlank()) {
            query += "&format=" + enc(format);
        }
        HttpResponse<String> response = send("GET", query, null, null);
        require(response, 200, "generate the SBOM for " + repo);
        return response.body();
    }

    /** A version's recorded signature as the API answers it: the outcome, the signer, the grade, where the material
     *  sat, the trust source's name and the same in the operator's words, and what else the material stated. */
    public record Signature(String outcome, String signer, String grade, String location, String source,
                            String admittedBy, Map<String, String> details) {
    }

    /** The signature recorded for the artifact at a path within the repository, or {@code null} when none was - a version
     *  published before signatures were checked, which is not one checked and found wanting. */
    public Signature signature(String repo, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/signature?repo=" + enc(repo) + "&path=" + enc(path), null, null);
        if (response.statusCode() == 204) {
            return null;
        }
        require(response, 200, "read the signature of " + repo + path);
        return JSON.readValue(response.body(), Signature.class);
    }

    /** One component of a version's closure: {@code repository} is empty where the version's own repository holds it,
     *  and names the repository a fallback reached otherwise. */
    public record ClosureComponent(String coordinate, String version, boolean cached, int depth, String repository) {

        public ClosureComponent {
            repository = Objects.requireNonNullElse(repository, "");
        }
    }

    /** A dependency whose subtree did not resolve, and why. */
    public record ClosureCut(String coordinate, String requirement, String reason) {

        public ClosureCut {
            requirement = Objects.requireNonNullElse(requirement, "");
        }
    }

    /** A package the version's bill names in another ecosystem, followed by coordinate across the tenant. */
    public record ClosureForeign(String ecosystem, String coordinate, String version, int depth) {
    }

    /** A version's closure as the API answers it: {@code state} is {@code RESOLVED}, {@code PARTIAL},
     *  {@code UNDECLARED}, {@code PENDING} (a release not yet resolved) or {@code CACHED} (a copy with no closure of its
     *  own); {@code resolved}, {@code kind} - {@code BILL} the bill the version carries, {@code RESOLVER} an ecosystem's
     *  resolver, {@code SCANNER} a scanner, {@code DECLARATIONS} the walk over declarations - and {@code source}, the
     *  producing source's name, are {@code null} where nothing is resolved; {@code exposure} is what the closure reaches
     *  that is held for review or carries findings. */
    public record Closure(String repository, String ecosystem, String coordinate, String version, String state,
                          String resolved, String kind, String source, boolean truncated,
                          List<ClosureComponent> components,
                          List<ClosureCut> cuts, List<ClosureForeign> foreign, ClosureExposure exposure,
                          ScreenedThrough screenedThrough) {

        /** An answer leaving a list or the source out reads as empty, so a reader needs no guard of its own. */
        public Closure {
            source = Objects.requireNonNullElse(source, "");
            components = components == null ? List.of() : List.copyOf(components);
            cuts = cuts == null ? List.of() : List.copyOf(cuts);
            foreign = foreign == null ? List.of() : List.copyOf(foreign);
        }
    }

    /** What a version was screened through: {@code basis} is {@code FEEDS} (a cached copy asked of {@code feeds}),
     *  {@code UNCOVERED} (a cached copy no enabled feed covers), {@code CLOSURE} (a published version, through its
     *  closure) or {@code NOTHING} (a published version with no closure). */
    public record ScreenedThrough(String basis, List<String> feeds) {

        public ScreenedThrough {
            feeds = feeds == null ? List.of() : List.copyOf(feeds);
        }
    }

    /** One version a closure reaches that is held for review or carries findings: {@code repository} is empty where
     *  the version's own repository holds it, {@code worst} the worst severity among {@code findings}, and
     *  {@code ecosystem} empty unless the version's bill names it in another ecosystem, a copy of which
     *  {@code repository} holds. */
    public record ClosureReached(String coordinate, String version, String repository, boolean held, int findings,
                                 String worst, List<ClosureHop> path, String ecosystem) {

        public ClosureReached {
            repository = Objects.requireNonNullElse(repository, "");
            path = path == null ? List.of() : List.copyOf(path);
            ecosystem = Objects.requireNonNullElse(ecosystem, "");
        }
    }

    /** One step of a path through a closure: a dependency at the version the closure holds. */
    public record ClosureHop(String coordinate, String version) {
    }

    /** A published version relying on the version asked about, in {@code repository} and {@code ecosystem}: the path
     *  its closure reaches that version along, from the dependency it names itself down to it, whether its closure
     *  stopped there because the version is held for review, and whether its bill names the version by coordinate in
     *  another ecosystem, relying on whichever copy its build installed. */
    public record Dependent(String repository, String ecosystem, String coordinate, String version,
                            List<ClosureHop> path, boolean cut, boolean byCoordinate) {
    }

    /** What a closure reaches that is held or carries findings, as the closure pass derived it at {@code derived};
     *  {@code null} in a {@link Closure} until it did. */
    public record ClosureExposure(String derived, int examined, long held, long vulnerable,
                                  List<ClosureReached> reached) {

        public ClosureExposure {
            reached = reached == null ? List.of() : List.copyOf(reached);
        }
    }

    /** The transitive closure the closure pass resolved for one version, as the version's document records it. */
    public Closure closure(String repo, String ecosystem, String coordinate, String version)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/repository/closure?repo=" + enc(repo) + "&ecosystem="
                + enc(ecosystem) + "&coordinate=" + enc(coordinate) + "&version=" + enc(version), null, null);
        require(response, 200, "read the closure of " + ecosystem + " " + coordinate + " " + version + " in " + repo);
        return JSON.readValue(response.body(), Closure.class);
    }

    /** The signed provenance attestation (a DSSE envelope) for a published artifact at a path within the repository. */
    public String provenance(String repo, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/provenance?repo=" + enc(repo) + "&path=" + enc(path), null, null);
        require(response, 200, "fetch provenance for " + repo + path);
        return response.body();
    }

    /** The attestation together with the verification material bound to it at signing time - the certificate chain and
     *  the transparency-log entry, each {@code null} where the signer has none - so a consumer verifies offline;
     *  {@code null} when provenance signing is not enabled (HTTP 404). */
    public ProvenanceMaterial provenanceMaterial(String repo, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/provenance?repo=" + enc(repo) + "&path=" + enc(path) + "&material", null, null);
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200, "fetch provenance material for " + repo + path);
        return JSON.readValue(response.body(), ProvenanceMaterial.class);
    }

    /** The signer's published public key (PEM), or {@code null} when provenance signing is not enabled (HTTP 404). */
    public String provenanceKey() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/provenance/key", null, null);
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200, "fetch the provenance public key");
        return response.body();
    }

    /** The certificate chain binding the signing key to an identity (PEM) for a certificate-shaped keyless signer, or
     *  {@code null} for a bare-key deployment or when signing is not enabled (HTTP 404). */
    public String provenanceCertificate() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/provenance/certificate", null, null);
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200, "fetch the provenance certificate");
        return response.body();
    }

    /** Where each path under a prefix was served from - an upstream, or a local publish. */
    public String origin(String repo, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/origin?repo=" + enc(repo) + "&path=" + enc(path), null, null);
        require(response, 200, "read the origin of " + repo + path);
        return response.body();
    }

    /** The assembled third-party attribution document for what is published. */
    public String attribution(String repo, String coordinate, String format)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/attribution?repo=").append(enc(repo));
        if (coordinate != null) {
            path.append("&coordinate=").append(enc(coordinate));
        }
        if (format != null) {
            path.append("&format=").append(enc(format));
        }
        HttpResponse<String> response = send("GET", path.toString(), null, null);
        require(response, 200, "read the attribution document for " + repo);
        return response.body();
    }

    /** The tenant's recorded VEX documents: a statement is about a product, not a repository, so there is no
     *  repository to narrow them to. */
    public String vexStatements() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/vex", null, null);
        require(response, 200, "read the VEX statements");
        return response.body();
    }

    /** One VEX statement. */
    public String vexStatement(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/vex/" + enc(id), null, null);
        require(response, 200, "read VEX statement " + id);
        return response.body();
    }

    /** Record a VEX statement from an OpenVEX or CSAF document. */
    public String addVex(String document) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/vex",
                HttpRequest.BodyPublishers.ofString(document), "application/json");
        require(response, 201, "record the VEX statement");
        return response.body();
    }

    /** Withdraw a VEX statement. */
    public void removeVex(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("DELETE", "/api/vex/" + enc(id), null, null);
        require(response, 200, "withdraw VEX statement " + id);
    }

    /** Every VEX statement as one OpenVEX document. */
    public String exportVex() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/vex/export", null, null);
        require(response, 200, "export the VEX statements");
        return response.body();
    }

    /** What depends on a version: its {@code resolved} dependents - {@code null} when no version was asked about -
     *  and its {@code declared} ones. */
    public record Dependents(String repository, String ecosystem, String coordinate, String version,
                             Resolved resolved, Declared declared) {
    }

    /** One page of the published versions built against a version; {@code next} is the cursor of the next page,
     *  {@code null} once there is none. */
    public record Resolved(List<Dependent> dependents, int examined, String next) {
    }

    /** Whether the declared index is {@code installed}, when its last full pass started - {@code null} before the
     *  first - one page of the versions declaring the package, and the cursor of the next page. */
    public record Declared(boolean installed, String built, List<Declaration> declarations, String next) {
    }

    /** One version declaring a dependency, with the requirement its manifest states - empty where it states none -
     *  and, when a version was asked about, whether that requirement {@code admits} it: {@code admits},
     *  {@code excludes} or {@code unknown}. */
    public record Declaration(String ecosystem, String coordinate, String version, String requirement, String admits) {
    }

    /** An attestation with its signing-time verification material; {@code certificateChain} and {@code transparencyLog}
     *  are {@code null} where the configured signer has none. */
    public record ProvenanceMaterial(String envelope, String certificateChain, TransparencyLog transparencyLog) {
    }

    /** The transparency-log entry recording an envelope, in the log's own shapes, so a consumer verifies offline. */
    public record TransparencyLog(String uuid, String logId, long logIndex, long integratedTime,
                                  String signedEntryTimestamp, String canonicalizedBody, InclusionProof inclusionProof) {
    }

    /** The Merkle audit path from the entry's leaf hash to the signed root the checkpoint publishes. */
    public record InclusionProof(long logIndex, long treeSize, String rootHash, List<String> hashes, String checkpoint) {
    }
}
