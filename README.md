jenesis-repository
==================

[![release](https://img.shields.io/github/v/release/jenesis/jenesis-repository?label=release)](https://github.com/jenesis/jenesis-repository/releases/latest)
![build](https://github.com/jenesis/jenesis-repository/actions/workflows/build.yml/badge.svg)

> ### [Jenesis](https://jenesis.build) - a modern Java build tool
> _Java-native config, plugin-free, with `module-info.java` treated as a feature, not an afterthought._

**An artifact repository for twenty-five package formats.** npm, PyPI, Go, Cargo, NuGet, RubyGems, Debian,
RPM, apk, Conda, Conan, CocoaPods, Composer, Swift, Helm, Homebrew, Hugging Face, Terraform, winget, Ivy -
and Maven, OCI, raw and the Jenesis module layout.

It is dual-layout at its core: the same artifacts resolve under the Maven layout, so any Maven, Gradle or
Jenesis build finds them, and under the Jenesis module layout, so a modular build resolves them by module
name - publish a modular jar once and both ecosystems resolve it. It is also a standards-compliant OCI
registry over the same store, so `docker push` works against it too.

A publish can be screened against OSV and the GitHub Advisory Database: a package at or above the configured
severity is refused or withheld for review, and the console's review queue says why and releases it.

Every format, storage backend, importer and console panel is a `ServiceLoader` plugin over one
content-addressed store, so twenty of those formats dedupe against each other: an npm tarball and a PyPI
wheel of identical bytes are stored once, and one reference scan answers for all of them.

📖 **The user documentation lives at [jenesis.build/repository](https://jenesis.build/repository/)** -
deploying it, the formats, storage backends, proxying, authentication, import, observability and the
console. What follows is for people working *on* this repository.

## Building and running

The build tool is a git submodule (`build/jenesis` symlinks into `build/.upstream`), so populate it once
after cloning:

```bash
git submodule update --init                          # the pinned build tool
java build/jenesis/Make.java build                # build everything
java build/jenesis/Make.java +source+store+s3 build   # one module and its dependencies
```

Run the server against the filesystem backend - `source/bundle` is the launchable module that
carries every layout, backend, importer and the console; `source/server` on its own `requires` none of
them and has nothing to serve:

```bash
JENREG_AUTH=false JENREG_FILESYSTEM_ROOT=/var/lib/jenesis-repository \
  java -Djenesis.execute.module=source+bundle build/jenesis/Execute.java
```

The server discovers whatever is on its module path at startup, so a narrower deployment is a launcher
whose `requires` name only the modules it should speak. Authentication is enforced by default; a real
deployment starts from `JENREG_BOOTSTRAP_KEY` (a well-formed `jenk_<tenant>.<secret><checksum>` key that
`java -Djenesis.execute.module=source+server-spi build/jenesis/Execute.java` mints and the server provisions at boot) and issues its keys through `/api/credentials`, while `JENREG_AUTH=false` is the
shortcut for local work. **The web console runs in that same process**, on the same port: the launcher
above scans it in, so `/` and `/console` are served beside the repository's own routes. It used to be a
second entry point on port 8081, which is why an older reading of this file describes one. The `dev`
profile swaps its OAuth sign-in for a built-in `admin`/`admin` form login:

```bash
SPRING_PROFILES_ACTIVE=dev JENREG_FILESYSTEM_ROOT=/var/lib/jenesis-repository \
  java -Djenesis.execute.module=source+bundle build/jenesis/Execute.java
```

The profile also lets the session cookie travel over plain http, which it must to survive a sign-in without
TLS. That used to be a variable an operator set (`JENREG_UI_SECURE_COOKIE=false`); a switch that turns session
hardening off in a deployment is not one worth having, so it belongs to the profile that already means "this
is not a deployment".

The image is built by the build rather than by a hand-written `Dockerfile`: `source/bundle` requires every
implementation, its `bundle=true` packaging emits the resolved runtime closure, and its `docker=` packaging
line makes `stage` write a ready-to-build context - `java -Djenesis.test.skip=true build/jenesis/Make.java stage`,
then `docker build -t jenesis-repository:free 'target/stage/docker/output/module-source%2Fbundle'` is the image.
There was a `Dockerfile` here that re-ran the whole build inside Docker - a second mechanism for a job the
shared one does - and the deployment settings it carried (`JENREG_FILESYSTEM_ROOT=/data`, a `VOLUME`) belong
to a deployment's own descriptor, which can make them conditional on the storage backend where an image cannot:
a baked-in `VOLUME` cannot be un-declared by a consumer, so an object-store deployment would create an anonymous
volume on every run that it never writes to. So the image declares no store root: name one with
`JENREG_FILESYSTEM_ROOT` and mount a volume there, or select an object store.

### Dependency versions, and the ones held back

Every dependency's version and checksum is an entry in `build.jenesis/pin-repository.properties`, which every
module imports with `@jenesis.bom`; no descriptor pins anything of its own. A refresh rewrites the file through the
build tool - `java -Xmx4g -Djenesis.dependency.pin=versions -Djenesis.test.skip=true
-Djenesis.pin.file=build.jenesis/pin-repository.properties build/jenesis/Make.java pin` - and the writer keeps no
comments, so the versions deliberately held below what a `-Djenesis.resolver.maven=stable` refresh proposes are
listed here with the reason. A refresh that moves one of these is reverted by hand before it is committed:

- **`commons-fileupload` 1.5.** 1.6.0's module descriptor requires `servlet.api` and `portlet.api` without
  `static`, which its own POM marks provided, so a module-path boot layer fails on a module nothing carries.
- **WireMock 4.0.0-beta.38**, the whole `org.wiremock` family. The 4.x beta is used by decision; the stable line
  is 3.x, so a stable refresh would move back to it.

## Module layout

Every module is a Java module under `source/`, and the split into `spi` and implementations is the extension
seam: a plugin implements an SPI and is discovered by `ServiceLoader`, never by the core naming it.

| Path | Module |
|------|--------|
| `source/server`, `source/server-spi` | The format-neutral dispatcher: routing, auth, the publish edge, the pull-through serve loop, and the `/api` surface. Knows no layout. |
| `source/store/{spi,filesystem,s3,gcs,azure}` | The content-addressed store and its backends. |
| `source/format/*` | Twenty-five layouts, each a plugin. `{spi,maven,java,oci,raw,jenesis,lifecycle}` are the published-tree ones; the rest - npm, PyPI, Go, Cargo, NuGet, gems, Debian, RPM, apk, Conda, Conan, CocoaPods, Composer, Swift, Helm, Homebrew, Hugging Face, Terraform, winget, Ivy - keep their bytes in the shared `blobs/` namespace. `signing` is the OpenPGP release signing three of them share. |
| `source/importer/*`, and an importer inside fifteen formats | Migration connectors that walk another repository and replay each asset through the owning format's real publish path. |
| `source/proxy` | The upstream fetcher behind pull-through caching, with revalidation and a negative cache. |
| `source/walk/{spi,store}`, `source/gc/{spi,store}` | The resumable artifact walk, and mark-sweep garbage collection over it. |
| `source/ui` | The web console (`/console`, `/browse`) and its design system. |
| `source/oidc`, `source/ratelimit`, `source/usage` | Sign-in, the request-rate ceiling, and credential-usage tracking. |
| `source/observation/spi`, `source/posture/spi`, `source/icon/spi` | Observation hooks, security-posture advisories, and console iconography. |
| `source/gate/store`, `source/gate-wiring`, `source/compliance/*` | The publish gate: the Maven and OCI inspectors that name what a publish is, the OSV and GitHub advisory feeds (off until an operator switches them on), policy-as-code and attestation admission, the scheduled rescan, signature verification, and the review queue where a hold is released. |
| `source/findings/store`, `source/health/store` | The findings and maintainer-health ledgers the gate and its screens read. |
| `source/feed`, `source/bundle`, `source/contract/testkit` | The advisory feed, the launchable module, and the shared contract test kit. |

Each family's `testkit` module carries the contract tests an implementation must pass, so a new backend or
format is validated against the same suite the built-in ones are.

## Writing a plugin

A plugin is a module that `provides` one of the SPIs above. Two rules make the seam work:

- **Implement the contract, then run its test kit.** `source/*/testkit` exists so an implementation proves it
  behaves like the ones already shipping - a store backend that passes the store contract is one the server
  can drive without knowing which it got.
- **A publish screen is a `PublishInterceptor`.** It sees the artifact once it is stored content-addressed but
  before any pointer is linked, returns a verdict, and can withhold an already-linked path on read. The
  publish gate (`source/gate/store`) is one, armed by `source/gate-wiring`; a composition without the wiring
  screens nothing and accepts every upload.

`AGENTS.md` carries the working conventions for this repository, and `docs/` holds the design notes that
outlive a single change.

## Tests

```bash
java build/jenesis/Make.java build     # compile and run the suite
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
