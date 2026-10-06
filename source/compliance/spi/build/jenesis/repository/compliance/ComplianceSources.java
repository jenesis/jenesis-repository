package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.LiveResolution;

/**
 * The compliance sources the deployment's settings switch on, as they stand now: the advisory feeds by name and merged,
 * the maintainer-health source, the advisory signals a report shows beside a finding, and the provenance signer.
 *
 * <p>Each is resolved through its contract's own home over the live settings and held in a {@link LiveResolution}, so
 * a feed switched on, re-pointed or given its credential is the one the gate screens with from the next ask, with no
 * restart, while a write to an unrelated setting keeps every source and its warm cache. A caller asks at the moment it
 * uses a source rather than keeping one, and compares an answer to its contract's neutral element - {@link
 * AdvisorySource#none()}, {@link HealthSource#none()} - to tell a deployment with nothing switched on.
 *
 * <p>It owns what it resolved: a source it replaces is closed when it is {@link AutoCloseable}, and {@link #close()}
 * closes every source it holds, so a composition closes it with its context.
 */
public final class ComplianceSources implements AutoCloseable {

    private final LiveResolution<SequencedMap<String, AdvisorySource>> feeds;
    private final LiveResolution<HealthSource> health;
    private final LiveResolution<List<AdvisorySignal>> signals;
    private final LiveResolution<ProvenanceSigner> provenance;
    private volatile Merged merged;

    /** The sources {@code config}, the deployment's live settings lookup by bare key, switches on. */
    public ComplianceSources(UnaryOperator<String> config) {
        feeds = new LiveResolution<>(config, AdvisorySource::named, SequencedMap::values);
        health = new LiveResolution<>(config, HealthSource::resolve);
        signals = new LiveResolution<>(config, AdvisorySignal::resolve, list -> list);
        provenance = new LiveResolution<>(config, ProvenanceSignerProvider::resolve);
    }

    /** The advisory feeds switched on, by the name each reports its findings under, in their attributed order. */
    public SequencedMap<String, AdvisorySource> advisoryFeeds() {
        return feeds.get();
    }

    /** The advisory feeds switched on, merged into the one source the gate screens with - which a repository's
     *  {@value AdvisorySource#SELECTION} narrows - and {@link AdvisorySource#none()} when none is. */
    public AdvisorySource advisories() {
        SequencedMap<String, AdvisorySource> current = advisoryFeeds();
        Merged last = merged;
        if (last == null || last.feeds() != current) {
            last = new Merged(current, AdvisorySource.resolve(current));
            merged = last;
        }
        return last.source();
    }

    /** The maintainer-health source switched on; {@link HealthSource#none()} when none is. */
    public HealthSource health() {
        return health.get();
    }

    /** The advisory signals switched on, each a column a report shows beside a finding. */
    public List<AdvisorySignal> advisorySignals() {
        return signals.get();
    }

    /** The provenance signer the settings configure. */
    public ProvenanceSigner provenanceSigner() {
        return provenance.get();
    }

    /** Close every source held. */
    @Override
    public void close() {
        feeds.close();
        health.close();
        signals.close();
        provenance.close();
    }

    /** The merged source of one resolution of the feeds, kept while that resolution stands. */
    private record Merged(SequencedMap<String, AdvisorySource> feeds, AdvisorySource source) {
    }
}
