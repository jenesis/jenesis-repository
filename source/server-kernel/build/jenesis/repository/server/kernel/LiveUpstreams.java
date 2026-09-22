package build.jenesis.repository.server.kernel;

import module java.base;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;

/**
 * The pull-through upstream table as a live view for {@code FormatDispatcher}: a lookup by format name
 * answers the runtime override ({@code format-upstream.<format>}, editable over {@code /api/settings}) or the
 * format's own {@link ProxyFormat#defaultUpstream() declared default}, and {@code null} for everything when the
 * deployment's proxy switch is off - read through {@link LiveConfig} on every request, so a settings change
 * applies on the next fetch without a restart, where the static map is fixed at boot. The dispatcher
 * only ever calls {@link #get}; {@link #entrySet} materializes a snapshot for any other reader.
 */
public final class LiveUpstreams extends AbstractMap<String, URI> {

    private final LiveConfig live;
    private final List<RepositoryFormat> formats;

    public LiveUpstreams(LiveConfig live, List<RepositoryFormat> formats) {
        this.live = live;
        this.formats = formats;
    }

    @Override
    public URI get(Object key) {
        if (!live.proxy() || !(key instanceof String format)) {
            return null;
        }
        String override = live.formatUpstream(format);
        if (override != null && !override.isBlank()) {
            return URI.create(override);
        }
        for (RepositoryFormat candidate : formats) {
            if (candidate.name().equals(format) && candidate instanceof ProxyFormat proxy) {
                return proxy.defaultUpstream().orElse(null);
            }
        }
        return null;
    }

    @Override
    public Set<Entry<String, URI>> entrySet() {
        Map<String, URI> snapshot = new LinkedHashMap<>();
        for (RepositoryFormat format : formats) {
            URI upstream = get(format.name());
            if (upstream != null) {
                snapshot.put(format.name(), upstream);
            }
        }
        return snapshot.entrySet();
    }
}
