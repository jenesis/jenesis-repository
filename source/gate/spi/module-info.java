/**
 * The compliance gate's contract half: the vocabulary every module that reacts to a hold reads, and nothing that
 * screens, replays or reviews. The two SPIs - {@link build.jenesis.repository.gate.HoldReleaseObserver}, run when a
 * reviewer releases or discards a held path, and {@link build.jenesis.repository.gate.RetroLicensePlanner}, the
 * dry-run seam for retroactive licence enforcement - with their discovery statics; the durable hold records
 * ({@code HoldRecords}, {@code HoldKind}, the KEV and licence kinds), the {@code QuarantineLog} the screen writes and
 * every review surface reads, the dispatch context a held upload is replayed from, the one way a sweep places a
 * retroactive hold, the guarded clear of a withhold marker, and the two questions a release asks of the holds it
 * is not lifting ({@code HeldElsewhere}).
 *
 * <p>It was one module with the screen until 2026-09-20. Six modules required it for the vocabulary alone - the
 * findings ledger, forwarding, reachability, the licence and scan sweeps, the router - and got the review queue, the
 * compliance screen, the inspection merge, a retention pass and two walk consumers with it. Those are
 * {@code build.jenesis.repository.gate} now - the implementation keeps the module <em>name</em>, because a settings
 * contributor's module names the document its dials are stored in and a namespace declaration's module names its
 * manifest entry, and both live on that side - and this contract half is {@code build.jenesis.repository.gate.spi}
 * over the package {@code build.jenesis.repository.gate}, so a provider of either SPI and every reader of the
 * records is unchanged. The implementation requires this module; nothing here requires it back.
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
