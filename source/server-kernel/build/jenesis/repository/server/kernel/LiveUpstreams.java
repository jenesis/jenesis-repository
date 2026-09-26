package build.jenesis.repository.server.kernel;

import module java.base;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;

/**
 * The pull-through upstream table as a live view for {@code FormatDispatcher}: a lookup by format name
 * answers the upstream the operator named ({@code format-upstream.<format>}, editable over {@code /api/settings}, or
 * {@code jenreg.proxy.<format>}), and {@code null} for a format nobody named one for and for everything when the
 * deployment's proxy switch is off - read through {@link LiveConfig} on every request, so a settings change
 * applies on the next fetch without a restart, where the static map is fixed at boot. The dispatcher
 * only ever calls {@link #get}; {@link #entrySet} materializes a snapshot for any other reader.
 *
 * <p><b>A format's public registry is offered, never assumed.</b> Nothing this product does reaches a third party
 * because it was installed, so a format's {@link ProxyFormat#defaultUpstream() declared public registry} is what the
 * console offers to name - one click away - and not a place a miss is fetched from until somebody does.
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
        String named = live.formatUpstream(format);
        return named == null || named.isBlank() ? null : URI.create(named);
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
