package build.jenesis.repository.dependency;

import module java.base;

/**
 * One node of a parsed dependency graph: the artifact the SBOM describes, or a dependency it resolved. {@code ref} is
 * the document-local {@code bom-ref} edges point at; {@code purl} is the ecosystem-neutral package URL
 * ({@code pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1}), which {@link #coordinate()} prefers because a "who
 * depends on X" or blast-radius query keys on it. {@code group}/{@code name}/{@code version} are the same coordinate in
 * parts, and {@code sha256} the content hash when recorded. Every field but {@code name} may be {@code null}.
 *
 * <p>{@code licenses} are what this component declares - CycloneDX records them per component, so an SBOM names the
 * licence of every dependency in the closure, with SPDX identifiers where the emitter matched one. Empty means declares
 * nothing, never an error.
 */
public record DependencyComponent(String ref, String group, String name, String version, String purl, String sha256,
                                  List<DependencyLicense> licenses) {

    public DependencyComponent {
        Objects.requireNonNull(ref, "ref");
        licenses = List.copyOf(licenses);
    }

    /** A component whose SBOM declared no licences, for consumers that read a BOM for its graph alone. */
    public DependencyComponent(String ref, String group, String name, String version, String purl, String sha256) {
        this(ref, group, name, version, purl, sha256, List.of());
    }

    /** A stable coordinate for this component: the {@code purl} when recorded, else {@code group:name:version} from the
     *  parts (a missing group or version drops its segment), so two SBOMs naming one dependency by the same purl name
     *  it alike. */
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
