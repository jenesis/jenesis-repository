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

    /** The reverse-dependency ("who depends on X") + CVE blast-radius query. With a {@code coordinate}, the
     *  artifacts whose recorded dependency tree names it; without one, the coordinates the index holds. Returns
     *  {@code null} when the reverse-dependency index is not installed on this deployment (HTTP 501). */
    public DependentsReport dependents(String repo, String coordinate) throws IOException, InterruptedException {
        String path = "/api/dependents?repo=" + enc(repo);
        if (coordinate != null && !coordinate.isBlank()) {
            path += "&coordinate=" + enc(coordinate);
        }
        HttpResponse<String> response = send("GET", path, null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "query dependents in " + repo);
        return JSON.readValue(response.body(), DependentsReport.class);
    }

    /** The declared tier of the reverse-dependency index: one page of the versions whose manifest declares a
     *  dependency on the package {@code dependency}, resumed after {@code cursor} - and, given a {@code version} of
     *  that package, whether each requirement admits it. Returns {@code null} when the index is not installed on this
     *  deployment (HTTP 501). */
    public DependentsReport declarations(String repo, String dependency, String version, String cursor)
            throws IOException, InterruptedException {
        String path = "/api/dependents?repo=" + enc(repo) + "&package=" + enc(dependency);
        if (version != null && !version.isBlank()) {
            path += "&version=" + enc(version);
        }
        if (cursor != null && !cursor.isBlank()) {
            path += "&after=" + enc(cursor);
        }
        HttpResponse<String> response = send("GET", path, null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "query the declarations of " + dependency + " in " + repo);
        return JSON.readValue(response.body(), DependentsReport.class);
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
    }

    /** A dependency whose subtree did not resolve, and why. */
    public record ClosureCut(String coordinate, String requirement, String reason) {
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
                          List<ClosureCut> cuts, ClosureExposure exposure, ScreenedThrough screenedThrough) {
    }

    /** What a version was screened through: {@code basis} is {@code FEEDS} (a cached copy asked of {@code feeds}),
     *  {@code UNCOVERED} (a cached copy no enabled feed covers), {@code CLOSURE} (a published version, through its
     *  closure) or {@code NOTHING} (a published version with no closure). */
    public record ScreenedThrough(String basis, List<String> feeds) {
    }

    /** One version a closure reaches that is held for review or carries findings: {@code repository} is empty where
     *  the version's own repository holds it, and {@code worst} the worst severity among {@code findings}. */
    public record ClosureReached(String coordinate, String version, String repository, boolean held, int findings,
                                 String worst, List<ClosureHop> path) {
    }

    /** One step of a path through a closure: a dependency at the version the closure holds. */
    public record ClosureHop(String coordinate, String version) {
    }

    /** A published version relying on the version asked about, in {@code repository}: the path its closure reaches
     *  that version along, from the dependency it names itself down to it, and whether its closure stopped there
     *  because the version is held for review. */
    public record Dependent(String repository, String coordinate, String version, List<ClosureHop> path,
                            boolean cut) {
    }

    /** One page of the published versions relying on a version; {@code next} is the cursor of the next page,
     *  {@code null} once there is none. */
    public record ReliedOn(String repository, String ecosystem, String coordinate, String version,
                           List<Dependent> dependents, int examined, String next) {
    }

    /** One page of the published versions of the tenant relying on a version {@code repo} holds, after
     *  {@code cursor} where one is given. */
    public ReliedOn reliedOn(String repo, String ecosystem, String coordinate, String version, String cursor)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/repository/relied-on?repo=" + enc(repo) + "&ecosystem="
                + enc(ecosystem) + "&coordinate=" + enc(coordinate) + "&version=" + enc(version)
                + (cursor == null || cursor.isBlank() ? "" : "&after=" + enc(cursor)), null, null);
        require(response, 200, "read what relies on " + ecosystem + " " + coordinate + " " + version + " in "
                + repo);
        return JSON.readValue(response.body(), ReliedOn.class);
    }

    /** What a closure reaches that is held or carries findings, as the closure pass derived it at {@code derived};
     *  {@code null} in a {@link Closure} until it did. */
    public record ClosureExposure(String derived, int examined, long held, long vulnerable,
                                  List<ClosureReached> reached) {
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

    /** A reverse-dependency answer: with a {@code coordinate}, the {@code dependents} pulling it in; without one,
     *  the {@code coordinates} the index holds; with a package, the versions that {@code declared} a dependency on
     *  it and the {@code nextDeclaredCursor} past them. The unanswered parts are {@code null}. */
    public record DependentsReport(String coordinate, List<String> dependents, List<String> coordinates,
                                   List<Declaration> declared, String nextDeclaredCursor) {
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
