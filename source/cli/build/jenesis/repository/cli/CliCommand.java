package build.jenesis.repository.cli;

import module java.base;

/**
 * One CLI verb's handler: dispatch its argument line against the stored session {@code home} and return the process
 * exit code. {@link Commands} maps each verb to one of these, implemented by the command groups.
 */
@FunctionalInterface
interface CliCommand {

    int run(String[] args, Path home) throws Exception;
}
