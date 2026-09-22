package build.jenesis.repository.format.rpm;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.format.signing.OpenPgpSigner;

/**
 * An RPM repository's {@code primary.xml} as a stored listing, with {@code primary.xml.gz}, {@code repomd.xml} and
 * (when a signing key is provisioned) {@code repomd.xml.asc} derived from it on every write. The entries are the
 * {@code <package>} stanzas the publish stored, keyed by their pool location, and an entry exists exactly when the
 * package is servable - its pool pointer not withheld, its version not yanked - the screen the on-read generation
 * applied per stanza, applied here to the one stanza a write touches. The {@code <metadata>} wrapper's
 * {@code packages} count is the entry count, and {@code repomd.xml}'s revision and timestamp are the document's
 * sequence, so a re-read is byte-stable between writes.
 */
final class RpmListings {

    private static final String NS_COMMON = "http://linux.duke.edu/metadata/common";
    private static final String NS_RPM = "http://linux.duke.edu/metadata/rpm";

    /** A {@code primary.xml}: the wrapper around {@code <package>} stanzas, each keyed by its {@code location} href. */
    static final StoredListing.Codec PRIMARY = new StoredListing.Codec() {
        @Override
        public SortedMap<String, byte[]> split(byte[] document) {
            SortedMap<String, byte[]> entries = new TreeMap<>();
            String text = new String(document, StandardCharsets.UTF_8);
            int from = 0;
            while (true) {
                int start = text.indexOf("<package", from);
                if (start < 0) {
                    break;
                }
                int end = text.indexOf("</package>", start);
                if (end < 0) {
                    break;
                }
                end += "</package>".length();
                String stanza = text.substring(start, end);
                entries.put(locationOf(stanza), stanza.getBytes(StandardCharsets.UTF_8));
                from = end;
            }
            return entries;
        }

        @Override
        public byte[] join(SortedMap<String, byte[]> entries) {
            StringBuilder body = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            body.append("<metadata xmlns=\"").append(NS_COMMON).append("\" xmlns:rpm=\"").append(NS_RPM)
                    .append("\" packages=\"").append(entries.size()).append("\">\n");
            for (byte[] stanza : entries.values()) {
                body.append(new String(stanza, StandardCharsets.UTF_8));
                if (body.charAt(body.length() - 1) != '\n') {
                    body.append('\n');
                }
            }
            return body.append("</metadata>\n").toString().getBytes(StandardCharsets.UTF_8);
        }

        /**
         * The same document, written as the stanzas arrive - through a spool, because the root element carries the
         * package count and so cannot be written until the last stanza has gone by.
         *
         * <p>{@code primary.xml} is every package in the repository. Without this the inherited appender collected
         * all of them into a map and called {@link #join}, which is that document again as a {@code StringBuilder}
         * and again as its {@code String} - so the streaming generator below wrote into a buffer, and every
         * publish rewrote the repository's index in heap.
         */
        @Override
        public Appender append(OutputStream out) {
            return StoredListing.spooling(out,
                    (body, _, stanza) -> {
                        body.write(stanza);
                        if (stanza.length == 0 || stanza[stanza.length - 1] != '\n') {
                            body.write('\n');                   // join's rule, kept: one stanza per line
                        }
                    },
                    packages -> ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata xmlns=\"" + NS_COMMON
                            + "\" xmlns:rpm=\"" + NS_RPM + "\" packages=\"" + packages + "\">\n")
                            .getBytes(StandardCharsets.UTF_8),
                    "</metadata>\n".getBytes(StandardCharsets.UTF_8));
        }

