# Releasing ByteQuay

This file documents how a release moves from a `git tag` to an
installer on a user's Mac. It covers the release workflow, the
Gatekeeper / quarantine model that decides what users see on first
launch, and updates from the sidebar. The
[signed macOS releases runbook](scripts/macos-release.md) covers
certificates, notarization credentials, and signing checks.

---

## The release flow at a glance

| Stage         | How                                                                 |
|---------------|---------------------------------------------------------------------|
| Build the DMG | GitHub Actions on tag push (`release.yml`), signed with Developer ID and notarized |
| Hosting       | GitHub Releases assets: DMG, ZIP, and `update-darwin-<arch>.json`   |
| User installs | Download → standard "downloaded from the Internet" prompt → Open    |
| User upgrades | Sidebar **Update to v…** button, from the first signed release on   |

---

## Cutting a release (manual + automated paths)

### Versioning

These places carry the version number — keep them in sync before tagging.
The app reads its own version from `frontend/package.json` through
`app.getVersion()`.

| File                                                      | Field                                   |
|-----------------------------------------------------------|-----------------------------------------|
| `frontend/package.json`                                   | `version` and the four `@bytequay/*` dependencies |
| `frontend/packages/ui/package.json`                       | `version`                               |
| `frontend/plugins/{google,jira,lab}/package.json`         | `version` and the `@bytequay/ui` dependency |
| `frontend/package-lock.json`                              | the same fields for the root and the four workspaces |
| `backend/pom.xml`                                         | top-level `<version>`                   |
| every module `pom.xml` under `backend/`                   | parent `<version>`                      |
| `backend/agent-cli/src/main/java/com/bytequay/agent/cli/codex/CodexAppServerClient.java` | `ClientInfo` version |

The poms change together from `backend/`:

```bash
mvn versions:set -DnewVersion=0.5.2 -DgenerateBackupPoms=false
```

`npm version --workspaces` leaves the `@bytequay/*` dependency versions
behind, so edit the frontend rows by hand. Then check that
`git grep -n '<old version>'` finds only unrelated dependency versions.
The release workflow refuses a tag that differs from the version in
`frontend/package.json`.

### Tag + push

```bash
# bump the versions above, commit, push to main first
git push origin main

# then tag and push the tag
git tag v0.1.0 -m "First public release"
git push origin v0.1.0
```

### What happens automatically

The `release.yml` workflow at `.github/workflows/release.yml` triggers on
any tag matching `v*`:

1. Spins up a `macos-latest` runner.
2. Installs JDK 25 LTS + the Node 24 LTS version pinned in `.nvmrc`.
3. Imports the signing certificate and notarization key into a
   temporary keychain.
4. Runs `npm ci` then `npm run make`. The `generateAssets` Forge hook
   builds the Spring Boot JAR without tests, Checkstyle or license
   checks if it's missing; CI runs those gates. Forge signs the app
   and staples its notarization ticket.
5. Checks the signature, ticket, and Gatekeeper on the app and the
   app inside the ZIP, then signs, notarizes, and staples the DMG.
6. Writes `update-darwin-<arch>.json` beside the ZIP.
7. Uploads the DMG, ZIP, and update manifest to the matching GitHub
   Release.
8. Marks the release as a pre-release if the tag contains a `-`
   (e.g. `v0.2.0-rc1`).

If any check fails, nothing is uploaded. You only have to fill in the
release notes on github.com.

### Manual fallback

If a tag run fails for a flaky reason, use **Actions → Release → Run
workflow** with the tag, which overwrites assets of the same name. A
run on a branch keeps verified installers as an Actions artifact
without publishing.

Don't publish a DMG built locally with `npm run make`. It is unsigned,
so users would hit Gatekeeper's block, and it has no update manifest.

---

## Why first launch is silent on your machine but not for users

macOS Gatekeeper inspects only files that carry the
`com.apple.quarantine` extended attribute. That attribute is set by
**the thing that downloaded the file** — Safari, Chrome, AirDrop,
Mail. Locally built files don't have it, so a `npm run make` install
opens silently no matter what.

Verify the difference:

```bash
xattr frontend/out/make/.../ByteQuay-0.1.0-arm64.dmg
# → (no output — locally built, no quarantine)

xattr ~/Downloads/ByteQuay-0.1.0-arm64.dmg
# → com.apple.quarantine  (downloaded via browser)
```

To preview what an end user actually sees without round-tripping
through a real release, simulate the quarantine attribute on a local
build:

```bash
xattr -w com.apple.quarantine "0181;0;Safari;" \
  frontend/out/make/.../ByteQuay-0.1.0-arm64.dmg
open frontend/out/make/.../ByteQuay-0.1.0-arm64.dmg
```

---

## What users see at each signing level

| State                                  | First-launch dialog                                                                | Has an "Open" button? |
|----------------------------------------|-----------------------------------------------------------------------------------|----------------------|
| Locally built, no quarantine attribute | _nothing_ — opens silently                                                        | n/a                  |
| Unsigned (0.5.5 and older releases)    | "ByteQuay can't be opened because the developer cannot be verified."              | ❌ no — must right-click → Open |
| Signed but not notarized               | "macOS cannot verify ByteQuay is free of malware."                                | ✅ yes               |
| Signed + notarized (0.5.6 and later)   | "ByteQuay is an app downloaded from the Internet. Are you sure you want to open it?" | ✅ yes               |
| App Store                              | _nothing_                                                                          | n/a                  |

Signing a new release does not change files users downloaded before.
Users of an unsigned release download a signed one once.

---

## Updates from the sidebar

An installed signed build checks the latest stable release's
`update-darwin-<arch>.json` every hour. When a newer version is
published, the sidebar shows **Update to v…**. One click checks for
running work, downloads and verifies the ZIP, then restarts into the
new version.

The first signed build with the updater is 0.5.6, so 0.5.5 and older
cannot update themselves. Pre-releases are never offered, because
GitHub does not count them as the latest release. Keep the bundle
identifier and signing team the same across releases. Don't delete a
published ZIP or its manifest while that release is the latest.

---

## Universal binaries (Intel + Apple Silicon)

The DMG produced today is `arm64`-only. Intel Macs (and Rosetta
fallbacks) get an "app not supported on this Mac" dialog. Two
options when there's actual demand:

- **Two DMGs** — keep the current `arm64` build, add a second
  `forge.config.ts` matrix entry for `x64`. Workflow uploads both.
  Users pick the right one.
- **Universal binary** — add `arch: 'universal'` to `packagerConfig`.
  One DMG that runs everywhere, but ~70MB heavier on download.

Defer until someone asks. The app is targeted at developers, who
overwhelmingly run Apple Silicon by now.

---

## Tag-protection rules (when there's > 1 maintainer)

Tag pushes today only require repo Write access — for a solo project
that's just the owner. Once collaborators land, harden:

- **Settings → Tags → Add tag protection rule → pattern `v*`**, restrict
  who can create matching tags.
- **Settings → Actions → require approval** for first-time contributors
  so a tag push from a new collaborator pauses for review before
  spending macOS-runner minutes.

Not needed for ByteQuay today. Bookmark for later.
