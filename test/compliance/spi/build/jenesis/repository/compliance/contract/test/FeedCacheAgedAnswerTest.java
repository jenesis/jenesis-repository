package build.jenesis.repository.compliance.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.FeedCache;
import build.jenesis.repository.compliance.Freshness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * at the choke point: what a per-key feed cache does with an answer it already drew, once that answer has
 * outlived the window it was drawn for and the refresh that should have replaced it fails.
 *
 * <p>There used to be one answer for every feed - keep serving the last good value and re-extend it by the retry
 * interval, indefinitely - which is why five fail-closed licensed feeds answered a stale advisory list for the whole
 * of a vendor outage while their contract said <em>raise</em>. It is now a declaration with no default, and the two
 * halves of the signal family take opposite positions on purpose: a fail-closed screen raises, because its answer is
 * read for its emptiness and nobody screened the key since the window lapsed; a fail-soft signal keeps what it drew,
 * with the instant it was really drawn at, because there the neutral value is the loosest one and dropping aged
 * evidence for it would loosen rather than tighten.
 *
 * <p>The window is crossed on an injected clock rather than by sleeping - the same {@code SignalContext.clock()} seam
 * every feed takes its window from in production.
 */
class FeedCacheAgedAnswerTest {

    private static final Duration WINDOW = Duration.ofHours(6);

    /** A loader that answers a fixed value until it is broken, then fails every call. */
    private static final class Vendor implements FeedCache.Loader<String> {

        private final Map<String, String> answers = new LinkedHashMap<>();
        private final Set<String> failing = new LinkedHashSet<>();
        private boolean down;

        private Vendor answering(String key, String value) {
            answers.put(key, value);
            return this;
        }

        /** One key the vendor cannot answer for while the rest of it works. */
        private Vendor failing(String key) {
            failing.add(key);
            return this;
        }

        private void down() {
            down = true;
        }

        @Override
        public String load(String key) throws IOException {
            if (down || failing.contains(key)) {
                throw new IOException("the vendor is unreachable" + (down ? "" : " for " + key));
            }
            return Objects.requireNonNull(answers.get(key), "the fixture answers nothing for " + key);
        }
    }

    @Test
    void a_fail_closed_cache_raises_rather_than_serving_an_answer_past_its_window() {
        Ticking clock = new Ticking();
        Vendor vendor = new Vendor().answering("log4j-core", "CVE-2021-44228");
        FeedCache<String> cache = FeedCache.failClosed("licensed", vendor, WINDOW, clock);

        assertThat(cache.get("log4j-core")).isEqualTo("CVE-2021-44228");
        Instant drawn = cache.freshness().refreshed().orElseThrow();

        // Inside the window the vendor is not asked again even once it is down: that is what a window is for, and the
        // gate is entitled to an answer up to that old.
        vendor.down();
        clock.advance(WINDOW.dividedBy(2));
        assertThat(cache.get("log4j-core")).as("inside its window the drawn answer stands").isEqualTo("CVE-2021-44228");
        assertThat(cache.freshness()).isEqualTo(Freshness.at(drawn));

        // Past it, the answer nobody could refresh is not an answer. This is the assertion the old cache failed: it
        // served "CVE-2021-44228" here, and would have gone on serving it - re-extended by the retry interval - for
        // as long as the vendor stayed down, so an advisory published in the meantime was invisible to the gate.
        clock.advance(WINDOW);
        assertThatExceptionOfType(RuntimeException.class)
                .as("an advisory answer older than its own window is a screen that did not happen")
                .isThrownBy(() -> cache.get("log4j-core"));
        assertThat(cache.freshness().authoritative())
                .as("and the reading says so, rather than reporting a refresh that never landed").isFalse();
        assertThat(cache.freshness().refreshed())
                .as("the instant it last really answered is kept, because that is what an operator needs to see")
                .contains(drawn);
    }

