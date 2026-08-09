package build.jenesis.repository.walk.contract.test;

import module java.base;

/**
 * The {@link StreamingIndexConsumer} fixture: one row written through per artifact, so the derived state is always at
 * least as far along as the walk's cursor and every crash point converges.
 */
final class StreamingIndexFixture extends RowIndexFixture {

    @Override
    public String consumer() {
        return StreamingIndexConsumer.NAME;
    }

    @Override
    public String providerClass() {
        return StreamingIndexConsumer.class.getName();
    }

    @Override
    String space() {
        return StreamingIndexConsumer.SPACE;
    }

    @Override
    public Delivery delivery() {
        return Delivery.PER_ITEM_DURABLE;
    }
}
