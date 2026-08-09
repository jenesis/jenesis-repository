package build.jenesis.repository.walk.contract.test;

import module java.base;

/**
 * The {@link StrideBufferedConsumer} fixture: one round trip per checkpoint stride instead of one per artifact, which
 * is what a rebuild over a large store actually wants. It converges at every crash point only because the flush hook
 * is ordered before the cursor commit - which is what
 * {@code CRASH_AFTER_THE_CHECKPOINT_LANDED_CONVERGES} proves for this fixture and only for this fixture.
 */
final class StrideBufferedFixture extends RowIndexFixture {

    @Override
    public String consumer() {
        return StrideBufferedConsumer.NAME;
    }

    @Override
    public String providerClass() {
        return StrideBufferedConsumer.class.getName();
    }

    @Override
    String space() {
        return StrideBufferedConsumer.SPACE;
    }

    @Override
    public Delivery delivery() {
        return Delivery.STRIDE_DURABLE;
    }
}
