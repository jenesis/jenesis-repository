The build cache
===============

> This is the build-cache module. The wire protocol, the per-project
> `cache.properties`, eviction and security below are current. What is no longer true anywhere in this
> file's history is that the cache is a product you deploy on its own: **there is no cache-only image and
> no dedicated cache deployment.** A cache and an artifact store normally sit on one machine over one
> store, so a deployment that wants only the cache runs the ordinary node with every format switched off -
> see *Deployment* below. Access is by hashed **credentials**, and the store is **multi-tenant**: the root
> holds one folder per tenant, each its own space of projects and credentials, and a key carries its tenant
> as a `<tenant>.<secret>` prefix.

A **build-cache server** for Jenesis - the server side of the
`BuildExecutorHttpCache` client: a content-addressed blob store that hands a build
the finished output of a step instead of re-running it. One deployment hosts many
**tenants**, each its own space of **projects** (a folder with its own cache policy)
and its own credentials - so it can be run privately or offered as a multi-tenant
product. A single-tenant deployment is just one tenant (e.g. `default`).

The HTTP surface is a thin `CacheController`; all the cache logic lives in a
framework-agnostic `Cache`, so it stays testable without a server. Each request runs
on its own **virtual thread** (`spring.threads.virtual.enabled`) and handlers are
plain blocking file/object-store I/O, so it scales while staying simple. The cache has
no backend of its own: it delegates into a segment of the repository's store, which is
why one store serves both halves and why they are one deployment rather than two.

How it works
------------

