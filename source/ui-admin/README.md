ui-admin
========

The admin console: the screens under `/ui/` that operate a repository - its repositories and what they hold,
publishing, staging, retention and cleanup, the review queue, credentials, members and tenants, settings, and the
build cache. It is not launched on its own. The bundle composes it into the same node as the repository, so both
answer on one port; `Application.start(int)` boots it alone, which is what its tests do.

Other modules add screens and menu entries through `ConsoleModuleProvider` (in `source/ui`), and every screen
renders through the shared `ConsoleLayout`, so a contribution looks and behaves like the console's own.

Sign-in, and every other dial the console reads, is listed with its default and its effective value on the
console's own Settings screen (`/ui/settings`), which reads the same stored settings as `/api/settings` and the
CLI. Members hold a role per tenant - viewer, editor or admin, each holding everything the one before it
does - and a deployment's super-admins hold every tenant.

For local exploration, the `dev` profile offers in-memory accounts - `root`/`root` as super-admin, and
`admin`/`admin`, `editor`/`editor` and `viewer`/`viewer` as members of a seeded `default` tenant. It refuses to
start on anything but a loopback address.
