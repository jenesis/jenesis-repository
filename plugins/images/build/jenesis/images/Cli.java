package build.jenesis.images;

import module java.base;

/**
 * Running a command-line tool, which is most of what publishing an image is.
 *
 * <p>Two shapes, because the registries need both: one that streams the tool's output into this build's own, for
 * the long-running pushes where silence reads as a hang; and one that captures it, for the single case where a
 * tool's output is another tool's input ({@code aws ecr get-login-password} into {@code docker login}).
 */
final class Cli {

    private Cli() {
    }

    /** Run {@code command}, streaming its output, and fail if it does not exit zero. */
    static void run(List<String> command) throws IOException {
        int status = start(new ProcessBuilder(command).inheritIO(), command);
        if (status != 0) {
            throw new IllegalStateException(String.join(" ", redacted(command)) + " exited " + status);
        }
    }

    /** Run {@code command} and return its standard output, failing if it does not exit zero. */
    static String capture(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process;
        try {
            process = builder.start();
        } catch (IOException unavailable) {
            throw missing(command, unavailable);
        }
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (await(process, command) != 0) {
            throw new IllegalStateException(String.join(" ", redacted(command)) + " exited non-zero");
        }
        return output.strip();
    }

    /** Run {@code command} and return what it printed on both streams, failing if it does not exit zero - for a
     *  tool whose answer is a line it may write to either, as {@code helm push} does with the digest it pushed. */
    static String combined(List<String> command) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException unavailable) {
            throw missing(command, unavailable);
        }
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        System.out.print(output);
        if (await(process, command) != 0) {
            throw new IllegalStateException(String.join(" ", redacted(command)) + " exited non-zero");
        }
        return output.strip();
    }

    /** Run {@code command} with {@code input} on its standard input - a secret handed to a tool without ever
     *  reaching a command line, where it would be visible to every process on the machine. */
    static void feed(List<String> command, String input) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process;
        try {
            process = builder.start();
        } catch (IOException unavailable) {
            throw missing(command, unavailable);
        }
        try (OutputStream out = process.getOutputStream()) {
            out.write(input.getBytes(StandardCharsets.UTF_8));
        }
        if (await(process, command) != 0) {
            throw new IllegalStateException(String.join(" ", redacted(command)) + " exited non-zero");
        }
    }

    /** Whether a tool is on the PATH at all, so a missing one is named before a flow half-runs. */
    static boolean available(String tool) {
        // Whether the process starts at all is the question, not what it answers: {@code --version} is not a
        // universal flag - helm has {@code helm version} and exits 1 on the flag - and reading that exit status as
        // "not on the PATH" reported a present helm as absent. A tool that is missing fails to start, which is the
        // IOException below.
        try {
            new ProcessBuilder(tool, "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor();
            return true;
        } catch (IOException notThere) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int start(ProcessBuilder builder, List<String> command) throws IOException {
        Process process;
        try {
            process = builder.start();
        } catch (IOException unavailable) {
            throw missing(command, unavailable);
        }
        return await(process, command);
    }

    private static int await(Process process, List<String> command) throws IOException {
        try {
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted running " + String.join(" ", redacted(command)), interrupted);
        }
    }

    private static IOException missing(List<String> command, IOException cause) {
        return new IOException("Could not run '" + String.join(" ", redacted(command)) + "'. Publishing images needs"
                + " that tool on the PATH; this goal is opt-in precisely so that an ordinary build does not.",
                cause);
    }

    /** A command as it is safe to print. Nothing here passes a secret as an argument - they go over standard
     *  input - but a password that ever did must not reach a log through an error message. */
    private static List<String> redacted(List<String> command) {
        List<String> safe = new ArrayList<>(command.size());
        boolean secret = false;
        for (String argument : command) {
            safe.add(secret ? "***" : argument);
            secret = argument.equals("--password") || argument.equals("-p");
        }
        return safe;
    }
}
