package build.jenesis.repository.dependency;

import module java.base;

/**
 * A single node in a parsed dependency graph: a component the CycloneDX SBOM names, either the artifact the SBOM
 * describes (the {@code metadata.component}) or one of the dependencies it resolved. {@code ref} is the
 * document-local {@code bom-ref} the edges point at; {@code purl} is the ecosystem-neutral <em>package URL</em>
 * (e.g. {@code pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1}) that identifies the same coordinate across
 * every repository, which is why {@link #coordinate()} prefers it - it is what a "who depends on X" / CVE
 * blast-radius query keys on. {@code group}/{@code name}/{@code version} carry the same coordinate in its parts, and
 * {@code sha256} is the component's content hash when the SBOM records one. Every field but {@code name} may be
 * {@code null} when the SBOM omits it.
 *
 * <p>{@code licenses} are the licences <em>this component</em> declares. CycloneDX records them per component, which
 * is what makes an SBOM a strictly richer declaration source than a jar's single-string OSGi {@code Bundle-License}
 * header: the document names not only what the artifact is licensed under but what every resolved dependency in its
 * closure is, and it names them with SPDX identifiers where the emitter could match one. Empty when the SBOM
 * declares none - which is a genuine "declares nothing", never an error.
 */
public record DependencyComponent(String ref, String group, String name, String version, String purl, String sha256,
                                  List<DependencyLicense> licenses) {

    public DependencyComponent {
        Objects.requireNonNull(ref, "ref");
        licenses = List.copyOf(licenses);
    }

    /** A component whose SBOM declared no licences - the shape every consumer that reads a BOM for its graph alone
     *  builds, so an SBOM model gaining licences costs a coordinate-only caller nothing. */
    public DependencyComponent(String ref, String group, String name, String version, String purl, String sha256) {
        this(ref, group, name, version, purl, sha256, List.of());
    }

    /**
     * A stable, comparable coordinate for this component: the {@code purl} when the SBOM records one (the canonical,
     * cross-ecosystem identifier), otherwise {@code group:name:version} assembled from the parts (a {@code group}less
     * or {@code version}less component drops that segment). This is the key a reverse-dependency index groups on, so
     * two SBOMs that name the same dependency by the same purl collapse onto one node.
     */
    public String coordinate() {
        if (purl != null && !purl.isBlank()) {
            return purl.trim();
        }
        StringBuilder builder = new StringBuilder();
        if (group != null && !group.isBlank()) {
            builder.append(group.trim()).append(':');
        }
        builder.append(name == null ? "" : name.trim());
        if (version != null && !version.isBlank()) {
            builder.append(':').append(version.trim());
        }
        return builder.toString();
    }
}