    @Test
    void a_fail_closed_cache_recovers_the_moment_the_vendor_does() {
        Ticking clock = new Ticking();
        Vendor vendor = new Vendor().answering("log4j-core", "CVE-2021-44228");
        FeedCache<String> cache = FeedCache.failClosed("licensed", vendor, WINDOW, clock);
        cache.get("log4j-core");

        vendor.down();
        clock.advance(WINDOW.multipliedBy(2));
        assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> cache.get("log4j-core"));

        // Nothing is remembered about the failure that would keep a publish blocked: the next lookup asks again, so a
        // blip costs the publishes it overlaps and not a minute more - and the key answering is what clears the
        // reading, because that is the one success that speaks for the key that failed.
        vendor.answers.put("log4j-core", "CVE-2021-45046");
        vendor.down = false;
        assertThat(cache.get("log4j-core")).isEqualTo("CVE-2021-45046");
        assertThat(cache.freshness().authoritative())
                .as("a landed refresh for the key that failed restores the reading").isTrue();
    }

    @Test
    void a_fail_soft_cache_keeps_what_it_drew_and_does_not_restamp_it() {
        Ticking clock = new Ticking();
        Vendor vendor = new Vendor().answering("CVE-2021-44228", "listed");
        FeedCache<String> cache = FeedCache.failSoft("catalogue", vendor, WINDOW, clock);

        assertThat(cache.get("CVE-2021-44228")).isEqualTo("listed");
        Instant drawn = cache.freshness().refreshed().orElseThrow();

        vendor.down();
        clock.advance(WINDOW.multipliedBy(2));
        assertThat(cache.get("CVE-2021-44228"))
                .as("a membership already drawn is evidence that has aged, and its neutral value is no evidence at all")
                .isEqualTo("listed");
        assertThat(cache.freshness().refreshed())
                .as("but it is not restamped: how old is too old is the consumer's policy, read off this instant")
                .contains(drawn);
        assertThat(cache.freshness().authoritative())
                .as("the value is real, so a consumer may still act on it - with its age in hand").isTrue();
    }

    @Test
    void a_lookup_that_produced_nothing_holds_the_reading_down_and_a_sibling_success_does_not_clear_it() {
        // /the reading is one value for the source although the lookups are per key, and it is derived
        // from the lookups rather than from whichever one finished last. A key that reached nothing must not be
        // laundered by another key answering cleanly - the vendor having answered for B says nothing about A.
        Ticking clock = new Ticking();
        Vendor vendor = new Vendor().answering("B", "clean").failing("A");
        FeedCache<String> cache = FeedCache.failSoft("catalogue", vendor, WINDOW, clock);

        assertThatExceptionOfType(RuntimeException.class)
                .as("A was never drawn, so there is nothing to serve for it whatever the mode")
                .isThrownBy(() -> cache.get("A"));
        assertThat(cache.get("B")).isEqualTo("clean");
        assertThat(cache.freshness().authoritative())
                .as("B answering does not make A's failed lookup a clearance").isFalse();
        assertThat(cache.freshness().authoritative())
                .as("and reading the accessor does not consume it - it answers the same way twice").isFalse();

        // A's own window lapsing is one honest way out: nothing is being served on the strength of it any more, and a
        // key nobody asks again may not stand a consumer down for ever.
        clock.advance(Duration.ofHours(1));
        assertThat(cache.freshness().authoritative())
                .as("A's lookup stops speaking for the reading once its retry window has lapsed").isTrue();
    }

    @Test
    void the_reading_is_never_fetched_never_authoritative_before_anything_lands() {
        Ticking clock = new Ticking();
        FeedCache<String> cache = FeedCache.failClosed("licensed", new Vendor(), WINDOW, clock);
        assertThat(cache.freshness())
                .as("a cache nobody has asked confirms nothing; a stamp taken at construction would render beside an "
                        + "empty panel exactly as a healthy feed does")
                .isEqualTo(Freshness.NEVER);
    }
}
