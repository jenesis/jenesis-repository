jenesis-ui
==========

The **admin console**, a module of the jenesis-enterprise monorepo (see [`../README.md`](../README.md)).
A standalone Spring Boot app (Spring Security 7 + Thymeleaf) that signs users in with **GitHub
and/or any OpenID Connect provider** and manages both products through one signed-in, per-tenant
console. For the multi-tenant **cache**: pick a tenant ("instance"), then browse its projects,
edit each project's cache settings, create projects, trigger eviction, mint and grant access
credentials, and manage that tenant's console members (viewer/editor/admin). For the multi-tenant
**artifact repository**: under **Repositories**, list the tenant's named repositories and, per
repository, browse published releases, promote or drop staging repositories, edit the retention
policy, pin or unpin versions, and preview or run cleanup. This is the authenticated GUI for both;
the repository server's own built-in page is an optional headless panel over the same REST API. It
reads/writes the cache volume through the cache storage SPI and the repository through the
`ArtifactStore` SPI, scoping each to the signed-in user's tenant. See
[`PHASE0-FINDINGS.md`](PHASE0-FINDINGS.md) for the build-layout rationale.

Build & run (from the repo root)
--------------------------------

    java build/jenesis/Make.java +server+ui build
    docker build -t jenesis-ui --build-arg MODULES="module-server%2Fui" --build-arg BASE=eclipse-temurin:25-jdk \
      --build-arg MAINMODULE=build.jenesis.repository.ui.admin --build-arg MAINCLASS=build.jenesis.repository.ui.admin.Application .

Local exploration without a GitHub app — the `dev` profile (in-memory `root`/`root` super-admin, plus
`admin`/`admin`, `editor`/`editor`, `viewer`/`viewer` as members of a seeded `default` tenant):

    SPRING_PROFILES_ACTIVE=dev JENREG_UI_SECURE_COOKIE=false JENREG_CACHE_ROOT=/path/to/data \
      java -Djenesis.execute.module=server/ui build/jenesis/Execute.java

Configuration (environment)
---------------------------

`JENREG_CACHE_ROOT` (the multi-tenant root, shared with the cache server) and `JENREG_UI_ADMINS`
(comma-separated provider-qualified ids/logins that are **super-admins**), plus at least one sign-in
provider:

- **GitHub** — `JENREG_UI_GITHUB_CLIENT_ID` / `JENREG_UI_GITHUB_CLIENT_SECRET` (members keyed `github/<id>`);
- **any OpenID Connect provider** (Google, Keycloak, Okta, Azure AD, Auth0, …) —
  `JENREG_UI_OIDC_ISSUER_URI` + `JENREG_UI_OIDC_CLIENT_ID` / `JENREG_UI_OIDC_CLIENT_SECRET`
  (endpoints discovered from the issuer at startup; members keyed `oidc/<sub>`;
  `JENREG_UI_OIDC_NAME` labels the button).

Both can be enabled at once (the login page shows a button per provider); if neither is set, sign-in
is disabled and the app shows a notice. `JENREG_UI_SECURE_COOKIE` (`false` only for plain-HTTP local
testing). Deploy alongside the cache server with the same volume mounted; HTTPS terminates at the
ingress (also the OAuth/OIDC callback host). Because an OIDC `sub` is opaque, bootstrap the first
super-admin in `JENREG_UI_ADMINS` by login/email (it matches either), then add members from the
console once they have signed in.

Two tiers of access. **Super-admins** (`JENREG_UI_ADMINS`) see every tenant, create and delete
tenants, are admin within all of them, and run the volume-wide disk reclaim. Everyone else sees only
the tenants they belong to, with a per-tenant role from that tenant's `.users/login.properties`:
**viewer** (read-only: browse projects, credentials, and repositories), **editor** (viewer plus
create/edit projects, run eviction, mint/grant/revoke/delete credentials, and manage repositories:
promote or drop staging, edit retention, pin versions, and run cleanup), and **admin** (editor plus
manage the tenant's console members, including promoting other admins). A user who can reach only one tenant
has it selected automatically and never sees the instances list; minted keys carry their tenant as a
`<tenant>.<secret>` prefix.
