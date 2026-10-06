package build.jenesis.repository.compliance;

import module java.base;

/**
 * The merge of named advisory feeds that {@link AdvisorySource#resolve(SequencedMap)} answers: it screens as its
 * {@code merged} source does and keeps the names, so {@link AdvisorySource#forRepository} can narrow it to the feeds a
 * repository selects. The feeds are the same instances the attributed view holds, so their caches are shared.
 */
record NamedFeeds(SequencedMap<String, AdvisorySource> feeds, AdvisorySource merged) implements AdvisorySource {

    NamedFeeds {
        feeds = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(feeds));
    }

    @Override
    public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
        return merged.advisories(ecosystem, coordinate, version);
    }

    @Override
    public Set<String> ecosystems() {
        return merged.ecosystems();
    }

    @Override
    public Freshness freshness() {
        return merged.freshness();
    }
}
