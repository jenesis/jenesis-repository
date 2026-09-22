Phase 0 findings — build layout decision (please review)
=========================================================

**TL;DR:** I had to switch the build from the `MODULAR_TO_MAVEN` layout (which you suggested) to the
plain **`MAVEN` layout** (a `pom.xml`). `MODULAR_TO_MAVEN` does not resolve Spring Boot's runtime
dependencies, so the app could not start. The MAVEN layout is still built entirely by the vendored
Jenesis tool and keeps the same `sources/` layout. This is the one decision from the approved plan
that changed, and it's worth your confirmation.

What was tried, and the evidence
--------------------------------

1. **`MODULAR_TO_MAVEN` + `module-info.java` (the planned approach).** Spring resolved and compiled
   fine, but:
   - `jpackage`/`jlink` app-image packaging failed: `automatic module cannot be used with jlink:
     micrometer.commons`. jlink refuses to link automatic modules, and Spring's closure is full of
     them — so the trimmed-runtime app-image (as used by jenesis-enterprise) is fundamentally
     incompatible with Spring.
   - Running on the module path then failed at runtime: `NoClassDefFoundError:
     org/apache/commons/logging/LogFactory`. The deeper problem: `MODULAR_TO_MAVEN` resolves the
     **JPMS module-graph closure of the `requires` directives**, not the **Maven transitive closure**.
     Only ~13 jars resolved — **no embedded Tomcat, no Jackson, no spring-webmvc**. Spring Boot pulls
     those via Maven transitivity and discovers some (e.g. Tomcat) reflectively at runtime; none of
     that is expressible as JPMS `requires` edges, so the module graph never includes them.

2. **`MAVEN` layout (`pom.xml`), chosen.** Jenesis resolves the **full Maven transitive closure**
   (Tomcat 11, Jackson, etc.) onto a **flat class path** — exactly how Spring Boot is designed to
   build and run. Verified: the app boots in ~1.3s (Spring Boot 4.1.0, embedded Tomcat 11), serves
   pages, and the whole GitHub OAuth2 redirect flow is correctly wired.

This still honours the intent behind your suggestion — "consume Spring via the Jenesis build" — it
just uses Jenesis's MAVEN descriptor instead of its module descriptor. Same vendored tool, same
`java build/jenesis/Make.java` commands, `sources/` unchanged.

Packaging
---------

Because the app-image route is out (jlink + automatic modules), the app ships as a plain
`java -cp '<deps>/*' build.jenesis.repository.ui.admin.Application` on a stock JRE (see `Dockerfile`). I also tried the
Jenesis **launcher bundle** (single jar): it boots, but its module-aware classloader breaks Spring's
component scanning and `application.properties` loading (the app fell back to Spring Boot's default
security and ignored config), so the flat class path on a JRE is the reliable option.

Open questions for you
----------------------

1. **Confirm the MAVEN-layout pivot.** I believe it's the right call (evidence above), but it changes
   the build mechanism you proposed. If you'd rather stay on `module-info.java`, the only viable way I
   see is to enumerate Spring's entire runtime closure as explicit `requires`/pins and fight
   module-path visibility for each non-modular jar — brittle and high-maintenance. I recommend MAVEN.

2. **Spring Boot version.** Pinned **4.1.0** (current GA; the `requires` had floated to it anyway).
   3.5.x is the mature prior line if you'd prefer it; the bump is a one-line change in `pom.xml`.

3. **GitHub OAuth app + first admin.** I can't complete a real GitHub login unattended. To finish
   verification you'll need to create a GitHub OAuth app and set `JENREG_UI_GITHUB_CLIENT_ID` /
   `JENREG_UI_GITHUB_CLIENT_SECRET` and `JENREG_UI_ADMINS=github/<your github id>`. Until
   then, use the `dev` profile (local login) to explore — see README.

4. **Identifier choice.** `users.properties` is keyed by **numeric GitHub id** (stable) as we
   discussed; the add-user form currently takes the numeric id (with optional login for display). If
   you'd like, I can add server-side resolution of a typed login → id via the GitHub API.

What was verified (with the `dev` profile, no GitHub needed)
------------------------------------------------------------

- Build green; app boots on a flat class path with full config (port, OAuth wiring, bean scanning).
- Auth: anonymous redirected to `/login`; `admin` vs `viewer`; **viewers get 403 on every mutation**.
- CSRF enforced on all POSTs (verified a POST is rejected without the token and succeeds with it).
- Projects list / project detail / admin pages render (Thymeleaf).
- Create project, save cache.properties, add/remove/generate keys, add/remove users — all persisted.
- Eviction: size-cap sweep deleted the oldest entry and kept the newer one; clear-all emptied the
  project and pruned empty step dirs; **config files were never touched**.
- Safety: a traversal project name (`../evil`) and a malformed `ttl` were rejected with nothing written.
- `Dockerfile` builds an image that runs the same flat-classpath command on a JRE.
