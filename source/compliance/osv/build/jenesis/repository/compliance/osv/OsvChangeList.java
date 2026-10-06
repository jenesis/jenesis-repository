package build.jenesis.repository.compliance.osv;

import module java.base;
import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedRequest;
import java.time.format.DateTimeParseException;

/**
 * One ecosystem's change list in OSV's export, {@code <ecosystem>/modified_id.csv}: every record's last modification and
 * id, newest first. Read from its head down to a position - the instant of the last line a reader reached - and never
 * past {@link #WINDOW} bytes, so a reader whose position lies further down learns that it ran out rather than reading a
 * shorter list.
 */
final class OsvChangeList {

    /** The most of one change list a read takes. */
    static final int WINDOW = 1 << 20;

    private OsvChangeList() {
    }

    /** One line of a change list. */
    record Line(Instant modified, String id) {
    }

    /** What one list says since a position - every line after it, newest first - and whether the window ran out
     *  before reaching it. Without a position, its head alone. */
    record Listed(List<Line> lines, boolean exhausted) {
    }

    /** What {@code ecosystem}'s list at {@code export} says since {@code position} through {@code client}, or empty
     *  where the export holds no list for it. */
    static Optional<Listed> read(FeedClient client, URI export, String ecosystem, Instant position)
            throws IOException {
        FeedRequest request = FeedRequest.get(export.resolve(path(ecosystem) + "/modified_id.csv"));
        try {
            return Optional.of(client.fetch(request, FeedClient.Reader.document(body -> {
                List<Line> lines = new ArrayList<>();
                BoundedInput window = new BoundedInput(body, WINDOW);
                BufferedReader reader = new BufferedReader(new InputStreamReader(window, StandardCharsets.UTF_8));
                String text;
                while ((text = reader.readLine()) != null) {
                    int comma = text.indexOf(',');
                    if (comma < 0) {
                        continue;
                    }
                    Instant modified;
                    try {
                        modified = Instant.parse(text.substring(0, comma).strip());
                    } catch (DateTimeParseException unreadable) {
                        continue;
                    }
                    String id = text.substring(comma + 1).strip();
                    int slash = id.lastIndexOf('/');
                    id = slash < 0 ? id : id.substring(slash + 1);
                    if (position == null) {
                        return new Listed(List.of(new Line(modified, id)), false);
                    }
                    if (!modified.isAfter(position)) {
                        return new Listed(List.copyOf(lines), false);
                    }
                    lines.add(new Line(modified, id));
                }
                // The list ended, or the window did: only a window that ran out leaves the position unreached.
                return new Listed(List.copyOf(lines), window.exhausted());
            })).value().orElseThrow());
        } catch (FeedException e) {
            if (e.reason() == FeedException.Reason.STATUS && e.status() == 404) {
                return Optional.empty();
            }
            throw new IOException("Could not read OSV's " + ecosystem + " change list (" + OsvQuery.reason(e) + ")",
                    e);
        }
    }

    /** {@code ecosystem} as a path segment of the export, which names its directories as OSV spells them. */
    static String path(String ecosystem) {
        return URLEncoder.encode(ecosystem, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** At most {@code limit} bytes of {@code in}, remembering whether the limit, not the stream, ended the read: at
     *  the limit it looks one byte past it, so a list exactly {@code limit} bytes long ends as a whole list rather
     *  than as one cut short. */
    private static final class BoundedInput extends FilterInputStream {

        private long remaining;
        private boolean exhausted;

        BoundedInput(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        boolean exhausted() {
            return exhausted;
        }

        /** At the limit, whether the stream goes on past it: the byte looked at is never handed out. */
        private int atLimit() throws IOException {
            exhausted |= super.read() >= 0;
            return -1;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return atLimit();
            }
            int read = super.read();
            if (read >= 0) {
                remaining--;
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return atLimit();
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
