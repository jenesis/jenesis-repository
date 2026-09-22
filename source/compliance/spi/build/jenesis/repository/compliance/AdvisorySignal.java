package build.jenesis.repository.compliance;

import module java.base;

/**
 * One provider-derived column of the vulnerability report: a named signal (the CISA known-exploited flag, an EPSS
 * probability, a vendor score) evaluated over the report's advisories. The report renders whatever columns the
 * installed modules contribute - remove the module and its column disappears - so the neutral report, console and
 * CLI name no signal. Evaluation is batch-shaped: a network-backed signal answers one query for the whole report
 * rather than one per advisory. A signal module contributes an implementation through {@link SignalSourceProvider},
 * opting into this contract by implementing it - usually on the same created object as its typed gate source, so the
 * gate and the report answer from one client and one config read; {@link #resolve} orders the enabled columns.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One shared instance renders every report, console panel and CLI table, concurrently.
 *     {@link #name}, {@link #label} and {@link #order} are constant declarations.</li>
 * <li><b>Idempotency / replay.</b> {@link #evaluate} is a pure projection of the advisories it is handed: the same
 *     list yields equal values, and evaluating does not mutate the argument, the signal or any stored state.</li>
 * <li><b>Absence sentinel.</b> {@link Value#ABSENT} is the blank cell - a positive "this signal has nothing to say
 *     about this advisory", never {@code null} and never a fabricated zero-with-display. The returned list is
 *     <em>index-aligned</em> with the input and therefore always the same length: a shorter list would silently
 *     re-associate every value after the gap with the wrong advisory.</li>
 * <li><b>Error visibility (&sect;9) - fail soft.</b> A report column is a display, so a signal that cannot answer
 *     renders {@link Value#ABSENT} rather than failing the report. The blast radius is bounded by that: a lost value
 *     can only under-report a concern, never hide a served artifact or a hold, both of which are the gate's business
 *     and decided elsewhere. A signal that also answers a gate contract on the same object (the known-exploited
 *     catalogue does) still owes that contract's own, stricter, fail mode there.</li>
 * <li><b>Bounded work (&sect;12).</b> Evaluation is batch-shaped by design: a network-backed signal answers one
 *     query for the whole report rather than one per advisory, so a report of a thousand advisories costs a bounded
 *     number of upstream calls rather than a thousand.</li>
 * <li><b>Read purity (&sect;10).</b> The intent is that rendering a column reads stored state. <b>Met by the
 *     known-exploited column</b>, which renders the mirrored catalogue's committed snapshot; the EPSS column still
 *     evaluates by consulting its underlying feed, so re-rendering a report can re-query a vendor. Recorded rather
 *     than implied (&sect;13).</li>
 * <li><b>Staleness.</b> {@link SignalSource#freshness()} carries it, and this is the contract that most needed it:
 *     a column of blanks is ambiguous between "nothing to report" and "the signal could not be consulted", which is
 *     exactly the &sect;10 ambiguity the staleness rule exists to remove. A report renders the column's freshness
 *     beside the column - its {@link Freshness#refreshed() instant} as the "as of" of every cell, and a column whose
 *     signal is not {@link Freshness#authoritative() authoritative} marked as unavailable rather than blank, since
 *     blank claims the signal looked and found nothing. A signal that also answers a gate contract on the same
 *     object reports one freshness for both: it is the same fetch behind both answers.</li>
 * <li><b>Ordering / determinism.</b> {@link #resolve} sorts by {@link #order} and then by {@link #name}, so the
 *     column order is fixed by declaration and never by discovery order; two signals may share an {@link #order} and
 *     are then separated by name. {@link #name} is the stable machine key - a persisted finding refers to it - while
 *     {@link #label} is display text and may change.</li>
 * <li><b>Tenant scoping (&sect;6).</b> None: a signal is a deployment-wide column over public data.</li>
 * </ol>
 */
public interface AdvisorySignal extends SignalSource {

    /** The stable machine key of this signal's column, e.g. {@code known-exploited}, {@code epss}. */
    String name();

    /** The column heading, e.g. {@code Known exploited}, {@code EPSS}. */
    String label();

    /** The column position; lower renders first. The report also sorts its lines by each signal's strongest
     *  {@link Value#rank() rank} in this order, so the most urgent signal drives the ordering. */
    int order();

    /** One value per advisory, index-aligned with the input; {@link Value#ABSENT} where the signal has nothing to
     *  say. */
    List<Value> evaluate(List<AdvisorySource.Advisory> advisories);

    /** A signal's value for one advisory: the preformatted display text and a sortable rank (higher is more
     *  urgent), so a renderer never re-parses the display. */
    record Value(String display, double rank) {

        /** The blank cell: renders empty and sorts last. */
        public static final Value ABSENT = new Value("", 0.0);
    }

    /** The signal names installed on this deployment, regardless of enablement.
     *
     *  <p><b>No production surface reads this</b>, and this javadoc asserted that a console and an API gated their
     *  surfaces on it for as long as neither did -. It is superseded rather than missing:
     *  {@code /api/capabilities} already serves the richer {@code signals} view, a name <em>and</em> label per
     *  installed signal off the Spring-injected list, so a name-only set has no surface left to gate. Its reader is
     *  the signal-consolidation suite, which drives every family through the one shared primitive. */
    static Set<String> installed() {
        return SignalSourceProvider.installed(AdvisorySignal.class);
    }

    /** Every enabled signal discovered via {@link ServiceLoader}, sorted by {@link AdvisorySignal#order() order}
     *  then name for a deterministic column layout; empty when none is enabled. */
    static List<AdvisorySignal> resolve(UnaryOperator<String> config) {
        List<AdvisorySignal> signals =
                new ArrayList<>(SignalSourceProvider.named(AdvisorySignal.class, config).values());
        signals.sort(Comparator.comparingInt(AdvisorySignal::order).thenComparing(AdvisorySignal::name));
        return List.copyOf(signals);
    }
}
