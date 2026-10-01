/**
 * The compliance gate's contract half: the vocabulary every module that reacts to a hold reads, and nothing that
 * screens, replays or reviews - the {@link build.jenesis.repository.gate.HoldReleaseObserver} and
 * {@link build.jenesis.repository.gate.RetroLicensePlanner} SPIs, the durable hold records, the quarantine log, the
 * dispatch context a held upload is replayed from, the placing of a retroactive hold, the guarded clear of a withhold
 * marker and the questions a release asks of the holds it is not lifting. The implementation is the module
 * {@code build.jenesis.repository.gate}, which requires this one, so a module needing only the vocabulary does not get
 * the review queue and the screen with it.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.gate.spi {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.events;
    requires build.jenesis.repository.inventory;
    requires org.slf4j;
    exports build.jenesis.repository.gate;
    uses build.jenesis.repository.gate.RetroLicensePlanner;
    uses build.jenesis.repository.gate.HoldReleaseObserver;
}
