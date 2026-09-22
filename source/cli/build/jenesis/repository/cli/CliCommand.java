package build.jenesis.repository.cli;

import module java.base;

/**
 * One CLI verb's handler: dispatch its argument line against the stored session {@code home} and return the process
 * exit code. The thin {@link Cli} dispatcher maps each verb name to one of these, implemented by the command groups
 * ({@link AuthCommands}, {@link DiscoveryCommands}, {@link ComplianceCommands}, {@link LifecycleCommands},
 * {@link AdminCommands}), so a new verb is a registry entry rather than another arm of one monolithic switch.
 */
@FunctionalInterface
interface CliCommand {

    int run(String[] args, Path home) throws Exception;
}
