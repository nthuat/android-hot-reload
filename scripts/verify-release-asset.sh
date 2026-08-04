#!/bin/sh
# Verifies a cli.zip release asset actually matches the version it claims to be, instead of
# trusting that a plain `distZip` run produced what ends up on GitHub.
#
# Why this exists: the v0.1.6 GitHub release shipped a cli.zip built BEFORE the version-handshake
# commits, even though the tag contained them. Nothing checked the uploaded asset against the
# release it was attached to -- only the published Maven Central runtime AAR was checked, and the
# CLI zip was wrongly assumed to match. See docs/releasing.md for the corrected process this
# script is now part of.
#
# Usage:
#   scripts/verify-release-asset.sh <version>                  # downloads the published cli.zip
#   scripts/verify-release-asset.sh <version> <path/to/cli.zip>  # checks a local zip instead
#
# <version> has no "v" prefix, e.g. "0.1.6" (matches cli/build.gradle.kts's `version`). Run this
# TWICE per release: once against the local zip before uploading (catches a bad build before it
# ever reaches GitHub), once against the published URL after (catches an upload mistake).
#
# POSIX sh only, same constraint as install.sh: no arrays, no `local`, no [[ ]], no pipefail.
set -eu

REPO="nthuat/android-hot-reload"

log() { printf 'verify-release-asset.sh: %s\n' "$*"; }
die() { printf 'verify-release-asset.sh: FAIL: %s\n' "$*" >&2; exit 1; }

[ "$#" -ge 1 ] || die "usage: $0 <version> [local-zip-path]"
version="$1"
tag="v$version"

command -v unzip >/dev/null 2>&1 || die "unzip is required but not found on PATH."

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/verify-release-asset.XXXXXX")
trap 'rm -rf "$work_dir"' EXIT

if [ "$#" -ge 2 ]; then
    zip_path="$2"
    [ -f "$zip_path" ] || die "local zip not found: $zip_path"
    log "checking local zip $zip_path against version $version"
else
    command -v curl >/dev/null 2>&1 || die "curl is required but not found on PATH."
    zip_path="$work_dir/cli.zip"
    download_url="https://github.com/$REPO/releases/download/$tag/cli.zip"
    log "downloading $download_url"
    curl -fsSL -o "$zip_path" "$download_url" \
        || die "download failed: $download_url (does release $tag exist, with a cli.zip asset?)"
fi

# --- basic sanity: non-empty, structurally valid zip -----------------------------------------
[ -s "$zip_path" ] || die "$zip_path is empty."
unzip -tq "$zip_path" >/dev/null 2>&1 || die "$zip_path is not a valid zip archive."

listing=$(unzip -l "$zip_path")

# --- invariant 1: exactly one cli/bin/cli entry (the root dir install.sh and InstallCliTask both
# hard-code -- see cli/build.gradle.kts's distributions block for how that's pinned) ------------
bin_count=$(printf '%s\n' "$listing" | grep -c ' cli/bin/cli$' || true)
[ "$bin_count" -eq 1 ] || die "expected exactly one 'cli/bin/cli' entry, found $bin_count."

# --- invariant 2: both agent .so files present (Main.kt's resolveAgentSoDir needs them per-ABI) -
for abi in arm64-v8a x86_64; do
    printf '%s\n' "$listing" | grep -q " cli/agent/$abi/libhotreload_agent.so\$" \
        || die "missing cli/agent/$abi/libhotreload_agent.so"
done

# --- invariant 3a: the bundled jar's own file name is versioned (a bare "cli.jar" is exactly the
# stale-build symptom the v0.1.6 incident was caught by, by hand) -------------------------------
jar_entry=$(printf '%s\n' "$listing" | grep -oE 'cli/lib/cli-[^ ]*\.jar$' | head -1)
[ -n "$jar_entry" ] || die "no cli/lib/cli-<version>.jar entry found -- found a bare cli.jar instead? That's the v0.1.6 stale-build symptom: this project's version property renames the jar, so its absence means the archive predates that commit."
jar_version=$(printf '%s' "$jar_entry" | sed -E 's#cli/lib/cli-(.*)\.jar#\1#')
[ "$jar_version" = "$version" ] || die "bundled jar is cli-$jar_version.jar, expected cli-$version.jar."

# --- invariant 3b: the jar's own baked-in hotreload-cli-version.txt agrees too (belt-and-
# suspenders over 3a: catches a jar that was merely renamed without rebuilding) -----------------
unzip -p "$zip_path" "$jar_entry" > "$work_dir/cli.jar" 2>/dev/null \
    || die "could not extract $jar_entry from $zip_path."
jar_baked_version=$(unzip -p "$work_dir/cli.jar" hotreload-cli-version.txt 2>/dev/null | tr -d '[:space:]') \
    || die "$jar_entry has no hotreload-cli-version.txt on its classpath -- broken CLI build."
[ "$jar_baked_version" = "$version" ] || die "hotreload-cli-version.txt inside $jar_entry says '$jar_baked_version', expected '$version'."

log "PASS: $zip_path matches version $version (cli/bin/cli present once, both agent .so present, jar name and baked-in version agree)."
