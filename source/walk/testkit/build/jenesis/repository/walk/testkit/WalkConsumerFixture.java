package build.jenesis.repository.walk.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkConsumer;

/**
 * How one {@link WalkConsumer} registers with the shared {@link WalkConsumerContract}: it supplies a corpus, a way to
 * build a fresh instance of itself, and - the declaration that makes the kit possible at all - <em>what convergence
 * means for it</em>.
 *
 * <p><b>Convergence is declared, not guessed.</b> Two correct consumers of the same pass can hold entirely different
 * durable state: one writes a row per artifact, another one document listing them all, a third an inverted index keyed
 * the other way round, and every one of them may legitimately carry a rebuild timestamp, a pass generation or a
 * different row order without being any less converged. A kit that compared stored bytes would therefore be asserting
 * a layout it has no business owning. So the fixture answers {@link #projection} - the consumer's durable state read
 * back out of the store and <em>normalised into whatever comparable value the consumer's own contract says is its
 * view</em> - and {@link #seed} answers what that value must be once the pass has converged. Everything the kit
 * asserts about convergence is an equality between those two, so "converged" is exactly as strong as the consumer
 * declared and no stronger.
 *
 * <p><b>The delivery class is declared too</b> ({@link Delivery}), because the pass provides one durability guarantee
 * and a consumer either rides it or does not. A crash-resume converges a per-item or stride-durable consumer and
 * <em>does not</em> converge a pass-snapshot one; the kit asserts each fixture's own class rather than holding every
 * consumer to the strongest one it saw, which would be a claim the walk's commit protocol does not support.
 *
 * <p>A fixture seeds into the store it is handed and holds no state of its own between checks: every check gets a
 * fresh, empty store and calls {@link #create()} for each simulated process, so a consumer's in-memory accumulation is
 * lost across a crash exactly as it would be in production.
 */
public interface WalkConsumerFixture {

    /** The {@link WalkConsumer#name() consumer name} this fixture drives - the same string an operator writes as
     *  {@code jenreg.<name>}, and the consumer's own key-space and settings namespace. */
    String consumer();

    /** The fully qualified {@code WalkConsumer} implementation class this fixture covers, as the census parses it out
     *  of the owning module's {@code provides ... with ...} clause. */
    String providerClass();

    /**
     * A <em>fresh</em> instance of the consumer, as a restarted process would build it. The kit calls this once per
     * simulated process - so an instance never survives an injected crash - and reaches it through
     * {@link WalkConsumer#discovered()} rather than by construction, exactly as the scheduled pass does.
     */
    default WalkConsumer create() {
        return WalkConsumer.discovered().stream()
                .filter(consumer -> consumer.name().equals(consumer()))
                .filter(consumer -> consumer.getClass().getName().equals(providerClass()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the '" + consumer() + "' consumer is not discoverable "
                        + "on this module graph, so its contract cannot run"));
    }

    /** The pointer roots the pass enumerates for this consumer ({@code publish} plus any blobs-namespace root its
     *  formats declare). Never {@code blobs}, {@code gc} or {@code walks} - {@link RebuildPass} refuses those. */
    List<String> pointerRoots();

    /** The store key prefixes this consumer may write under. The kit walks the store after a pass and fails on any key
     *  the pass created outside them (or outside the walk's own {@code walks/} pass state), so "the consumer stayed in
     *  its own namespace" is a statement about the store rather than about intent. */
    List<String> namespaces();

    /**
     * Publish {@code artifacts} retained pointers under {@link #pointerRoots()} - the history that already existed
     * before this consumer was ever switched on - and declare what a converged projection over them looks like. The
     * kit chooses the count relative to the walk's checkpoint stride so its crash points land where it says they do.
     */
    Corpus seed(ArtifactStore store, int artifacts) throws IOException;