        /**
         * The stored stanzas, one at a time, scanned out of the stream rather than out of the whole document.
         *
         * <p>The same {@code <package>}...{@code </package>} scan {@link #split} performs, over a window that is
         * refilled rather than over the document as a string. A stanza is bounded by what one package's metadata
         * costs, so holding one is fine; holding the file they are all in is what this avoids.
         */
        @Override
        public Reader read(InputStream in, long ignored) {
            byte[] open = "<package".getBytes(StandardCharsets.UTF_8);
            byte[] shut = "</package>".getBytes(StandardCharsets.UTF_8);
            return new Reader() {

                // Bytes, not characters. A window refilled from the stream splits multi-byte UTF-8 sequences at
                // arbitrary points, so decoding each window as it arrives would mangle any non-ASCII summary or
                // description a package carries. Both markers are ASCII and every continuation byte is >= 0x80,
                // so searching the bytes cannot match inside a character; only the extracted stanza is decoded.
                private byte[] pending = new byte[16384];

                private int length;

                private boolean drained;

                @Override
                public Optional<Map.Entry<String, byte[]>> next() throws IOException {
                    while (true) {
                        int start = StoredListing.indexOf(pending, 0, length, open);
                        int end = start < 0 ? -1 : StoredListing.indexOf(pending, start, length, shut);
                        if (end >= 0) {
                            end += shut.length;
                            byte[] stanza = Arrays.copyOfRange(pending, start, end);
                            System.arraycopy(pending, end, pending, 0, length - end);
                            length -= end;
                            return Optional.of(Map.entry(
                                    locationOf(new String(stanza, StandardCharsets.UTF_8)), stanza));
                        }
                        if (drained) {
                            return Optional.empty();
                        }
                        // Nothing complete in hand: drop what can no longer begin a stanza, then read more.
                        if (start > 0) {
                            System.arraycopy(pending, start, pending, 0, length - start);
                            length -= start;
                        }
                        if (length == pending.length) {
                            pending = Arrays.copyOf(pending, pending.length * 2);
                        }
                        int read = in.read(pending, length, pending.length - length);
                        if (read < 0) {
                            drained = true;
                        } else {
                            length += read;
                        }
                    }
                }

                @Override
                public void close() throws IOException {
                    in.close();
                }
            };
        }

