package build.jenesis.repository.cache.storage.testkit;

import module java.base;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.CacheStorage.Entry;
import build.jenesis.repository.cache.storage.CacheStorage.Stored;
import build.jenesis.repository.walk.Traversal;

/**
 * A backend that keeps its shape and loses exactly one behaviour - the falsifier a {@link CacheStorageContract}
 * property declares, so the check that names that property can be shown to bite.
 *
 * <p>Every mutant here wraps the fixture's real storage rather than standing in for it. That matters: a hand-built
 * fake would answer a check for reasons of its own, and a check that passes against a fake says nothing about the
 * backend. Wrapping means everything except the one removed behaviour is still the real filesystem, S3, GCS or
 * Azure implementation, so a check that survives the mutation has genuinely stopped measuring its property.
 *
 * <p>The removal is deliberately the <em>plausible</em> defect, not an absurd one. A backend does not usually
 * answer {@code null} to everything; it forgets to honour an expectation token, keeps what a failed upload managed
 * to write, lists a leaf as though it were a container, or reports a capacity pair that contradicts itself. Each
 * mutant below is one of those, which is what makes a surviving check interesting rather than merely surprising.
 */
public enum CacheStorageMutant {

    /** The control. Nothing is removed, so every check must pass - this is what separates "the check bites" from
     *  "the fixture is broken". */
    NONE("nothing") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return delegate;
        }
    },

    /** The durability of a stored blob: the stream is consumed in full and nothing is written. */
    A_STORE_THAT_DROPS_THE_BLOB("the durability of a stored blob - the stream is read to its end and discarded") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public void store(Entry entry, InputStream in) throws IOException {
                    in.transferTo(OutputStream.nullOutputStream());
                }
            };
        }
    },

    /** The abort: a source that fails mid-stream commits the prefix it managed to read. */
    A_STORE_THAT_KEEPS_A_FAILED_WRITE("the abort - a source that fails mid-stream commits the prefix it read") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public void store(Entry entry, InputStream in) throws IOException {
                    // Read what the failing source yields, swallow its failure, and commit that prefix - the
                    // half-write the property exists to forbid.
                    ByteArrayOutputStream prefix = new ByteArrayOutputStream();
                    try {
                        in.transferTo(prefix);
                    } catch (IOException expected) {
                        // the source failed; commit anyway, which is the defect
                    }
                    delegate.store(entry, new ByteArrayInputStream(prefix.toByteArray()));
                }
            };
        }
    },

    /** The entry-address screen: a malformed project or hex segment is accepted instead of refused. */
    AN_ADDRESS_SCREEN_THAT_PASSES("the entry-address screen - a malformed address is accepted") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public void store(Entry entry, InputStream in) throws IOException {
                    try {
                        delegate.store(entry, in);
                    } catch (IllegalArgumentException refused) {
                        // the screen fired; pretend it did not
                    }
                }
            };
        }
    },

    /** The tenant subspace: scoping hands back the same storage, so siblings share everything. */
    A_SCOPE_THAT_IGNORES_THE_TENANT("the tenant subspace - scoping returns the same storage") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public CacheStorage scope(String tenant) {
                    return this;
                }
            };
        }
    },

    /** The tenant-name screen: any name is admitted, so the tenant segment stops being a name. */
    A_TENANT_NAME_SCREEN_THAT_PASSES("the tenant-name screen - any name is admitted") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public CacheStorage scope(String tenant) {
                    try {
                        return delegate.scope(tenant);
                    } catch (IllegalArgumentException refused) {
                        return delegate;
                    }
                }
            };
        }
    },

    /** The config round trip: one declared property does not come back. */
    A_CONFIG_THAT_FORGETS_A_KEY("the config round trip - one property does not come back") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public Properties readConfig(String project, String file) {
                    Properties read = delegate.readConfig(project, file);
                    if (read != null && !read.isEmpty()) {
                        read.remove(read.stringPropertyNames().iterator().next());
                    }
                    return read;
                }
            };
        }
    },

    /** The absence sentinel: a version token is answered for a config that does not exist. */
    A_CONFIG_VERSION_THAT_IS_ALWAYS_PRESENT("the absence sentinel - an absent config still answers a token") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public Object configVersion(String project) {
                    Object token = delegate.configVersion(project);
                    return token == null ? "invented" : token;
                }
            };
        }
    },

    /** The advance: the token is one constant, so a rewrite is indistinguishable from no write. */
    A_CONFIG_VERSION_THAT_NEVER_MOVES("the advance - the config token is one constant") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public Object configVersion(String project) {
                    return delegate.configVersion(project) == null ? null : "frozen";
                }
            };
        }
    },

    /** The config-path screen: a path that escapes the scope, or a hostile file name, is written anyway. */
    A_CONFIG_PATH_SCREEN_THAT_PASSES("the config-path screen - an escaping path is written") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public void writeFile(String path, Properties properties) throws IOException {
                    try {
                        delegate.writeFile(path, properties);
                    } catch (IllegalArgumentException refused) {
                        // the screen fired; pretend it did not
                    }
                }

                @Override
                public void writeConfig(String project, String file, Properties properties) throws IOException {
                    try {
                        delegate.writeConfig(project, file, properties);
                    } catch (IllegalArgumentException refused) {
                        // the screen fired; pretend it did not
                    }
                }
            };
        }
    },

    /** The nested-file round trip: a written file reads back empty. */
    A_FILE_TREE_THAT_FORGETS("the nested-file round trip - a written file reads back empty") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public Properties readFile(String path) {
                    return new Properties();
                }
            };
        }
    },

    /** The listing's shape: a leaf document is emitted beside the immediate child containers. */
    A_LISTING_THAT_INCLUDES_LEAVES("the listing's shape - a leaf is emitted as though it were a container") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public Traversal.Result listDir(String prefix, String cursor, int limit, Consumer<String> names) {
                    Traversal.Result result = delegate.listDir(prefix, cursor, limit, names);
                    names.accept("a-leaf.properties");
                    return result;
                }
            };
        }
    },

    /** The recursive delete: the subtree is left where it was. */
    A_RECURSIVE_DELETE_THAT_STOPS("the recursive delete - the subtree is left in place") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public void deleteDir(String path) {
                    // nothing
                }
            };
        }
    },

    /** The expectation: a versioned write lands whatever token it was given. */
    A_VERSIONED_WRITE_THAT_ALWAYS_LANDS("the expectation - a versioned write ignores the token it was handed") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public boolean writeFileVersioned(String path, Properties properties, Object expected)
                        throws IOException {
                    delegate.writeFile(path, properties);
                    return true;
                }
            };
        }
    },

    /** The token's per-version identity: one constant stands for every version of the document. */
    A_VERSION_TOKEN_THAT_IS_CONSTANT("the token's per-version identity - one constant covers every version") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public Object fileVersion(String path) {
                    return delegate.fileVersion(path) == null ? null : "frozen";
                }
            };
        }
    },

    /** The enumeration's shape: a config document is enumerated as though it were an entry blob. */
    AN_ENUMERATION_THAT_INCLUDES_THE_CONFIG("the enumeration's shape - a config document is enumerated as a blob") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public Traversal.Result entries(String project, String cursor, int limit, Consumer<Stored> entries) {
                    Traversal.Result result = delegate.entries(project, cursor, limit, entries);
                    entries.accept(new Stored(1L, Instant.EPOCH, "config"));
                    return result;
                }
            };
        }
    },

    /** The delete: the enumerated blob stays where it was. */
    A_DELETE_THAT_DOES_NOTHING("the delete - the enumerated blob stays where it was") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public void delete(Stored entry) {
                    // nothing
                }
            };
        }
    },

    /** Touch's safety: recording recency destroys the entry it was asked about. */
    A_STAMP_THAT_DESTROYS("the stamp's safety - recording recency destroys the entry") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public void stamp(Entry entry, Instant at, Instant previous) {
                    try {
                        delegate.store(entry, InputStream.nullInputStream());
                    } catch (IOException cause) {
                        throw new UncheckedIOException(cause);
                    }
                }
            };
        }
    },

    /** The stamp's substance: recording recency writes nothing, so it reads back as never used. */
    A_STAMP_THAT_IS_FORGOTTEN("the stamp's substance - recording recency writes nothing") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public void stamp(Entry entry, Instant at, Instant previous) {
                }
            };
        }
    },

    /** The recency's reach: an entry never stamped reads as absent, so its own time is never consulted. */
    A_RECENCY_THAT_KNOWS_ONLY_STAMPS("the recency's reach - an entry never stamped reads as having none") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public Optional<CacheStorage.Recency> recency(Entry entry) {
                    return delegate.recency(entry).filter(CacheStorage.Recency::stamped);
                }
            };
        }
    },

    /** The capacity pair: usable reports the unlimited sentinel while total reports a real number. */
    A_CAPACITY_PAIR_THAT_DISAGREES("the capacity pair - usable is the unlimited sentinel, total is a real number") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public long usableSpace() {
                    return -1L;
                }

                @Override
                public long totalSpace() {
                    long total = delegate.totalSpace();
                    return total < 0 ? 1L << 40 : total;
                }
            };
        }
    },

    /**
     * The bound: every enumeration ignores the caller's limit and answers in one page.
     *
     * <p>The substituted bound is generous rather than {@link Integer#MAX_VALUE}. Every backend in this family
     * collects {@code limit + 1} to learn whether more remains, and that expression overflows at {@code MAX_VALUE}
     * - so a mutant that passed it would be measuring an arithmetic bug rather than the property, and would report
     * "the check broke" instead of "the check survived". A bound far above anything a check seeds removes the
     * caller's limit just as completely and stays inside the domain every backend already handles.
     */
    AN_ENUMERATION_THAT_IGNORES_THE_LIMIT("the bound - every enumeration ignores the caller's limit") {
        @Override
        public CacheStorage decorate(CacheStorage delegate) {
            return new Forwarding(delegate, this) {
                @Override
                public Traversal.Result projects(String cursor, int limit, Consumer<String> names) {
                    return delegate.projects(cursor, UNBOUNDED, names);
                }

                @Override
                public Traversal.Result entries(String project, String cursor, int limit, Consumer<Stored> entries) {
                    return delegate.entries(project, cursor, UNBOUNDED, entries);
                }

                @Override
                public Traversal.Result listDir(String prefix, String cursor, int limit, Consumer<String> names) {
                    return delegate.listDir(prefix, cursor, UNBOUNDED, names);
                }
            };
        }
    };

    /** A bound far above anything a contract check seeds, and far below where {@code limit + 1} overflows. */
    private static final int UNBOUNDED = 100_000;

    private final String removes;

    CacheStorageMutant(String removes) {
        this.removes = removes;
    }

    /** What this mutant takes away, phrased to complete "the mutation removes ...". */
    public String removes() {
        return removes;
    }

    /** The fixture's storage with this mutant's one behaviour removed. */
    public abstract CacheStorage decorate(CacheStorage delegate);

    /**
     * Everything the backend does, forwarded. A mutant overrides the single method its removal lives in, so the
     * other twenty stay the real backend - including {@link #scope}, which re-wraps, so a check that scopes a
     * fresh subspace still meets the mutation rather than escaping it.
     */
    abstract static class Forwarding implements CacheStorage {

        private final CacheStorage delegate;
        private final CacheStorageMutant mutant;

        Forwarding(CacheStorage delegate, CacheStorageMutant mutant) {
            this.delegate = delegate;
            this.mutant = mutant;
        }

        @Override
        public CacheStorage scope(String tenant) {
            return mutant.decorate(delegate.scope(tenant));
        }

        @Override
        public boolean projectExists(String project) {
            return delegate.projectExists(project);
        }

        @Override
        public Properties readConfig(String project, String file) {
            return delegate.readConfig(project, file);
        }

        @Override
        public Object configVersion(String project) {
            return delegate.configVersion(project);
        }

        @Override
        public boolean exists(Entry entry) {
            return delegate.exists(entry);
        }

        @Override
        public Optional<CacheStorage.Recency> recency(Entry entry) {
            return delegate.recency(entry);
        }

        @Override
        public void stamp(Entry entry, Instant at, Instant previous) {
            delegate.stamp(entry, at, previous);
        }

        @Override
        public void read(Entry entry, OutputStream out) throws IOException {
            delegate.read(entry, out);
        }

        @Override
        public void store(Entry entry, InputStream in) throws IOException {
            delegate.store(entry, in);
        }

        @Override
        public Traversal.Result projects(String cursor, int limit, Consumer<String> names) {
            return delegate.projects(cursor, limit, names);
        }

        @Override
        public Traversal.Result entries(String project, String cursor, int limit, Consumer<Stored> entries) {
            return delegate.entries(project, cursor, limit, entries);
        }

        @Override
        public void delete(Stored entry) {
            delegate.delete(entry);
        }

        @Override
        public long usableSpace() {
            return delegate.usableSpace();
        }

        @Override
        public long totalSpace() {
            return delegate.totalSpace();
        }

        @Override
        public void createProject(String project) throws IOException {
            delegate.createProject(project);
        }

        @Override
        public void writeConfig(String project, String file, Properties properties) throws IOException {
            delegate.writeConfig(project, file, properties);
        }

        @Override
        public Properties readFile(String path) {
            return delegate.readFile(path);
        }

        @Override
        public void writeFile(String path, Properties properties) throws IOException {
            delegate.writeFile(path, properties);
        }

        @Override
        public boolean writeFileVersioned(String path, Properties properties, Object expected) throws IOException {
            return delegate.writeFileVersioned(path, properties, expected);
        }

        @Override
        public Object fileVersion(String path) {
            return delegate.fileVersion(path);
        }

        @Override
        public Traversal.Result listDir(String prefix, String cursor, int limit, Consumer<String> names) {
            return delegate.listDir(prefix, cursor, limit, names);
        }

        @Override
        public void deleteDir(String path) throws IOException {
            delegate.deleteDir(path);
        }
    }
}
