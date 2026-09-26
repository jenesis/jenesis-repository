package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The {@link ComplianceGate.Subject} shapes a {@link QualityInspector} hands the gate, assembled once instead of per
 * format. Sixteen inspectors read sixteen different artifact layouts, but they emit only a handful of subject
 * shapes, and each had re-spelled them: the coordinate-only subject a path-derived leg returns, the same subject
 * carrying the one SPDX-ish identifier the manifest declared (with the identical "absent or blank declares nothing"
 * trimming repeated verbatim in the Cargo, PyPI, RPM and Conda inspectors), the list form for a manifest that
 * declares several, and the content-scan subject the secret and attestation inspectors stamp their findings onto.
 *
 * <p>The builder is immutable: every {@code license} call returns a new instance, and {@code subject} freezes the
 * result, so a shared inspector instance can build subjects on many request threads at once without a shared
 * mutable field between them - which the {@link QualityInspector} thread-safety clause requires.
 *
 * <h2>Coordinates are the format's own, and are guarded</h2>
 * The coordinate and version are the inspector's business - read from the request path or from a manifest - and this
 * class never invents them. What it does share is {@link #unsafeSegment(String)}: the guard four inspectors had
 * copied for rejecting a path segment the format itself would never have stored or served, so a screened coordinate
 * always equals a servable one.
 */
public final class ManifestSubjectBuilder {

    private final String ecosystem;
    private final List<ComplianceGate.DeclaredLicense> licenses;
    private final List<Maintainer> maintainers;
    private final List<ComplianceGate.Dependency> dependencies;

    private ManifestSubjectBuilder(String ecosystem, List<ComplianceGate.DeclaredLicense> licenses,
                                   List<Maintainer> maintainers) {
        this(ecosystem, licenses, maintainers, null);
    }

    private ManifestSubjectBuilder(String ecosystem, List<ComplianceGate.DeclaredLicense> licenses,
                                   List<Maintainer> maintainers, List<ComplianceGate.Dependency> dependencies) {
        this.ecosystem = ecosystem;
        this.licenses = licenses;
        this.maintainers = maintainers;
        this.dependencies = dependencies;
    }

    /**
     * Subjects for {@code ecosystem} - the advisory-feed namespace this inspector's coordinates are keyed in
     * ({@code npm}, {@code Maven}, {@code Packagist}, {@code conda}, ...), matching the format module's own
     * {@code ecosystem()} so a screened coordinate and a served one are looked up under the same name.
     */
    public static ManifestSubjectBuilder of(String ecosystem) {
        return new ManifestSubjectBuilder(Objects.requireNonNull(ecosystem, "ecosystem"), List.of(), List.of());
    }

    /** Whom the manifest names as responsible for the artifact; one named by nothing at all is dropped. */
    public ManifestSubjectBuilder maintainer(Maintainer maintainer) {
        if (maintainer == null || (maintainer.name() == null && !maintainer.addressable())
                || maintainers.contains(maintainer)) {
            return this;
        }
        List<Maintainer> named = new ArrayList<>(maintainers);
        named.add(maintainer);
        return new ManifestSubjectBuilder(ecosystem, licenses, List.copyOf(named), dependencies);
    }

    public ManifestSubjectBuilder maintainers(Collection<Maintainer> named) {
        ManifestSubjectBuilder built = this;
        for (Maintainer maintainer : named == null ? List.<Maintainer>of() : named) {
            built = built.maintainer(maintainer);
        }
        return built;
    }

    /**
     * That the manifest was read for what it depends on, whether or not it declares anything - what makes a subject
     * with no dependencies read as "depends on nothing" rather than as "nobody looked". {@link #dependency} implies it.
     */
    public ManifestSubjectBuilder readsDependencies() {
        return dependencies != null ? this
                : new ManifestSubjectBuilder(ecosystem, licenses, maintainers, List.of());
    }

    /**
     * What the manifest declares the artifact depends on: a package's coordinate and the requirement it states, one
     * entry per declaration. A blank coordinate is dropped, and a coordinate declared twice - as a runtime and a
     * development dependency, say - is kept once, with its first requirement.
     */
    public ManifestSubjectBuilder dependency(String coordinate, String requirement) {
        ManifestSubjectBuilder read = readsDependencies();
        if (coordinate == null || coordinate.isBlank()
                || read.dependencies.stream().anyMatch(declared -> declared.coordinate().equals(coordinate.strip()))) {
            return read;
        }
        List<ComplianceGate.Dependency> declared = new ArrayList<>(read.dependencies);
        declared.add(new ComplianceGate.Dependency(coordinate.strip(), requirement == null ? "" : requirement.strip()));
        return new ManifestSubjectBuilder(ecosystem, licenses, maintainers, List.copyOf(declared));
    }

    /** The dependencies declared so far, or {@code null} when the manifest was not read for them. */
    public List<ComplianceGate.Dependency> dependencies() {
        return dependencies;
    }

    /** Whom the manifest named so far. */
    public List<Maintainer> named() {
        return maintainers;
    }

    /**
     * This builder plus the single SPDX-ish identifier a manifest declared, or unchanged when it declares none: an
     * absent, null or blank value is "declares nothing", never a licence named {@code ""}. The identifier is
     * trimmed, because manifests routinely carry surrounding whitespace and the licence policy matches on the id.
     */
    public ManifestSubjectBuilder license(String identifier) {
        return identifier == null || identifier.isBlank()
                ? this
                : license(identifier.trim(), null);
    }

    /**
     * This builder plus a licence declared as a name and/or a URL - the legacy shape a {@code .nuspec}
     * ({@code <license>} plus {@code <licenseUrl>}), an npm {@code {type, url}} entry or a POM {@code <license>}
     * carries. Unchanged when neither is present, since a licence with no name and no URL declares nothing.
     */
    public ManifestSubjectBuilder license(String name, String url) {
        if ((name == null || name.isBlank()) && (url == null || url.isBlank())) {
            return this;
        }
        List<ComplianceGate.DeclaredLicense> declared = new ArrayList<>(licenses);
        declared.add(new ComplianceGate.DeclaredLicense(name, url));
        return new ManifestSubjectBuilder(ecosystem, List.copyOf(declared), maintainers, dependencies);
    }

    /**
     * This builder plus each identifier a manifest declaring several carries (a Composer {@code license} array, a
     * gemspec's {@code licenses}), each trimmed, with the blank and null ones dropped exactly as
     * {@link #license(String)} drops a single blank one.
     */
    public ManifestSubjectBuilder licenses(Collection<String> identifiers) {
        ManifestSubjectBuilder built = this;
        for (String identifier : identifiers == null ? List.<String>of() : identifiers) {
            built = built.license(identifier);
        }
        return built;
    }

    /** The licences declared so far - the value {@link #subject(String, String)} will stamp onto the subject. */
    public List<ComplianceGate.DeclaredLicense> declared() {
        return licenses;
    }

    /**
     * The single-subject result an inspector returns for one artifact: its coordinate, its version and whatever
     * licences were declared. Returned as the immutable one-element list the SPI's {@code inspect} methods answer
     * with, so a caller never sees a list it could mutate under the gate.
     */
    public List<ComplianceGate.Subject> subject(String coordinate, String version) {
        return List.of(new ComplianceGate.Subject(ecosystem, coordinate, version, licenses)
                .withMaintainers(maintainers).withDependencies(dependencies));
    }

    /**
     * The same subject, marked with where its coordinate sits on a resolved dependency graph - the shape the Maven
     * inspector emits, which alone resolves a closure and can therefore say a subject is the artifact itself
     * ({@link ComplianceGate.Reachability#root}) or a dependency reached along a named path. Every other inspector
     * covers a tree artifact by artifact and uses {@link #subject(String, String)}, whose subjects carry the
     * unknown-reachability default.
     */
    public List<ComplianceGate.Subject> subject(String coordinate, String version,
                                                ComplianceGate.Reachability reachability) {
        return List.of(new ComplianceGate.Subject(ecosystem, coordinate, version, licenses, reachability)
                .withMaintainers(maintainers).withDependencies(dependencies));
    }

    /**
     * A <em>content-scan</em> subject: one derived from an artifact's bytes rather than from a package coordinate,
     * so it carries no licensable identity - the artifact's {@code location} stands in for the coordinate and the
     * version is empty. The secret and attestation inspectors both build exactly this and then stamp their finding
     * on it with {@link ComplianceGate.Subject#withSecrets} / {@link ComplianceGate.Subject#withAttestation}, which
     * is what makes {@link ComplianceGate.Subject#contentScan()} true and keeps the licence dimension from raising a
     * bogus "no license declared" beside a content finding.
     */
    public static ComplianceGate.Subject contentScan(String ecosystem, String location) {
        return new ComplianceGate.Subject(ecosystem, location, "", List.of());
    }

    /**
     * Whether a coordinate or version segment read out of a request path is one the format would never have stored
     * or served: empty, a {@code .}/{@code ..} traversal segment, or carrying a path separator or a control
     * character. An inspector screens nothing for such a segment rather than inventing a coordinate the repository
     * does not hold - the same guard the format modules apply to the request path, shared here so the inspector and
     * the format cannot drift apart on it.
     */
    public static boolean unsafeSegment(String value) {
        return !ArtifactStore.safeSegment(value);
    }
}