        /** The first offset in {@code buffer[from, to)} where {@code pattern} occurs whole, or {@code -1}. */
    };

    private final Blobs blobs;
    private final ArtifactStore store;
    private final Function<Blobs, OpenPgpSigner> signer;

    RpmListings(Blobs blobs, Function<Blobs, OpenPgpSigner> signer) {
        this.blobs = blobs;
        this.store = blobs.store();
        this.signer = signer;
    }

    static String primary(String repo) {
        return "rpm/" + repo + "/repodata/primary.xml";
    }

    static String repomd(String repo, String name) {
        return "rpm/" + repo + "/repodata/" + name;
    }

    /** The reverse index a publish writes: {@code rpm/<repo>/repodata/by/<name>/<version>/<encoded location>}. */
    static String reverseKey(String repo, String name, String version, String location) {
        return "rpm/" + repo + "/repodata/by/" + name + "/" + version + "/"
                + URLEncoder.encode(location, StandardCharsets.UTF_8);
    }

    /** The entry id of a stanza: its {@code location} href, URL-encoded the way the stanza's own key is. */
    static String locationOf(String stanza) {
        int at = stanza.indexOf("<location");
        if (at < 0) {
            return "";
        }
        int href = stanza.indexOf("href=\"", at);
        if (href < 0) {
            return "";
        }
        int end = stanza.indexOf('"', href + 6);
        return URLEncoder.encode(unescape(stanza.substring(href + 6, end)), StandardCharsets.UTF_8);
    }

    private static String unescape(String attribute) {
        if (attribute.indexOf('&') < 0) {
            return attribute;
        }
        return attribute.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    StoredListing.Spec spec(String repo) {
        return StoredListing.Spec.of(primary(repo), PRIMARY, sink -> generate(repo, sink)).deriving(document -> {
            byte[] gz = RpmFormat.gzip(document.body());
            long seq = document.header().seq();
            StoredListing.Header gzHeader = StoredListing.Header.of(seq, gz);
            StoredListing.derive(store, primary(repo) + ".gz", gzHeader, gz);
            byte[] repomd = RpmFormat.repomd(seq / 1000L, document.header(), gzHeader);
            StoredListing.derive(store, repomd(repo, "repomd.xml"), seq, repomd);
            OpenPgpSigner signing = signer.apply(blobs);
            if (signing != null) {
                StoredListing.derive(store, repomd(repo, "repomd.xml.asc"), seq, signing.detachedSignature(repomd, OpenPgpSigner.Encoding.ARMOURED));
            }
        });
    }

    /**
     * Emit an entry per package, in the order the scan yields them.
     *
     * <p>The index names every package it covers, so collecting them into a sorted map held that whole set. The
     * scan's order is the sink's order - the store's lexicographic child order, which is where the sorted map's
     * ordering came from and is what now supplies it. The key here is the child name itself, which is what makes
     * that substitution sound: a key composed across nested scans would not be in scan order.
     */
    private void generate(String repo, StoredListing.Generator.Sink sink) throws IOException {
        String prefix = RpmFormat.indexPrefix(repo);
        ENTRIES.scan(store, prefix, name -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (!blobs.read(prefix + "/" + name, buffer)) {
                return;
            }
            String location = URLDecoder.decode(name, StandardCharsets.UTF_8);
            if (servable(repo, location)) {
                sink.accept(name, buffer.toByteArray());
            }
        });
    }

    /** A package was published (or imported, or backfilled): list it if it is servable. */
    void published(String repo, String location, byte[] stanza) throws IOException {
        String id = URLEncoder.encode(location, StandardCharsets.UTF_8);
        if (servable(repo, location)) {
            StoredListing.put(store, spec(repo), id, stanza);
        } else {
            StoredListing.remove(store, spec(repo), id);
        }
    }

    /** Re-decide one package's membership from the store's current state - after a hold, a release or a mark. */
    void refresh(String repo, String location) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read(RpmFormat.indexKey(repo, location), buffer)) {
            StoredListing.remove(store, spec(repo), URLEncoder.encode(location, StandardCharsets.UTF_8));
            return;
        }
        published(repo, location, buffer.toByteArray());
    }

    /** Re-derive {@code repomd.xml.asc} after a signing key is provisioned. */
    void rederive(String repo) throws IOException {
        // Streamed from the stored primary, never read whole: the same shape the Debian twin had on its rebuild leg,
        // which the debian-gzip canary showed failing at three hundred thousand stanzas under 512 MiB.
        StoredListing.Spec spec = spec(repo);
        Optional<StoredListing.Served> served = StoredListing.open(store, spec);
        if (served.isPresent()) {
            try (StoredListing.Served document = served.get()) {
                spec.derivation().after(document.derived());
            }
        }
    }

    private boolean servable(String repo, String location) throws IOException {
        if (blobs.withheld("rpm/" + repo + "/" + location)) {
            return false;
        }
        String[] nevra = RpmFormat.nevra(location.substring(location.lastIndexOf('/') + 1));
        if (nevra == null) {
            return true;
        }
        return Lifecycle.read(store, repo + "/" + nevra[0], nevra[1] + "-" + nevra[2] + "." + nevra[3])
                .filter(flag -> flag.state() == Lifecycle.State.YANKED)
                .isEmpty();
    }

    /** Regenerate the listing at this key if it is an RPM primary (its twins and repomd regenerate with it). */
    boolean rebuild(String listing) throws IOException {
        String[] segments = listing.split("/");
        if (!segments[0].equals("rpm") || segments.length != 4 || !segments[2].equals("repodata")) {
            return false;
        }
        if (segments[3].equals("primary.xml")) {
            StoredListing.rebuild(store, spec(segments[1]));
            return true;
        }
        return segments[3].equals("primary.xml.gz") || segments[3].equals("repomd.xml")
                || segments[3].equals("repomd.xml.asc");
    }

    /** The stride the repository-wide index is enumerated in. It <b>drains</b>: the index names every package by
     *  definition, so neither the names nor the round-trips that fetch them may cap it, and what is bounded is how
     *  many names are in hand at once. Capping either one silently omits packages - or, once the entry cap alone was
     *  lifted, stopped omitting them and started throwing instead, at exactly {@code steps x page} names. That is
     *  the ceiling the OCI tag canary hit at a million: a generator that raises {@code TraversalException} does not
     *  answer short, it never materialises the document at all. */
    private static final BoundedChildren ENTRIES = BoundedChildren.draining();
}
