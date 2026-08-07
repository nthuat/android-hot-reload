# Releasing to Maven Central

This covers publishing `:gradle-plugin` (`dev.thuat:gradle-plugin`, plugin id
`dev.thuat.hotreload`) and `:runtime` (`dev.thuat:hotreload-runtime`) to Maven Central via the
[Central Portal](https://central.sonatype.com), using the
[`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin)
plugin. `:cli` and `:agent` are never published here: the CLI ships as a GitHub Release zip with
the agent's `.so` bundled inside it.

As of this writing **no release has been published to Central yet**: the DNS TXT record proving
ownership of the `dev.thuat` / `thuat.dev` namespace is live, but no publish has been run. Steps
below assume that's still true; skip step 1 once it's done for good.

## One-time setup (per Central Portal account, do once)

1. **Namespace verification**. Already done: `thuat.dev` has a Central Portal DNS TXT record
   (`dig +short TXT thuat.dev` returns the token Central Portal issued). Confirm at
   [central.sonatype.com/publishing/namespaces](https://central.sonatype.com/publishing/namespaces)
   that `dev.thuat` shows as verified.
2. **Generate a user token** at [central.sonatype.com/account](https://central.sonatype.com/account)
   → "Generate User Token". This gives you a username/password pair (NOT your login password,
   but a generated token) to put in `~/.gradle/gradle.properties`:
   ```properties
   mavenCentralUsername=<generated username>
   mavenCentralPassword=<generated password>
   ```
   Never commit these. `~/.gradle/gradle.properties` is outside the repo and untracked by git.
3. **Generate a GPG signing key** (skip if you already have one you want to reuse):
   ```bash
   gpg --full-generate-key   # RSA and RSA, 4096 bits, no expiration (or a long one), real name + email
   gpg --list-secret-keys --keyid-format LONG   # note the key id, e.g. 1234567890ABCDEF
   ```
   Central itself doesn't require the key to be on a public keyserver, but publishing a copy makes
   it easier for consumers to verify signatures:
   ```bash
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   ```
4. **Export the key for in-memory signing** (this is what CI/local publishing actually reads;
   see the warning about GnuPG version compatibility below):
   ```bash
   gpg --export-secret-keys --armor <KEY_ID> > /tmp/signing-key.asc
   ```
   Add to `~/.gradle/gradle.properties` (or export as env vars; see below):
   ```properties
   signingInMemoryKey=<paste the full contents of /tmp/signing-key.asc, including BEGIN/END lines>
   signingInMemoryKeyPassword=<the key's passphrase, empty string if none>
   ```
   Do **not** set `signingInMemoryKeyId`: Gradle's signing plugin expects the *short* 8-hex-digit
   form (e.g. `0x1234ABCD`), not the 16-digit "long" key id GnuPG prints by default, and getting
   this wrong throws a confusing `IllegalStateException` at sign time. It's optional; omitting it
   entirely lets Gradle pick the (only) key in the in-memory keyring, which is simpler and always
   correct for a single-key setup.

   Then delete the plaintext export: `rm /tmp/signing-key.asc`.

   **GnuPG version note**: keys exported by very new GnuPG builds (2.5.x dev branch, as opposed to
   the stable 2.4.x series) can fail Gradle's bundled Bouncy Castle parser with `Could not read PGP
   secret key`, even though the key is valid OpenPGP; this was hit and reproduced while verifying
   this setup. If you see that error, regenerate/export the key with a stable GnuPG 2.4.x release,
   or use an existing key exported that way.
5. In CI, set the four properties above as secrets and inject them as `ORG_GRADLE_PROJECT_*` env
   vars (Gradle auto-maps `ORG_GRADLE_PROJECT_foo` → project property `foo`, so no gradle.properties
   file is needed in CI at all):
   ```yaml
   env:
     ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
     ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
     ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_IN_MEMORY_KEY }}
     ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_IN_MEMORY_KEY_PASSWORD }}
   ```
   This repo's CI does not currently have a publish job: wiring one up is a future step (see
   below), out of scope until the first manual publish is done and verified.

## Publishing a release

1. Bump `version` in `runtime/build.gradle.kts` and `gradle-plugin/build.gradle.kts` (they're not
   read from a single shared property, keep them in sync manually) to a real release version,
   never `-SNAPSHOT` (Central's release repository rejects snapshots outright; snapshots would
   need the separate Central snapshots repo, which this project doesn't use).
2. Run the whole verification loop locally first, same as CI, plus a mavenLocal check:
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 21)
   ./gradlew build -x lint
   ./gradlew publishToMavenLocal   # sanity check the POMs before touching Central
   ```
