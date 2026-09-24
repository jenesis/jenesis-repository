package build.jenesis.repository.upstream.store.test;

import module java.base;
import build.jenesis.repository.upstream.UpstreamTokenIssuer;

/**
 * An issuer for {@code *.issued.test} hosts that counts what it mints and hands out a token valid for one hour from
 * the suite's clock; {@link #failing} makes the next issues throw, as a cloud that refuses the identity would.
 */
public final class CountingIssuer implements UpstreamTokenIssuer {

    static final AtomicInteger ISSUED = new AtomicInteger();
    static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2026-09-24T12:00:00Z"));
    static volatile boolean failing;

    @Override
    public String name() {
        return "counting";
    }

    @Override
    public boolean issues(String host) {
        return host.endsWith(".issued.test");
    }

    @Override
    public Token issue(String host) throws IOException {
        if (failing) {
            throw new IOException("the identity was refused");
        }
        int number = ISSUED.incrementAndGet();
        return new Token("Authorization", "Bearer minted-" + number, NOW.get().plus(Duration.ofHours(1)));
    }
}
