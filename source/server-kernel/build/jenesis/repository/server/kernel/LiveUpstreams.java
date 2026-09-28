package build.jenesis.repository.server.kernel;

import module java.base;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.FormatDispatcher;

/**
 * The pull-through upstream table as a live view for {@code FormatDispatcher}: a lookup by tenant and format name
 * answers the upstream the operator named for that tenant, else for the deployment ({@code format-upstream.<format>},
 * editable over {@code /api/upstreams}, or {@code jenrepo.proxy.<format>}), and {@code null} for a format nobody named
 * one for and for everything when the deployment's proxy switch is off - read through {@link LiveConfig} on every
 * request, so a settings change applies on the next fetch without a restart. The dispatcher calls {@link #upstream};
 * the map face ({@link #get}, {@link #entrySet}) is the deployment's own table, for any other reader.
 *
 * <p><b>A format's public registry is offered, never assumed.</b> Nothing this product does reaches a third party
 * because it was installed, so a format's {@link ProxyFormat#defaultUpstream() declared public registry} is what the
 * console offers to name - one click away - and not a place a miss is fetched from until somebody does.
 */
public final class LiveUpstreams extends AbstractMap<String, URI> implements FormatDispatcher.Upstreams {

    private final LiveConfig live;
    private final List<RepositoryFormat> formats;

    public LiveUpstreams(LiveConfig live, List<RepositoryFormat> formats) {
        this.live = live;
        this.formats = formats;
    }

    @Override
    public URI get(Object key) {
        return key instanceof String format ? upstream(null, format) : null;
    }

    @Override
    public URI upstream(String tenant, String format) {
        if (!live.proxy()) {
            return null;
        }
        String named = live.formatUpstream(tenant, format);
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
