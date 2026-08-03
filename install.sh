#!/bin/sh
# Installs the android-hot-reload CLI without needing a Gradle project at all:
#   curl -fsSL https://raw.githubusercontent.com/nthuat/android-hot-reload/main/install.sh | sh
#
# Resolves the latest GitHub release (or a pinned one via HOTRELOAD_VERSION), downloads its
# cli.zip, unpacks it to ~/.local/share/hotreload/<version>/, and symlinks
# ~/.local/bin/hotreload at it. Safe to re-run: re-running the same version is a no-op re-link,
# re-running with a newer HOTRELOAD_VERSION (or a newer "latest") just adds that version's dir
# and repoints the symlink — older versions are left on disk rather than pruned (ponytail: no
# GC of old versions here, add one if disk usage ever becomes a real complaint).
#
# POSIX sh only: no arrays, no `local`, no [[ ]], no pipefail — must run under plain `sh` on both
# macOS (whose /bin/sh is not bash-compatible) and Linux.
set -eu

REPO="nthuat/android-hot-reload"
INSTALL_ROOT="$HOME/.local/share/hotreload"
BIN_DIR="$HOME/.local/bin"
SYMLINK="$BIN_DIR/hotreload"

log() { printf '%s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl is required but not found on PATH."

# --- resolve which release to install -----------------------------------------------------
# HOTRELOAD_VERSION pins a specific release, e.g. `HOTRELOAD_VERSION=v0.1.2 curl ... | sh`.
# Accepts either "v0.1.2" or "0.1.2" — the `v` prefix is normalized on rather than required,
# since it's an easy detail to get wrong when typing it by hand.
if [ -n "${HOTRELOAD_VERSION:-}" ]; then
    case "$HOTRELOAD_VERSION" in
        v*) tag="$HOTRELOAD_VERSION" ;;
        *) tag="v$HOTRELOAD_VERSION" ;;
    esac
    log "install.sh: using pinned release $tag"
else
    log "install.sh: resolving latest release..."
    latest_json=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest") \
        || die "could not reach GitHub's releases API. Check your network, or pin a version with HOTRELOAD_VERSION=vX.Y.Z."
    tag=$(printf '%s' "$latest_json" | grep '"tag_name"' | head -1 | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')
    [ -n "$tag" ] || die "could not parse a release tag from the GitHub API response."
    log "install.sh: latest release is $tag"
fi
version="${tag#v}"
install_dir="$INSTALL_ROOT/$version"
download_url="https://github.com/$REPO/releases/download/$tag/cli.zip"

# --- skip the download entirely if this exact version is already installed ----------------
if [ -x "$install_dir/bin/cli" ]; then
    log "install.sh: $tag already installed at $install_dir, skipping download."
else
    tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/hotreload-install.XXXXXX")
    trap 'rm -rf "$tmp_dir"' EXIT
    zip_path="$tmp_dir/cli.zip"

    log "install.sh: downloading $download_url"
    curl -fsSL -o "$zip_path" "$download_url" \
        || die "download failed. Check that release $tag exists at https://github.com/$REPO/releases."

    # Verify the download: non-empty, a structurally valid zip, and contains the entry we need.
    # No checksum file is published alongside releases today, so this is the strongest check
    # available without one — add a checksum comparison here if that ever changes.
    [ -s "$zip_path" ] || die "downloaded cli.zip is empty."
    command -v unzip >/dev/null 2>&1 || die "unzip is required but not found on PATH."
    unzip -tq "$zip_path" >/dev/null 2>&1 || die "downloaded cli.zip failed its integrity check."
    unzip -l "$zip_path" | grep -q 'cli/bin/cli$' \
        || die "downloaded cli.zip doesn't contain the expected cli/bin/cli entry."

    unzip -q "$zip_path" -d "$tmp_dir/extracted"
    rm -rf "$install_dir"
    mkdir -p "$INSTALL_ROOT"
    mv "$tmp_dir/extracted/cli" "$install_dir"
    chmod +x "$install_dir/bin/cli"
    log "install.sh: installed to $install_dir"
fi

# --- symlink -------------------------------------------------------------------------------
mkdir -p "$BIN_DIR"
ln -sf "$install_dir/bin/cli" "$SYMLINK"
log "install.sh: linked $SYMLINK -> $install_dir/bin/cli"

# --- PATH check ----------------------------------------------------------------------------
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *)
        warn "$BIN_DIR is not on your PATH."
        warn "Add this line to your shell profile (~/.zshrc, ~/.bashrc, or ~/.profile), then restart your shell:"
        warn "    export PATH=\"$BIN_DIR:\$PATH\""
        ;;
esac

# --- JDK check -------------------------------------------------------------------------------
# hotreload needs JDK 17+ (same requirement as the Gradle plugin/AGP it drives); catching a
# missing/too-old JDK here beats the user discovering it as an opaque failure on first run.
if command -v java >/dev/null 2>&1; then
    java_version_line=$(java -version 2>&1 | head -1)
    # Handles both the old "1.8.0_xxx" scheme and the modern "17.0.1"/"21" scheme.
    major=$(printf '%s' "$java_version_line" | sed -E 's/.*"([0-9]+)(\.[0-9]+)?.*".*/\1/')
    case "$major" in
        1) major=$(printf '%s' "$java_version_line" | sed -E 's/.*"1\.([0-9]+).*/\1/') ;;
    esac
    if ! [ "$major" -ge 17 ] 2>/dev/null; then
        warn "detected $java_version_line — hotreload needs JDK 17+. Install one and set JAVA_HOME, or put a JDK 17+ 'java' first on PATH."
    fi
else
    warn "no 'java' found on PATH — hotreload needs JDK 17+ on PATH or JAVA_HOME."
fi

log ""
log "Done. Run:"
log "  $SYMLINK run --project /path/to/your/project --package your.app.package"
