package build.jenesis.repository.closure.contract.test;

import build.jenesis.repository.compliance.testkit.NoEgressResolver;

/**
 * This suite's hermetic-run tripwire: the shared {@link NoEgressResolver} mechanism, installed for this test JVM
 * through this module's own {@code provides} clause. A closure source reads only the stores of its walk, so a name
 * being resolved means one reached for a remote repository - Maven Resolver's own remotes, a repository a POM
 * declares - and the contract's "nothing is fetched" check is that measurement. {@code ClosureSourceCensusTest} proves
 * the tripwire is installed, without which every such check would be vacuously true.
 */
public final class NoEgress extends NoEgressResolver {

    /** Public for {@code ServiceLoader}: the JDK constructs the provider named in this module's {@code provides}
     *  clause. Nothing in the suite instantiates it. */
    public NoEgress() {
    }

    @Override
    protected String refusal(String host) {
        return host + " - the ClosureSource contract suite runs hermetically: a source reads only the stores of the "
                + "walk it is handed, so resolving a name means it reached for a remote repository.";
    }

    @Override
    public String name() {
        return "jenesis-closure-contract-no-egress";
    }
}
