package build.jenesis.repository.server;

import module java.base;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A composition started on a port: the port it answers on, and closing it stops it. Every launcher's handle is one of
 * these, adding only what its own composition can answer beyond that.
 */
public abstract class Launched implements AutoCloseable {

    private final int port;

    /** The started composition's context, for what a launcher's handle answers beyond its port. */
    protected final ConfigurableApplicationContext context;

    protected Launched(int port, ConfigurableApplicationContext context) {
        this.port = port;
        this.context = Objects.requireNonNull(context, "context");
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        context.close();
    }
}
