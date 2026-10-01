/**
 * The repository definitions model: a repository's shape as {@code (writable, ordered fallbacks)}, the parser for its
 * clause grammar, the derived view the console badge reads, the outbound-target screen a write surface applies to a
 * configured upstream, and the parse-time switches the redirect modules flip so {@code redirect} and {@code dns} parse
 * only where their module is installed.
 *
 * <p>A surface that renders or validates a definition - the settings store, the configuration API, the redirect modules
 * - does not require the router and what lies behind it. The router requires this module; nothing here requires the
 * router.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.definitions {
    requires transitive build.jenesis.repository.store;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.settings;
    requires org.slf4j;
    exports build.jenesis.repository.definitions;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.definitions.RoutingSettingsContributor;
}
