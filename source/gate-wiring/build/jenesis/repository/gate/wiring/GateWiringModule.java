package build.jenesis.repository.gate.wiring;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the compliance screen's publish-path wiring to the repository server's {@code ServerModuleProvider}
 * discovery, so the server imports {@link GateWiringConfig} without naming a screen anywhere.
 */
public final class GateWiringModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "gate-wiring";
    }

    @Override
    public Class<?> configuration() {
        return GateWiringConfig.class;
    }
}