    /**
     * A seeded corpus: how many {@link WalkConsumer#onRetained} calls one full pass makes over it, and the
     * {@link #projection} a converged consumer must then answer.
     *
     * <p><b>{@code deliveries} is the fixture's to count, and it is not always one per seeded artifact</b>
     * (D-255). The kit cannot derive it, because a consumer whose derived state is <em>itself</em> a retained
     * pointer feeds its own walk: the rows it writes during a pass land under {@link #pointerRoots()} and are
     * enumerated by the same pass that wrote them, so the count depends on the order the roots are listed in and
     * cannot be known before the pass runs. The first shipped consumer is one of these - a module view is a
     * serving pointer, so one pass over {@code n} modular jars delivers {@code 2n + 1}, not {@code n}.
     *
     * <p>A fixture in that position owes two things beside the number: a reason the count is <em>stable</em>
     * (which root sorts first, so every derived row is written before the subtree it lands in is listed), and a
     * seed that makes the derived subtree exist from the first listing - without it a first pass and a second
     * disagree, and the contract's re-run legs read that as a consumer that does not converge.
     */
    record Corpus(int deliveries, Map<String, String> converged) {

        public Corpus {
            if (deliveries <= 0) {
                throw new IllegalArgumentException("a corpus that delivers nothing asserts nothing");
            }
            converged = Map.copyOf(converged);
        }
    }

    /**
     * This consumer's durable state, read back out of {@code store} and normalised into the comparable view its own
     * contract calls its projection - dropping whatever may legitimately differ between two converged rebuilds (a
     * rebuild stamp, a pass generation, row order). Empty when the consumer has committed nothing yet. This is the
     * fixture's declaration of convergence, and it is the only thing the kit compares.
     */
    Map<String, String> projection(ArtifactStore store) throws IOException;

    /**
     * The say-so a consumer leaves behind when it knows it could <em>not</em> converge from the pass it just saw - the
     * &sect;5 "degrade gracefully and say so, never serve a silently-incomplete view as if it were whole" surface -
     * or empty when it converged. The kit requires exactly one of the two after every crash-resume: either the
     * projection is the converged one, or this answers a reason. A consumer that answers neither has quietly replaced
     * a whole view with a fragment, which is the defect this SPI exists to prevent.
     */
    default Optional<String> degradation(ArtifactStore store) throws IOException {
        return Optional.empty();
    }

    /** What durability this consumer actually rides - see {@link Delivery}. The kit holds it to this class and to no
     *  stronger one. */
    Delivery delivery();

    /**
     * The three delivery classes the walk's commit protocol supports, and the only three a consumer may declare. The
     * distinction is not stylistic: it decides whether a crash-resumed pass converges the consumer at all, and the kit
     * asserts each fixture against its own declaration rather than against the strongest class in the room (which is
     * how a suite ends up claiming a durability the store never provided).
     */
    enum Delivery {

        /** The derived write completes inside {@code onRetained}, before it returns. Every crash point converges: the
         *  cursor can only ever be behind the derived state, so the replay is a re-upsert. */
        PER_ITEM_DURABLE,

        /** Deliveries are buffered and flushed from {@code WalkConsumer.beforeCheckpoint}. Every crash point converges
         *  <em>because</em> the flush is ordered before the cursor commit that would skip those items - which is the
         *  whole reason the hook is forwarded to consumers at all. */
        STRIDE_DURABLE,

        /** One artifact committed from {@code onPassCompleted}. A crash-resumed pass replays only the uncommitted
         *  tail, so what reaches {@code onPassCompleted} is a fragment: this class does <em>not</em> converge across a
         *  crash and must not pretend to. Such a consumer detects the re-entered generation, refuses to overwrite its
         *  snapshot with the fragment, records the degradation, and converges on the next full pass. */
        PASS_SNAPSHOT;

        /** Whether a crash-resumed pass leaves this class converged. The kit's post-resume assertion is exactly this
         *  question, so no fixture can be held to a guarantee its class does not carry (the plan's gate 5). */
        public boolean convergesAcrossACrash() {
            return this != PASS_SNAPSHOT;
        }
    }

    /** The contract properties this consumer's shape genuinely does not have, each with a mandatory reason naming
     *  where the property is proven instead. Empty by default: an exclusion is a deliberate, reviewable statement. */
    default Map<WalkConsumerContract.Property, String> unsupported() {
        return Map.of();
    }
}
