/**
 * Credential usage tracking as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.server.spi.KeyUsageTrackerProvider} answering to {@code batching}, accumulating an
 * allowed request's tenant, key hash and source address on a bounded queue off the request path and flushing each
 * credential's count and last use through the authorization store at most once per day. A deployment without this
 * module records no usage and its health surface reports the worker as off.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.usage {
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.observation;
    exports build.jenesis.repository.usage to build.jenesis.repository.test,
            build.jenesis.repository.server.e2e, build.jenesis.repository.usage.test;
    provides build.jenesis.repository.server.spi.KeyUsageTrackerProvider
            with build.jenesis.repository.usage.BatchingKeyUsageTrackerProvider;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.usage.KeyUsageObservability;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.usage.UsageSettingsContributor;
}