The client computes two hashes per step - `step` (the step's identity) and `inputs`
(a fold of every input file's content hash) - and addresses an entry at
`/<step>/<inputs>`. The body is an opaque zip the client packs and unpacks, so the
server never inspects content; it is a pure key/value blob store.

    GET  /<step>/<inputs>     -> 200 + blob, or 404         (a cache read)
    PUT  /<step>/<inputs>     -> 201, or 204 if present     (a cache write)
    GET  /actuator/health     -> 200 {"status":"UP"}        (health probe, via Actuator)

Observability is served by Spring Boot Actuator rather than the traffic port: `GET
/actuator/prometheus` exposes a `jenesis_cache_requests_total` counter labelled by
`tenant`, `project` and `outcome` (`hit`, `miss`, `stored`,
`present`, `rejected`, `invalid`, `unauthorized`, `forbidden`, `touched`, `full`), so
cache hit rates and rejected traffic can be scraped per tenant and project. Requests
rejected before a tenant/project is resolved are counted under an empty label. Actuator
follows Spring Boot conventions on the main port. On the node an operator actually runs
these are authorization-gated like every other `/actuator` path - only a deployment-wide
grant reads them - and `MANAGEMENT_SERVER_PORT` moves them to a dedicated port kept off
the public service if a deployment would rather they were not on it at all.

Two headers carry the routing and the credential - never the path, so neither is
exposed to anything that logs URLs:

    Jenesis-Cache-Project: <project>          ->  selects a project within the tenant
    Jenesis-Cache-Key:     <tenant>.<secret>  ->  the credential; its prefix selects the tenant

The key is a `<tenant>.<secret>` string: the server splits off the tenant prefix
(so a key is bound to exactly one tenant and is only ever checked against that
tenant's space), hashes the whole key, and looks it up under
`<root>/<tenant>/.users/<sha256(key)>/projects.properties`. That file maps projects to
roles - a comma list of `read` / `write`, or `*` for every project: a `read`
credential may GET, `write` is required to PUT - so consumers (CI, untrusted clients)
get credentials that fill their builds from the cache but can never write to it. An
entry lives at `<root>/<tenant>/<project>/<step>/<inputs>`, shared by all of that
tenant's credentials; tenants and projects never see each other's entries.

Reads stream the file to the response; writes stream to a temp file and are published
with an atomic rename, so a concurrent reader never sees a half-written entry. A PUT
for an entry that already exists answers `204` before the body is read, so a client
that sent `Expect: 100-continue` skips re-uploading a blob the server already holds.
On the client side the upload (PUT) runs on a background thread, so a slow or
unreachable cache never delays the build - reads (GET) are synchronous because the
build consumes their result, but writes never sit on its critical path.

Configuration
-------------

The server binds all of its configuration through Spring Boot `@ConfigurationProperties`
(`jenreg.cache.*`, plus `jenreg.s3.*` / `jenreg.azure-blob.*` for the cloud backends). Each property
also accepts the relaxed-binding environment variable below, which is how the Docker images configure
it; the admin (ui) image reads the shared concepts (root, backend, eviction) from the very same
variables:

| variable                 | default      | meaning                                                            |
| ------------------------ | ------------ | ------------------------------------------------------------------ |
| `PORT`                   | `8080`       | listening port                                                     |
| `JENREG_CACHE_ROOT`     | `data`       | storage directory holding the tenant folders; mount a volume here |
| `JENREG_CACHE_MAX_BYTES`| `2147483648` | per-entry upload cap (2 GiB)                                        |
| `JENREG_CACHE_PROJECTS` | `256`        | project configurations kept in the in-memory LRU cache             |
| `JENREG_CACHE_REAPER`   | `PT1H`       | interval of the `ttl` reaper sweep (ISO-8601 duration; `0`/`off` disables it) |
| `JENREG_CACHE_MIN_FREE` | *(unset)*    | minimum free **bytes** on the volume; below it a global sweep across all projects reclaims space (`0`/unset = off) |
| `JENREG_CACHE_MIN_FREE_PERCENT` | *(unset)* | minimum free **percent** of the volume (0-100); below it the same global sweep runs (`0`/unset = off) |
| `JENREG_CACHE_DEFAULT_PROJECT`  | `default` | project assumed when the `Jenesis-Cache-Project` header is absent |
| `JENREG_CACHE_PROJECT_REQUIRED` | `false`   | require the project header (disable the fallback above); a missing one is then `400` |
| `JENREG_CACHE_KEY`              | *(unset)* | a single **trial** bootstrap key: a request presenting exactly it gets full read+write on the default tenant, with no stored credential (logged with a strong warning) |
| `JENREG_CACHE_DEFAULT_TENANT`   | `default` | the tenant the bootstrap key grants |

By default a request without a `Jenesis-Cache-Project` header uses project `default` (rename it with
`JENREG_CACHE_DEFAULT_PROJECT`, or require the header with `JENREG_CACHE_PROJECT_REQUIRED`). A key is
always required. For a quick trial, `JENREG_CACHE_KEY=<secret>` makes the server accept that one key
(compared in constant time) as a full-access credential on the default tenant, so a single client can
use the cache without provisioning anything - it logs a prominent warning that this is for trials only
and that real keys should be issued per credential through the admin console. Normal
`<tenant>.<secret>` credentials keep working alongside it.

Everything else is per tenant and per project. Under the root, each tenant is a
folder; inside it each project holds its entries plus its `cache.properties`, and the
tenant's credentials live under `.users/`. Configs are parsed on first use and kept
in an LRU cache, re-read only when they change on disk - so revoking a grant takes
effect at once without re-parsing on every request:

    <root>/
      acme/                                        # a tenant
        .users/
          login.properties                         # console members (used by the admin ui, ignored here)
          <sha256(key)>/
            projects.properties                    # this credential's grants: <project> = read,write  (* = all)
            metadata.properties                    # label, created
        lib/                                        # a project
          cache.properties                          # this project's cache policy
          <step>/<inputs>                           # entries, shared by all of the tenant's credentials

A credential's `projects.properties` maps projects to roles (a comma list of `read` /
`write`, or `*` for every project); the plaintext key is never stored, only its
SHA-256, and the minted key is `<tenant>.<secret>`:

    lib   = read,write
    docs  = read
    *     = read

`cache.properties` tunes the project's storage (both keys optional):

| key    | default   | effect                                                                       |
| ------ | --------- | ---------------------------------------------------------------------------- |
| `size` | *(unset)* | total byte cap; over it, entries are evicted on a background thread until under (unset = no cap) |
| `lru`  | `true`    | evict the least-recently-modified entry first (`false` = most-recently)      |
| `ttl`  | *(unset)* | ISO-8601 duration (e.g. `P30D`); a dedicated reaper thread evicts entries not touched within it (unset = no age eviction) |

`size`/`lru` eviction is reactive, triggered on a store that pushes the project over its cap.
`ttl` eviction is periodic: a single reaper thread sweeps every project root each
`JENREG_CACHE_REAPER` interval and drops entries idle longer than that project's `ttl` (a GET or
HEAD touch counts as use, so an actively-read entry never ages out).

Disk headroom is a property of the shared volume, not any one project, so it is configured
server-wide (the env vars above) rather than per project. When `JENREG_CACHE_MIN_FREE` (bytes) or
`JENREG_CACHE_MIN_FREE_PERCENT` is set and the volume drops below either, a **global**
least-recently-used sweep across every project reclaims space until both floors are satisfied - on
each reaper tick and again before a store, where a PUT that still cannot fit is refused with `507
Insufficient Storage`. This is the safety net against a full disk: per-project `size`/`ttl` caps do
not by themselves bound total volume use.

A tenant with no credentials under `.users/` is unreachable - every request is
refused - so a project is live only once a credential has been granted it. Credentials
are normally minted and granted through the admin console, which also manages tenants and
their console members - the same console the node serves, since the cache and the artifact
store are one deployment.

Deployment
----------

**The cache is not deployed on its own.** There is no cache-only image: the two images this
product publishes is the bundle, and the cache is a
capability inside them. A deployment that wants only the build cache runs the ordinary node
with every format switched off - `JENREG_<FORMAT>=false` for each - and that is a supported
state rather than a misconfiguration: with no format enabled the artifact half simply is not
there, the node is healthy, and a request to `/repository/...` is unclaimed rather than an
error. `ServerToggleE2ETest` holds that, enumerating the formats from the SPI so a format
added later is switched off too.

That is deliberate rather than incidental. A cache and an artifact store normally sit on one
machine over one store, and the cache has no backend of its own - it delegates into a segment
of the repository's store - so splitting them into two deployments would mean two processes
over one store for no gain, and two security postures to keep in step.

What follows from it: the store settings, the credentials, the console, the actuator posture
and the TLS termination are the node's, documented with the node, and nothing here restates
them. What stays here is what is specific to the cache: the wire protocol above, the
per-project `cache.properties`, eviction and the key model below.

Security
--------

- Serve over HTTPS in production - the key is a bearer credential. TLS is terminated
  where the node's is, because it is the same node; the client refuses to send the key
  over plain http to anything but loopback (override only for testing with
  `-Djenreg.cache.insecure=true`).
- Treat keys like API tokens: mint one credential per holder (in the admin console),
  grant it `read` or `read,write` on the projects it needs, and revoke by deleting the
  credential (it takes effect on the next request). Only the key's SHA-256 is stored,
  so a key cannot be recovered from the volume. The tenant prefix, the project and the
  step/input path segments are all validated, so a request can only ever reach inside
  its own tenant's space - no traversal and no crossing tenants.

Relationship to the local cache
-------------------------------

This is the networked sibling of `BuildExecutorFileCache`: same content-addressing,
same zip entry format, and the same `cache.properties` knobs (`size`, `lru`) per
project. A build typically layers them - the always-on incremental cache under
`target/`, then a shared cache (local folder or this server) consulted on a miss.
`BuildExecutorHttpCache` is one implementation of the pluggable `BuildExecutorCache`
seam, so swapping local for remote is just a different URL.