3. **The actual publish command**, with credentials/signing key available as `ORG_GRADLE_PROJECT_*`
   env vars or in `~/.gradle/gradle.properties`:
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 21)
   ./gradlew :gradle-plugin:publishToMavenCentral :hotreload-runtime:publishToMavenCentral
   ```
   This uploads a signed bundle to the Central Portal but does **not** auto-release it (this repo
   deliberately doesn't call `publishToMavenCentral(automaticRelease = true)`); see the manual
   step below.
4. Tag and push the release the same way as previous releases (`git tag vX.Y.Z && git push --tags`),
   and cut a GitHub Release with the CLI zip, matching the existing `v0.1.0`/`v0.1.1` pattern.

## Post-publish (manual, on central.sonatype.com)

1. Go to [central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments).
2. Find the new deployment (one per module, or combined depending on how the upload batched them),
   verify the contents look right (POM metadata, jars, signatures all present).
3. Click **"Publish"** to release it out of the pending/validating state. This is the manual gate
   `automaticRelease = false` leaves in place: nothing reaches Central until this button is
   clicked, even though the artifacts already uploaded successfully.
4. **Propagation delay**: after clicking Publish, expect roughly 15-30 minutes before the artifact
   resolves from `mavenCentral()` in a consumer build, and longer (up to a few hours) before it's
   indexed and shows up in [search.maven.org](https://search.maven.org) search results. Direct
   coordinate resolution (`dev.thuat:hotreload-runtime:X.Y.Z`) is usually faster than search
   indexing.
5. Once confirmed resolvable, bump the version references in `README.md`'s quickstart, cut a
   matching git tag, and attach a fresh `cli.zip` to the GitHub release so the tag, the release
   asset, and the Central version all agree: a consumer following the README should never be told
   to apply plugin `X.Y.Z` while the only downloadable CLI is older.

   `jitpack.yml` is kept so previously published tags (`v0.1.0`, `v0.1.1`) keep resolving for
   anyone who pinned them; new releases go to Central and the README no longer mentions JitPack.

## Building and verifying the `cli.zip` release asset

**Why this section exists**: the `v0.1.6` release shipped a `cli.zip` built from a state before
the version-handshake commits, even though the tag itself contained them. `./gradlew :cli:distZip`
was run without cleaning first, so Gradle served a stale artifact, and nothing checked the
uploaded asset against the release it was attached to before or after upload. Only the Central
runtime AAR was checked, and the CLI zip was wrongly assumed to match it. `scripts/verify-
release-asset.sh` (see that file for the exact checks it runs) exists so this never again depends
on a human remembering to eyeball a zip. Follow this exact order, every release:

1. **Build from a clean checkout of the tag.** Not the working tree you happened to publish from,
   and not an incremental build that might be serving a stale output:
   ```bash
   git clone https://github.com/nthuat/android-hot-reload.git /tmp/release-build
   cd /tmp/release-build
   git checkout vX.Y.Z
   ./gradlew :cli:distZip
   ```
   The result is `cli/build/distributions/cli.zip`, always named exactly that and rooted at
   `cli/` regardless of the project's `version` (pinned in `cli/build.gradle.kts`; see that file's
   `distributions` block for why both `install.sh` and `InstallCliTask` depend on this).
2. **Verify the local zip before uploading anything:**
   ```bash
   scripts/verify-release-asset.sh X.Y.Z /tmp/release-build/cli/build/distributions/cli.zip
   ```
   A failure here means the build itself is wrong, fix it before it ever touches GitHub.
3. **Upload** `cli.zip` as a release asset on the GitHub release for `vX.Y.Z`.
4. **Verify the published asset**, downloaded fresh rather than trusting the upload:
   ```bash
   scripts/verify-release-asset.sh X.Y.Z
   ```
   A failure here (that step 2 didn't already catch) means the wrong file got uploaded, or the
   upload was interrupted/corrupted, fix the release asset and re-run this step until it passes.

Step 2 and step 4 run the same checks against two different files for a reason: step 2 catches a
bad build before it ever reaches a user, step 4 catches a mistake made in the upload itself (wrong
file picked, partial upload, uploaded to the wrong tag). Skipping either one is exactly how the
`v0.1.6` incident happened.

**CI**: no job currently runs the verifier automatically on release publish. Doing so would need a
`release: { types: [published] }` trigger with a step that downloads the just-published asset and
runs `scripts/verify-release-asset.sh` against it, gating nothing (the asset is already public by
the time that event fires) but at least paging someone loudly on a mismatch. Left as a manual step
for now since wiring an automatic gate around an already-published asset is a bigger design
question (what happens on failure? un-publish? open an issue?) than this fix covers, run the two
commands above by hand until that's decided.

## Gradle Plugin Portal

Publishing `dev.thuat.hotreload` to the [Gradle Plugin Portal](https://plugins.gradle.org) is what
lets consumers resolve the plugin by id with **no repository configuration at all** — the Portal is
in Gradle's default `pluginManagement` repositories, Maven Central is not. `:gradle-plugin` applies
`com.gradle.plugin-publish` for this. Central publishing is unchanged and still runs through
`mavenPublishing`; the two coexist, since `:runtime` is a plain library only Central can serve.

### One-time setup

1. Create an account at [plugins.gradle.org](https://plugins.gradle.org/user/register), then
   generate an API key under **your profile → API Keys**.
2. Put the pair in `~/.gradle/gradle.properties` (never commit them):
   ```properties
   gradle.publish.key=<key>
   gradle.publish.secret=<secret>
   ```
3. The plugin id's namespace has to be verified before the first publish. `dev.thuat` is already
   proven for Central via the `thuat.dev` DNS TXT record, but the Portal runs its **own** namespace
   check — expect a one-time manual approval on the first submission.

### Publishing

Run after the Central publish for the same version, so the two never disagree about what `X.Y.Z`
contains:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :gradle-plugin:publishPlugins
```

Add `--validate-only` first if you want the Portal to check the submission without publishing it.
Unlike Central, the Portal has no manual "Publish" gate: a successful run is live, and versions are
immutable, so a mistake needs a new version rather than a fix in place.

### After publishing

Once it resolves from the Portal, the README quickstart can drop the `pluginManagement`
repositories block entirely — that block exists only because Central isn't a default plugin
repository. Leave it documented for anyone pinning an older version.
