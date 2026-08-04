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
   matching git tag, and attach a fresh `cli.zip` (`./gradlew :cli:distZip`) to the GitHub release
   so the tag, the release asset, and the Central version all agree: a consumer following the
   README should never be told to apply plugin `X.Y.Z` while the only downloadable CLI is older.

   `jitpack.yml` is kept so previously published tags (`v0.1.0`, `v0.1.1`) keep resolving for
   anyone who pinned them; new releases go to Central and the README no longer mentions JitPack.

## Future step: Gradle Plugin Portal

Publishing `dev.thuat.hotreload` to the [Gradle Plugin Portal](https://plugins.gradle.org) (so
consumers can drop the `mavenCentral()`/`gradlePluginPortal()` plugin-marker dance entirely and
just use `plugins { id("dev.thuat.hotreload") version "X.Y.Z" }` with zero extra repository setup)
is explicitly **out of scope** for this pass: it needs a separate Plugin Portal account and API
key, unrelated to the Central Portal credentials above. Revisit once Central publishing is
confirmed working end to end.
