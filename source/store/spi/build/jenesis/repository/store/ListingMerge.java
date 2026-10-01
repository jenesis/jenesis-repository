package build.jenesis.repository.store;

import module java.base;

import static build.jenesis.repository.store.StoredListing.*;
import static build.jenesis.repository.store.ListingFrame.*;
import static build.jenesis.repository.store.ListingLanes.*;

/**
 * How a {@link StoredListing} document is rendered and merged: a generator's entries into a fresh document, and a
 * stored document with a batch of changes into the next one, in one ordered pass over both that holds a stride of
 * entries rather than the document, with the trailer of the entries' sources merged beside the body.
 */
final class ListingMerge {

    private ListingMerge() {
    }

    /**
     * A document rendered outside heap, with what its header needs already computed: the body in {@code file},
     * and - when any entry carries a source - the source trailer in {@code sources}, with its length and digest,
     * and the digest of the trailer the stored document carried when this was merged from one ({@code ""} when it
     * carried none), so a write that would change only the provenance is still a write.
     */
    record Rendered(Path file, long size, String md5, String sha256, long entries, Path sources,
                            long trailer, String trailerDigest, String storedTrailer) {

        Rendered(Path file, long size, String md5, String sha256, long entries) {
            this(file, size, md5, sha256, entries, null, 0L, "", "");
        }

        void discard() throws IOException {
            Files.deleteIfExists(file);
            if (sources != null) {
                Files.deleteIfExists(sources);
            }
        }
    }

