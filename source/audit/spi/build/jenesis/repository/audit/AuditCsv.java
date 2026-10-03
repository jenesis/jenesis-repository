package build.jenesis.repository.audit;

import module java.base;

/**
 * A tenant's audit trail as CSV, one row an event, streamed through {@link AuditTrail#stream} so a large trail exports
 * in flat memory - the one export every surface offering it writes, so the escaping that keeps a row from running as a
 * spreadsheet formula is written once.
 */
public final class AuditCsv {

    /** The media type an export is answered with. */
    public static final String CONTENT_TYPE = "text/csv;charset=UTF-8";

    /** What a surface answers, with {@code 501}, on a deployment carrying no audit module. */
    public static final String NOT_INSTALLED = "audit is not installed on this deployment";

    private AuditCsv() {
    }

    /** Write {@code tenant}'s events between {@code from} and {@code to} (either open), of {@code action} when one is
     *  named, newest first, under a header row. */
    public static void write(AuditTrail trail, String tenant, Instant from, Instant to, String action, Writer out)
            throws IOException {
        out.write("at,actor,action,target\n");
        trail.stream(tenant, from, to, action, event -> out.write(field(event.at().toString()) + ','
                + field(event.actor()) + ',' + field(event.action()) + ',' + field(event.target()) + '\n'));
    }

    /** Quote a field carrying a comma, quote or newline, and prefix a leading {@code = + - @}, tab or carriage return
     *  with an apostrophe so a spreadsheet does not evaluate it. */
    static String field(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.isEmpty() || "=+-@\t\r".indexOf(value.charAt(0)) < 0 ? value : "'" + value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
