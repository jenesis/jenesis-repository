/**
 * The repository definitions model: a repository's shape as {@code (writable, ordered fallbacks)}, the parser that
 * produces it from both the legacy ({@code hosted}, {@code proxy <url>}, {@code group a,b}) and the clause
 * ({@code writable} / {@code fallback <source> [options]}) spelling, the derived views the console badges read, the
 * outbound-target screen a write surface applies to a configured upstream, and the two parse-time switches the
 * redirect modules flip so a {@code redirect} serve token or a {@code dns} source keyword parses only where the
 * module that serves it is installed.
 *
 * <p>It exists because the model was a nested type of the router until 2026-09-20, so every surface that rendered
 * or validated a definition - the console's settings store, the configuration API, the three redirect modules -
 * required the router and with it the gate, the compliance SPI, the inventory, the metadata store and the
 * maintenance seam. Measured that day: thirty-seven uses across those modules, none of which resolved anything.
 * What the model needs is the shared outbound-target rule ({@code blobs}), the cleartext rule ({@code settings})
 * and the artifact descriptor a {@code match=} predicate reads ({@code store}). The router requires this module and
 * walks the record; nothing here requires the router.
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
}
