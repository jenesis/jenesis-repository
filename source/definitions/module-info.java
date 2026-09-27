/**
 * The repository definitions model: a repository's shape as {@code (writable, ordered fallbacks)}, the parser that
 * produces it from the clause grammar ({@code writable} / {@code fallback <source> [options]}), the derived view the
 * console badge reads, the outbound-target screen a write surface applies to a configured upstream, and the two
 * parse-time switches the redirect modules flip so a {@code redirect} serve token or a {@code dns} source keyword
 * parses only where the module that serves it is installed.
 *
 * <p>It exists so that a surface that renders or validates a definition - the console's settings store, the
 * configuration API, the three redirect modules - does not require the router and with it the gate, the compliance
 * SPI, the inventory, the metadata store and the maintenance seam, none of which such a use resolves anything
 * through. What the model needs is the shared outbound-target rule ({@code blobs}), the cleartext rule ({@code
 * settings})
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
