package build.jenesis.repository.cli;

import module java.base;

/**
 * The verbs over evidence supplied from outside: third-party scanner reports, VEX statements saying what an advisory
 * actually means for this product, and the test runs the selection service learns from.
 *
 * <p>All three ingest a document produced by another tool, so each takes a file path rather than asking a caller to
 * inline a payload - which is also what lets a CI job or an agent use them without assembling JSON. None takes a
 * repository: a build, a test history and a VEX statement belong to the tenant, and the API scopes them to it.
 */
final class ScanCommands {

    private ScanCommands() {
    }

    static int vex(String[] args, Path home) throws Exception {
        String action = args.length > 1 ? args[1] : "";
        switch (action) {
            case "show" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: vex show <id>");
                }
                System.out.println(CliSupport.client(home).provenance().vexStatement(args[2]));
            }
            case "add" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: vex add <file>");
                }
                System.out.println(CliSupport.client(home).provenance().addVex(Files.readString(Path.of(args[2]))));
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: vex remove <id>");
                }
                CliSupport.client(home).provenance().removeVex(args[2]);
                System.out.println("Withdrew VEX statement " + args[2] + ".");
            }
            case "export" -> {
                String output = null;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equals("--output")) {
                        output = CliSupport.flag(args, ++i);
                    } else {
                        throw new IllegalArgumentException("Unknown export flag '" + args[i] + "'");
                    }
                }
                String document = CliSupport.client(home).provenance().exportVex();
                if (output == null) {
                    System.out.println(document);
                } else {
                    Files.writeString(Path.of(output), document);
                    System.out.println("Wrote " + output + ".");
                }
            }
            case "" -> System.out.println(CliSupport.client(home).provenance().vexStatements());
            default -> throw new IllegalArgumentException("Unknown vex action '" + action + "'");
        }
        return 0;
    }

    static int scans(String[] args, Path home) throws Exception {
        String action = args.length > 1 ? args[1] : "";
        switch (action) {
            case "show" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: scans show <id>");
                }
                System.out.println(CliSupport.client(home).buildCache().scan(args[2]));
            }
            case "report" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: scans report <id>");
                }
                System.out.println(CliSupport.client(home).buildCache().scanReport(args[2]));
            }
            case "analytics" -> {
                boolean asReport = args.length > 2 && args[2].equals("report");
                System.out.println(CliSupport.client(home).buildCache().scanAnalytics(asReport));
            }
            case "ingest" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: scans ingest <file>");
                }
                System.out.println(CliSupport.client(home)
                        .buildCache().ingestScan(Files.readString(Path.of(args[2]))));
            }
            case "" -> System.out.println(CliSupport.client(home).buildCache().scans());
            default -> throw new IllegalArgumentException("Unknown scans action '" + action + "'");
        }
        return 0;
    }

    static int tests(String[] args, Path home) throws Exception {
        String action = args.length > 1 ? args[1] : "";
        switch (action) {
            case "ingest" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: tests ingest <file>");
                }
                System.out.println(CliSupport.client(home)
                        .buildCache().ingestTestRun(Files.readString(Path.of(args[2]))));
            }
            case "show" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: tests show <id>");
                }
                System.out.println(CliSupport.client(home).buildCache().testRun(args[2]));
            }
            case "flaky" -> {
                if (args.length > 2) {
                    throw new IllegalArgumentException("Usage: tests flaky");
                }
                System.out.println(CliSupport.client(home).buildCache().flakyTests());
            }
            case "select" -> {
                String changed = null;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equals("--changed")) {
                        changed = CliSupport.flag(args, ++i);
                    } else {
                        throw new IllegalArgumentException("Unknown select flag '" + args[i] + "'");
                    }
                }
                System.out.println(CliSupport.client(home).buildCache().selectTests(changed));
            }
            default -> throw new IllegalArgumentException(
                    "Usage: tests ingest <file> | tests show <id> | tests flaky | tests select");
        }
        return 0;
    }
}
