package build.jenesis.repository.store;

import module java.base;

/**
 * The one clock the product stamps stored facts with. Every node of a fleet stamps what it stores - a publish, a
 * rebuild's boundary, a pin - with an instant, and the mechanisms that compare stamps across nodes carry allowances
 * for a peer whose clock runs ahead. A deployment cannot be asked to skew a node's clock to prove those allowances,
 * and a container cannot skew its own, so the clock the product reads is installed once at boot: the system clock,
 * or the system clock offset by {@code jenreg.clock.skew}, a dial that exists so a fleet's tolerance to a peer whose
 * clock runs ahead is a thing a test can provoke rather than a thing a javadoc asserts. Reading {@link #now()} where a
 * stamp is made, instead of {@link Instant#now()}, is what puts a mechanism under that test.
 */
public final class Clocks {

    private static volatile Clock installed = Clock.systemUTC();

    private Clocks() {
    }

    /** Install the clock every stamp reads from here on; the last installation wins, and a boot installs once. */
    public static void install(Clock clock) {
        installed = Objects.requireNonNull(clock, "clock");
    }

    /** The clock stamps are made with. */
    public static Clock clock() {
        return installed;
    }

    /** The instant a stamp made now carries. */
    public static Instant now() {
        return installed.instant();
    }
}
