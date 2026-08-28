jenesis-repository
==================

[![release](https://img.shields.io/github/v/release/jenesis/jenesis-repository?label=release)](https://github.com/jenesis/jenesis-repository/releases/latest)
![build](https://github.com/jenesis/jenesis-repository/actions/workflows/build.yml/badge.svg)

> ### [Jenesis](https://jenesis.build) - a modern Java build tool
> _Java-native config, plugin-free, with `module-info.java` treated as a feature, not an afterthought._

**A dual-layout artifact repository.** It serves the same artifacts under the Maven layout, so any Maven,
Gradle or Jenesis build resolves them, and under the Jenesis module layout, so a modular build resolves them
by module name - publish a modular jar once and both ecosystems resolve it. It is also a
standards-compliant OCI registry over the same store, so `docker push` works against it too. Every layout,
storage backend, importer and console panel is a `ServiceLoader` plugin over one content-addressed store.

📖 **The user documentation lives at [jenesis.build/repository](https://jenesis.build/repository/)** -
deploying it, the formats, storage backends, proxying, authentication, import, observability and the
console. What follows is for people working *on* this repository.

## Building and running

The build tool is a git submodule (`build/jenesis` symlinks into `.jenesis/upstream`), so populate it once
after cloning:

```bash
git submodule update --init                          # the pinned build tool
java build/jenesis/Project.java build                # build everything
java build/jenesis/Project.java +source+store+s3 build   # one module and its dependencies
```

Run the all-in-one server against the filesystem backend - `source/bundle` is the launchable module that
carries every layout, backend, importer and the console; `source/server` on its own `requires` none of
them and has nothing to serve:

```bash
JENREG_AUTH=false JENREG_FILESYSTEM_ROOT=/var/lib/jenesis-repository \
  java -Djenesis.execute.module=source+bundle build/jenesis/Execute.java
```

The server discovers whatever is on its module path at startup, so a narrower deployment is a launcher
whose `requires` name only the modules it should speak. Authentication is enforced by default; a real
deployment starts from `JENREG_BOOTSTRAP_KEY` (a well-formed `jenk_<tenant>.<secret><checksum>` key the
server provisions at boot) and issues its keys through `/api/credentials`, while `JENREG_AUTH=false` is the
shortcut for local work. The web console is a second
process on port 8081 - the `dev` profile swaps its OAuth sign-in for a built-in `admin`/`admin` form login:

```bash
PORT=8081 SPRING_PROFILES_ACTIVE=dev JENREG_UI_SECURE_COOKIE=false \
JENREG_FILESYSTEM_ROOT=/var/lib/jenesis-repository \
  java -Djenesis.execute.module=source+bundle \
       -Djenesis.execute.mainClass=build.jenesis.repository.bundle.Console build/jenesis/Execute.java
```

`Dockerfile` builds the all-in-one image: `source/bundle` requires every implementation, and its
`bundle=true` packaging emits the resolved runtime closure that the image consumes, so a deployment is
trimmed by configuration rather than rebuilt.

## Module layout

Every module is a Java module under `source/`, and the split into `spi` and implementations is the extension
seam: a plugin implements an SPI and is discovered by `ServiceLoader`, never by the core naming it.

| Path | Module |
|------|--------|
| `source/server`, `source/server-spi` | The format-neutral dispatcher: routing, auth, the publish edge, the pull-through serve loop, and the `/api` surface. Knows no layout. |
| `source/store/{spi,filesystem,s3,gcs,azure}` | The content-addressed store and its backends. |
| `source/format/{spi,maven,java,oci,raw}` | The layouts, each a plugin: Maven, the Jenesis module layout, OCI/Docker, and raw. |
| `source/importer/{spi,maven,nexus,artifactory,index}` | Migration connectors that walk another repository and pull its artifacts in. |
| `source/proxy` | The upstream fetcher behind pull-through caching, with revalidation and a negative cache. |
| `source/walk/{spi,store}`, `source/gc/{spi,store}` | The resumable artifact walk, and mark-sweep garbage collection over it. |
| `source/ui` | The web console (`/console`, `/browse`) and its design system. |
| `source/oidc`, `source/ratelimit`, `source/usage` | Sign-in, the request-rate ceiling, and credential-usage tracking. |
| `source/observation/spi`, `source/posture/spi`, `source/icon/spi` | Observation hooks, security-posture advisories, and console iconography. |
| `source/feed`, `source/bundle`, `source/contract/testkit` | The advisory feed, the all-in-one launchable module, and the shared contract test kit. |

Each family's `testkit` module carries the contract tests an implementation must pass, so a new backend or
format is validated against the same suite the built-in ones are.

## Writing a plugin

A plugin is a module that `provides` one of the SPIs above. Two rules make the seam work:

- **Implement the contract, then run its test kit.** `source/*/testkit` exists so an implementation proves it
  behaves like the ones already shipping - a store backend that passes the store contract is one the server
  can drive without knowing which it got.
- **A publish screen is a `PublishInterceptor`.** It sees the artifact once it is stored content-addressed but
  before any pointer is linked, returns a verdict, and can withhold an already-linked path on read. No
  provider ships by default, so the chain is empty and every upload is accepted.

`AGENTS.md` carries the working conventions for this repository, and `docs/` holds the design notes that
outlive a single change.

## Tests

```bash
java build/jenesis/Project.java build     # compile and run the suite
```

Contract suites are tagged, and CI decides which tagged suites to run from what a change touches. A change to
an SPI is expected to arrive with the test-kit clause that pins the new behaviour, so every implementation
inherits it.

## Continuous integration and releases

`.github/workflows/build.yml` builds and tests on push and pull request, on JDK 25, and uploads `target/` on
failure. It logs in to Docker Hub when credentials are configured, only to avoid anonymous pull rate limits
for the container-backed tests.

`.github/workflows/release.yml` is dispatched by hand from the Actions tab, so any commit is releasable: the
optional `sha` input names the commit (default: the head it runs on) and the optional `tag` input names the tag
(`vX.Y.Z`; default: the next minor of the latest tag). JReleaser then signs, publishes and tags. `project.properties` carries the POM metadata.

## License

Apache License 2.0 - see [LICENSE](LICENSE). Copyright Rafael Winterhalter.

The console ships two third-party assets under `source/ui/META-INF/resources/`, which keep the terms their
own authors chose. [Pico CSS](https://picocss.com) 2.1.1 (`css/pico.min.css`) is MIT, and its notice ships
beside it in `css/pico.LICENSE.txt` because MIT asks that it accompany every copy.
[htmx](https://htmx.org) 2.0.4 (`js/htmx.min.js`) is 0BSD, which requires no notice and carries no copyright
line of its own.
