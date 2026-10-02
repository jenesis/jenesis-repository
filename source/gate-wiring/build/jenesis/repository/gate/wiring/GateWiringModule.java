package build.jenesis.repository.gate.wiring;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/** Contributes {@link GateWiringConfig}, the compliance screen's publish-path wiring, to the server. */
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