    /** Render {@code spec}'s generator into a temporary file, digesting as it goes; the sources it states go to
     *  a second file beside it, in the order they are emitted. */
    static Rendered render(Spec spec) throws IOException {
        Path file = OwnerOnly.createTempFile("jenrepo-listing", ".tmp");
        Counter counter = new Counter();
        MessageDigest sha256 = digest("SHA-256");
        MessageDigest md5 = spec.md5() ? digest("MD5") : null;
        SourceWriter sources = new SourceWriter();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
             OutputStream digesting = digesting(out, sha256, md5);
             Codec.Appender appender = spec.codec().append(digesting)) {
            spec.generator().generate(new Generator.Sink() {
                @Override
                public void accept(String id, byte[] entry) throws IOException {
                    appender.append(id, entry);
                    counter.count++;
                }

                @Override
                public void accept(String id, byte[] entry, long source) throws IOException {
                    accept(id, entry);
                    if (source != NO_SOURCE) {
                        sources.line(id, sourced(id, source), false);
                    }
                }

                @Override
                public void absent(String id, long source) throws IOException {
                    if (source != NO_SOURCE) {
                        sources.line(id, sourced(id, source), true);
                    }
                }
            });
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(file);
            sources.discard();
            throw failed;
        }
        sources.close();
        return new Rendered(file, Files.size(file), md5 == null ? "" : HexFormat.of().formatHex(md5.digest()),
                HexFormat.of().formatHex(sha256.digest()), counter.count, sources.file, sources.size, sources.digest(),
                "");
    }

    /**
     * Render the stored document with {@code batch} applied, holding neither.
     *
     * <p>The counterpart of {@link #render}: same output, same temporary file, but the entries come from the
     * stored document rather than from the generator. Both sequences are in ascending id order - the reader
     * because a stored document is, the changes because they are collected into a sorted map - so applying one to
     * the other is a single linear merge, and the update costs a buffer rather than the repository.
     *
     * <p>The batch is replayed rather than unioned, because order is meaningful within it: a prefix removal in one
     * pending change can take out an entry an earlier one added, and a later change can re-add under a prefix an
     * earlier removal cleared. Replaying gives the final state of every id the batch mentions; a stored id the
     * batch never mentions can only be affected by a prefix removal, so those are applied to it directly.
     */
    static Rendered merge(ArtifactStore store, String key, Spec spec, Served stored, List<Pending> batch)
            throws IOException {
        // Phase one: the body, merged as if every change lands - which is every change, unless the trailer says
        // a stored entry's source has moved past one. The body comes first in the stream and the trailer after
        // it, and a stream has one end; reading the trailer first would mean holding it, sized by the listing.
        // So the body is merged optimistically, the trailer is then read once and merged into the new trailer as
        // it goes, and only when it names a stale change - a rare event, a rebuild's snapshot meeting a publish -
        // is the body merged again from a second read, with the stale changes left out.
        SortedMap<String, Change> resolved = resolve(batch);
        List<String> prefixes = prefixes(batch);
        Rendered body = merge(spec, joined(spec, stored.body(), stored.header().size(), SourceReader.none()),
                items(resolved), prefixes, false, Set.of());
        try {
            TrailerMerge trailer = mergeTrailer(stored.rest(), resolved, prefixes);
            try {
                if (!trailer.stale.isEmpty()) {
                    SUPERSEDED.add(trailer.stale.size());
                    body.discard();
                    body = null;
                    Optional<Served> again = openStored(store, key);
                    if (again.isEmpty()) {
                        throw new IOException("listing " + key + " vanished while it was being merged");
                    }
                    try (Served second = again.get()) {
                        body = merge(spec, joined(spec, second.body(), second.header().size(), SourceReader.none()),
                                items(resolved), prefixes, false, trailer.stale);
                    }
                }
                if (body.sources != null) {
                    Files.deleteIfExists(body.sources);   // phase one's provenance: the merged trailer replaces it
                }
                return new Rendered(body.file, body.size, body.md5, body.sha256, body.entries, trailer.file,
                        trailer.size, trailer.digest, trailer.storedDigest);
            } catch (IOException | RuntimeException failed) {
                if (trailer.file != null) {
                    Files.deleteIfExists(trailer.file);
                }
                throw failed;
            }
        } catch (IOException | RuntimeException failed) {
            if (body != null) {
                body.discard();
            }
            throw failed;
        }
    }

    /** The batch replayed into one answer per id: order is meaningful within it, because a prefix removal in one
     *  pending change can take out an entry an earlier one added, and a later change can re-add under a prefix an
     *  earlier removal cleared. */
    static SortedMap<String, Change> resolve(List<Pending> batch) {
        SortedMap<String, Change> resolved = new TreeMap<>();
        for (Pending pending : batch) {
            for (String prefix : pending.prefixes()) {
                resolved.replaceAll((id, change) -> id.startsWith(prefix) ? new Change(Optional.empty(), NO_SOURCE)
                        : change);
            }
            resolved.putAll(pending.changes());
        }
        return resolved;
    }

    static List<String> prefixes(List<Pending> batch) {
        List<String> prefixes = new ArrayList<>();
        for (Pending pending : batch) {
            prefixes.addAll(pending.prefixes());
        }
        return prefixes;
    }

    static Items items(List<Pending> batch) {
        return items(resolve(batch));
    }

    static Items items(SortedMap<String, Change> resolved) {
        Iterator<Map.Entry<String, Change>> changes = resolved.entrySet().iterator();
        return () -> {
            if (!changes.hasNext()) {
                return Optional.empty();
            }
            Map.Entry<String, Change> change = changes.next();
            return Optional.of(new Item(change.getKey(), change.getValue().fragment(), change.getValue().source()));
        };
    }

    /**
     * Render {@code stored} with {@code incoming} applied, holding neither.
     *
     * <p>Both sides are in ascending id order - a stored document is, a change set is collected into a sorted
     * map, a generator emits so - so applying one to the other is a single linear merge, and the update costs a
     * buffer rather than the repository. Per id: a stored item the incoming side never names survives, unless a
     * prefix removal covers it or - under a regeneration - it carries no source, in which case the generator's
     * silence about it is its removal; an incoming item the stored side never names lands; and where both name
     * an id the incoming wins, unless both carry a source and the stored one is the later, or the id is one the
     * caller already found stale, in which case the stored item stays. A put's source becomes the entry's; a
     * removal's source becomes a tombstone's; no source, no line.
     */
    static Rendered merge(Spec spec, Joined stored, Items incoming, List<String> prefixes, boolean regenerate,
                                  Set<String> stale) throws IOException {
        Path file = OwnerOnly.createTempFile("jenrepo-listing", ".tmp");
        Counter counter = new Counter();
        MessageDigest sha256 = digest("SHA-256");
        MessageDigest md5 = spec.md5() ? digest("MD5") : null;
        SourceWriter sources = new SourceWriter();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
             OutputStream digesting = digesting(out, sha256, md5);
             Codec.Appender appender = spec.codec().append(digesting);
             Joined kept = stored) {
            Optional<Item> entry = kept.next();
            Optional<Item> change = incoming.next();
            while (entry.isPresent() || change.isPresent()) {
                int order = entry.isEmpty() ? 1
                        : change.isEmpty() ? -1
                        : entry.get().id().compareTo(change.get().id());
                if (order < 0) {
                    Item item = entry.get();
                    boolean covered = prefixes.stream().anyMatch(item.id()::startsWith);
                    if (!covered && (item.source() != NO_SOURCE || (!regenerate && item.entry().isPresent()))) {
                        emit(appender, sources, counter, item);
                    }
                    entry = kept.next();
                } else {
                    Item item = change.get();
                    // Stale: the caller found it so against the trailer, or both sides carry a source and the
                    // stored one is the later. A stale change with nothing stored under its id - a tombstone the
                    // caller read - lands nothing; the trailer's own merge kept the tombstone.
                    boolean keep = stale.contains(item.id()) || (order == 0
                            && item.source() != NO_SOURCE && entry.get().source() != NO_SOURCE
                            && item.source() < entry.get().source());
                    if (!keep) {
                        emit(appender, sources, counter, item);
                    } else if (order == 0) {
                        if (regenerate) {
                            SUPERSEDED.increment();
                        }
                        emit(appender, sources, counter, entry.get());
                    }
                    if (order == 0) {
                        entry = kept.next();
                    }
                    change = incoming.next();
                }
            }
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(file);
            sources.discard();
            throw failed;
        }
        sources.close();
        return new Rendered(file, Files.size(file), md5 == null ? "" : HexFormat.of().formatHex(md5.digest()),
                HexFormat.of().formatHex(sha256.digest()), counter.count, sources.file, sources.size, sources.digest(),
                "");
    }

    static void emit(Codec.Appender appender, SourceWriter sources, Counter counter, Item item)
            throws IOException {
        if (item.entry().isPresent()) {
            appender.append(item.id(), item.entry().get());
            counter.count++;
        }
        if (item.source() != NO_SOURCE) {
            sources.line(item.id(), item.source(), item.entry().isEmpty());
        }
    }

    /**
     * The trailer of a stored document merged with a change set, read once as a stream after the body: every
     * stored line survives unless the change set names its id - then the change's source replaces it, no source
     * meaning no line - or a prefix removal covers it; a change whose source is below the stored line's is stale,
     * keeps the stored line, and is reported so the body can be merged again without it.
     */
    static TrailerMerge mergeTrailer(InputStream rest, SortedMap<String, Change> resolved,
                                             List<String> prefixes) throws IOException {
        Set<String> stale = new HashSet<>();
        SourceWriter merged = new SourceWriter();
        try (SourceReader stored = new SourceReader(rest)) {
            Iterator<Map.Entry<String, Change>> changes = resolved.entrySet().iterator();
            Map.Entry<String, Change> change = changes.hasNext() ? changes.next() : null;
            Optional<Source> line = stored.next();
            while (line.isPresent() || change != null) {
                int order = line.isEmpty() ? 1
                        : change == null ? -1
                        : line.get().id().compareTo(change.getKey());
                if (order < 0) {
                    Source source = line.get();
                    if (prefixes.stream().noneMatch(source.id()::startsWith)) {
                        merged.line(source.id(), source.seq(), source.absent());
                    }
                    line = stored.next();
                } else {
                    Change incoming = change.getValue();
                    if (order == 0 && incoming.source() != NO_SOURCE && incoming.source() < line.get().seq()) {
                        stale.add(change.getKey());
                        merged.line(line.get().id(), line.get().seq(), line.get().absent());
                    } else if (incoming.source() != NO_SOURCE) {
                        merged.line(change.getKey(), incoming.source(), incoming.fragment().isEmpty());
                    }
                    if (order == 0) {
                        line = stored.next();
                    }
                    change = changes.hasNext() ? changes.next() : null;
                }
            }
            merged.close();
            return new TrailerMerge(merged.file, merged.size, merged.digest(), stored.digest(), stale);
        } catch (IOException | RuntimeException failed) {
            merged.discard();
            throw failed;
        }
    }

    /** What merging a stored trailer with a change set produced: the new trailer (or none), the stored one's
     *  digest, and the ids whose change the stored provenance refuses. */
    record TrailerMerge(Path file, long size, String digest, String storedDigest, Set<String> stale) {
    }

    /** A local copy of a stored document's body and trailer, so a regeneration can merge into it from re-openable
     *  files: the daily pass's cost is a temporary file, never the heap. */
    static Rendered copy(Spec spec, Served stored) throws IOException {
        Path file = OwnerOnly.createTempFile("jenrepo-listing", ".tmp");
        Path trailer = OwnerOnly.createTempFile("jenrepo-listing-sources", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(file)) {
                stored.body().transferTo(out);
            }
            long size;
            try (OutputStream out = Files.newOutputStream(trailer)) {
                size = stored.rest().transferTo(out);
            }
            if (size == 0) {
                Files.deleteIfExists(trailer);   // a document with no trailer: nothing to reopen
            }
            return new Rendered(file, Files.size(file), "", "", Header.UNKNOWN, size == 0 ? null : trailer, size, "",
                    "");
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(file);
            Files.deleteIfExists(trailer);
            throw failed;
        }
    }

    /** One id on one side of a merge: its entry, or empty for a tombstone, and its source or {@link StoredListing#NO_SOURCE}. */
    record Item(String id, Optional<byte[]> entry, long source) {
    }

    /** A side of a merge: items in ascending id order, empty once exhausted. */
    @FunctionalInterface
    interface Items {

        Optional<Item> next() throws IOException;
    }

    /** A rendered document's entries beside its sources, walked in lockstep by id: an entry with a source line
     *  carries it, an entry without one carries none, and a source line without an entry is a tombstone. */
    static final class Joined implements Items, Closeable {

        private final Codec.Reader entries;
        private final SourceReader sources;
        private Optional<Map.Entry<String, byte[]>> entry;
        private Optional<Source> source;

        Joined(Codec.Reader entries, SourceReader sources) throws IOException {
            this.entries = entries;
            this.sources = sources;
            entry = entries.next();
            source = sources.next();
        }

        @Override
        public Optional<Item> next() throws IOException {
            if (entry.isEmpty() && source.isEmpty()) {
                return Optional.empty();
            }
            int order = entry.isEmpty() ? 1
                    : source.isEmpty() ? -1
                    : entry.get().getKey().compareTo(source.get().id());
            Item item;
            if (order < 0) {
                item = new Item(entry.get().getKey(), Optional.of(entry.get().getValue()), NO_SOURCE);
                entry = entries.next();
            } else if (order > 0) {
                item = new Item(source.get().id(), Optional.empty(), source.get().seq());
                source = sources.next();
            } else {
                item = new Item(entry.get().getKey(), Optional.of(entry.get().getValue()), source.get().seq());
                entry = entries.next();
                source = sources.next();
            }
            return Optional.of(item);
        }

        @Override
        public void close() throws IOException {
            try (Codec.Reader closing = entries; SourceReader also = sources) {
                // both closed, whichever throws
            }
        }
    }

    static Joined joined(Spec spec, InputStream body, long size, SourceReader sources) throws IOException {
        return new Joined(spec.codec().read(body, size), sources);
    }

    /** A rendered document reopened for a further merge: its body and, when it has one, its trailer. */
    static Joined joined(Spec spec, Rendered rendered) throws IOException {
        InputStream body = new BufferedInputStream(Files.newInputStream(rendered.file));
        try {
            SourceReader sources = rendered.sources == null ? SourceReader.none()
                    : new SourceReader(new BufferedInputStream(Files.newInputStream(rendered.sources)));
            try {
                return new Joined(spec.codec().read(body, rendered.size), sources);
            } catch (IOException | RuntimeException failed) {
                sources.close();
                throw failed;
            }
        } catch (IOException | RuntimeException failed) {
            body.close();
            throw failed;
        }
    }

    /** One line of a source trailer: an id, the source sequence, and whether it is a tombstone. */
    record Source(String id, long seq, boolean absent) {
    }

    /**
     * The source trailer read as a stream, digested as it goes so an unchanged trailer is recognised without
     * being held: a blank line, the {@value StoredListing#SOURCES} line, then one {@code id<TAB>seq[<TAB>absent]} line per
     * id in ascending order. Bytes that do not open so are no trailer, which is what every document written
     * before sources existed has after its body: nothing.
     */
    static final class SourceReader implements Closeable {

        private final BufferedReader lines;
        private final MessageDigest sha256 = ListingMerge.digest("SHA-256");
        private boolean started;
        private boolean exhausted;
        private long read;

        SourceReader(InputStream rest) {
            lines = new BufferedReader(new InputStreamReader(new DigestInputStream(new CountingInput(rest), sha256),
                    StandardCharsets.UTF_8));
        }

        static SourceReader none() {
            return new SourceReader(InputStream.nullInputStream());
        }

        Optional<Source> next() throws IOException {
            if (exhausted) {
                return Optional.empty();
            }
            if (!started) {
                started = true;
                String blank = lines.readLine();
                String magic = blank == null ? null : lines.readLine();
                if (!"".equals(blank) || !SOURCES.equals(magic)) {
                    exhausted = true;
                    return Optional.empty();
                }
            }
            String line = lines.readLine();
            if (line == null || line.isEmpty()) {
                exhausted = true;
                return Optional.empty();
            }
            String[] parts = line.split("\t", -1);
            if (parts.length < 2) {
                throw new IOException("malformed source line: " + line);
            }
            try {
                return Optional.of(new Source(parts[0], Long.parseLong(parts[1]), parts.length > 2));
            } catch (NumberFormatException malformed) {
                throw new IOException("malformed source line: " + line, malformed);
            }
        }

        /** The digest of the trailer's bytes once it has been read to its end, {@code ""} when there was none -
         *  the value a rendered trailer's digest is compared against. */
        String digest() throws IOException {
            while (!exhausted) {
                next();
            }
            lines.transferTo(Writer.nullWriter());
            return read == 0 ? "" : HexFormat.of().formatHex(sha256.digest());
        }

        @Override
        public void close() throws IOException {
            lines.close();
        }

        private final class CountingInput extends FilterInputStream {

            CountingInput(InputStream in) {
                super(in);
            }

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    read++;
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = super.read(buffer, offset, length);
                if (n > 0) {
                    read += n;
                }
                return n;
            }
        }
    }

    /** The source trailer written as a stream to a temporary file that exists only once a first line is written,
     *  digested as it goes; {@link #file} is {@code null} for a document with no sources. */
    static final class SourceWriter {

        private Path file;
        private OutputStream out;
        private final MessageDigest sha256 = ListingMerge.digest("SHA-256");
        private long size;

        void line(String id, long seq, boolean absent) throws IOException {
            if (out == null) {
                file = OwnerOnly.createTempFile("jenrepo-listing-sources", ".tmp");
                out = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(file)), sha256);
                write("\n" + SOURCES + "\n");
            }
            write(id + "\t" + seq + (absent ? "\tabsent" : "") + "\n");
        }

        private void write(String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            out.write(bytes);
            size += bytes.length;
        }

        void close() throws IOException {
            if (out != null) {
                out.close();
            }
        }

        String digest() {
            return file == null ? "" : HexFormat.of().formatHex(sha256.digest());
        }

        void discard() throws IOException {
            close();
            if (file != null) {
                Files.deleteIfExists(file);
            }
        }
    }
    /** {@code in} cut off after {@code limit} bytes - what lets a framed codec hand its inner codec the body
     *  without the footer, when the end of the body is known by arithmetic rather than by looking. */
    static InputStream limited(InputStream in, long limit) {
        return new FilterInputStream(in) {

            private long left = limit;

            @Override
            public int read() throws IOException {
                if (left <= 0) {
                    return -1;
                }
                int read = super.read();
                if (read >= 0) {
                    left--;
                }
                return read;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (left <= 0) {
                    return -1;
                }
                int read = super.read(buffer, offset, (int) Math.min(length, left));
                if (read > 0) {
                    left -= read;
                }
                return read;
            }
        };
    }

    static OutputStream digesting(OutputStream out, MessageDigest sha256, MessageDigest md5) {
        OutputStream digested = new DigestOutputStream(out, sha256);
        return md5 == null ? digested : new DigestOutputStream(digested, md5);
    }

    static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(algorithm + " is required of every JDK", impossible);
        }
    }

    /** A count a {@link Generator.Sink} lambda can raise; a local cannot be captured mutably. */
    static final class Counter {

        private long count;
    }
}
