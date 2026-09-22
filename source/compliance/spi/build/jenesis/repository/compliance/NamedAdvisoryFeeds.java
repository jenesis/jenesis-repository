package build.jenesis.repository.compliance;

import module java.base;

/**
 * The advisory feeds a deployment has configured, in the order they were named, each under the name an operator
 * gave it.
 *
 * <p>It is a type rather than the bare {@code SequencedMap} {@link AdvisorySource#named} answers so that a
 * container injects <em>this</em> map by type. A bare {@code Map<String, AdvisorySource>} parameter means
 * something else entirely to Spring - every {@code AdvisorySource} bean keyed by its bean name - which is a
 * different map that happens to have the same shape, and the mistake is silent.
 *
 * @param feeds the configured feeds by name, in configuration order
 */
public record NamedAdvisoryFeeds(SequencedMap<String, AdvisorySource> feeds) {

    /** Defensive: the map a caller hands in is copied, so the record a deployment shares cannot change under it. */
    public NamedAdvisoryFeeds {
        feeds = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(feeds));
    }
}
