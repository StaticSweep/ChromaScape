#!/usr/bin/env bash

# FEDORA-01 packaging library. Sourced by the package builder, the shipped
# installer/uninstaller, the packaging regressions, and the FEDORA-02
# certification harness. It performs no work on source: every function is
# explicit and read-only unless its name says otherwise.

FEDORAPKG_SCHEMA_VERSION=1
FEDORAPKG_MANIFEST="PACKAGE-MANIFEST.json"
FEDORAPKG_NAME_DEFAULT="chromascape"

fedorapkg_emit() {
    local level="$1" code="$2" message="$3"
    printf 'FEDORAPKG level=%s code=%s message=%s\n' "$level" "$code" "$message"
}

fedorapkg_fail() {
    fedorapkg_emit ERROR "$1" "$2"
    return 2
}

# Reads the declared application version from the Gradle build, stripping the
# development "-SNAPSHOT" suffix so a package never advertises a moving target.
fedorapkg_project_version() {
    local project_dir="$1" raw
    raw="$(awk -F'"' '/^[[:space:]]*version[[:space:]]*=[[:space:]]*"/ {print $2; exit}' \
        "$project_dir/build.gradle.kts" 2>/dev/null)"
    if [[ -z "$raw" ]]; then
        fedorapkg_fail VERSION_UNKNOWN "could not read a version literal from build.gradle.kts"
        return 2
    fi
    printf '%s' "${raw%-SNAPSHOT}"
}

fedorapkg_tarball_name() {
    local name="$1" version="$2"
    printf '%s-%s-linux-x86_64.tar.gz' "$name" "$version"
}

fedorapkg_rpm_name() {
    local name="$1" version="$2"
    printf '%s-%s-1.x86_64.rpm' "$name" "$version"
}

# Normalises a version for RPM: RPM cannot carry a leading "v" and forbids "-".
fedorapkg_rpm_version() {
    local version="${1#v}"
    printf '%s' "${version//-/.}"
}

# Lists the members a valid Linux tarball must contain, one per line. The base
# distribution is deliberately RemoteInput-free; the native pair is opt-in.
fedorapkg_required_members() {
    printf '%s\n' \
        "application.jar" \
        "launcher" \
        "licenses/ChromaScape-LICENSE" \
        "tools/install.sh" \
        "tools/uninstall.sh" \
        "tools/lib/packaging.sh" \
        "PACKAGE-MANIFEST.json"
}

fedorapkg_native_members() {
    printf '%s\n' \
        "native/linux-x86_64/libRemoteInput.so" \
        "native/linux-x86_64/remoteinput-manifest.json"
}

# Lists the tar members as relative paths without a trailing slash for
# directories. Portable across GNU tar (the only supported Fedora tar).
fedorapkg_tar_members() {
    local archive="$1"
    tar -tzf "$archive" 2>/dev/null | sed 's:/$::' | sed '/^$/d'
}

# Verifies the tarball's top-level layout and required members. Prints a PASS or
# ERROR record for each checked member and returns non-zero on the first class of
# failure. The tarball is expected to unpack to a single top-level directory.
fedorapkg_verify_tarball() {
    local archive="$1" expected_top="$2" errors=0 member top tops members_file
    if [[ ! -r "$archive" ]]; then
        fedorapkg_fail TARBALL_MISSING "archive is absent or unreadable: $archive"
        return 2
    fi
    # Listing a multi-hundred-megabyte archive is expensive; read the member
    # inventory exactly once and query it in memory rather than once per member.
    members_file="$(mktemp "${TMPDIR:-/tmp}/fedorapkg-members.XXXXXX")"
    fedorapkg_tar_members "$archive" > "$members_file"
    tops="$(awk -F/ 'NF>1 {print $1}' "$members_file" | sort -u)"
    if [[ "$(printf '%s\n' "$tops" | grep -c .)" != "1" || "$tops" != "$expected_top" ]]; then
        fedorapkg_fail TARBALL_LAYOUT "archive must unpack to exactly '$expected_top'; got: ${tops//$'\n'/,}"
        errors=$((errors + 1))
    fi
    while IFS= read -r member; do
        if grep -qxF "$expected_top/$member" "$members_file"; then
            fedorapkg_emit PASS TARBALL_MEMBER "present: $member"
        else
            fedorapkg_emit ERROR TARBALL_MEMBER_MISSING "missing: $member"
            errors=$((errors + 1))
        fi
    done < <(fedorapkg_required_members)
    rm -f "$members_file"
    if (( errors > 0 )); then
        return 2
    fi
    return 0
}

# Reads one string field from the packaged PACKAGE-MANIFEST.json without a JSON
# parser, so the installer works on a host with no Python and no jq.
fedorapkg_manifest_field() {
    local manifest="$1" field="$2"
    sed -n 's/.*"'"$field"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$manifest" | head -n 1
}

fedorapkg_manifest_bool() {
    local manifest="$1" field="$2"
    sed -n 's/.*"'"$field"'"[[:space:]]*:[[:space:]]*\(true\|false\).*/\1/p' "$manifest" | head -n 1
}

# sha256 of a file, printing only the hex digest.
fedorapkg_sha256() {
    local file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print $1}'
    else
        fedorapkg_fail SHA256_UNAVAILABLE "sha256sum is not available"
        return 2
    fi
}

# The install root holds immutable releases plus a single mutable "current"
# symlink, which is what makes upgrade, rollback and uninstall atomic and
# reversible without ever overwriting a running release.
fedorapkg_release_dir() {
    local prefix="$1" version="$2"
    printf '%s/releases/%s' "$prefix" "$version"
}

fedorapkg_state_file() {
    printf '%s/install.json' "$1"
}

fedorapkg_bin_shim() {
    local bin_dir="$1" name="$2"
    printf '%s/%s' "$bin_dir" "$name"
}

# Generates the tiny bin shim. It derives the install prefix from its own
# location and exec's the mutable "current" release launcher, so an upgrade or
# rollback takes effect for the next start without editing the shim.
fedorapkg_write_shim() {
    local shim="$1" prefix="$2"
    cat > "$shim" <<EOF
#!/usr/bin/env sh
set -eu
exec "$prefix/current/launcher" "\$@"
EOF
    chmod 0755 "$shim"
}

# Installs an extracted release directory into the prefix under a versioned
# release dir, then atomically flips "current" to it. The previous release is
# retained for rollback. All writes are within the prefix (no sudo, no /usr).
fedorapkg_install_release() {
    local prefix="$1" bin_dir="$2" name="$3" version="$4" source_dir="$5"
    local release target current_tmp previous="" state_tmp files_sha
    release="$(fedorapkg_release_dir "$prefix" "$version")"
    mkdir -p "$prefix/releases" "$bin_dir"
    if [[ -e "$prefix/current" && -L "$prefix/current" ]]; then
        previous="$(readlink "$prefix/current")"
        previous="${previous##*/}"
    fi
    if [[ -d "$release" ]]; then
        rm -rf "$release"
    fi
    mkdir -p "$release"
    # cp -a preserves the executable launcher mode and is bounded to the
    # extracted release tree, never the invoking working directory.
    cp -a "$source_dir/." "$release/"
    target="$release"
    current_tmp="$prefix/.current.$$"
    rm -f "$current_tmp"
    ln -s "releases/$version" "$current_tmp"
    mv -Tf "$current_tmp" "$prefix/current"
    fedorapkg_write_shim "$(fedorapkg_bin_shim "$bin_dir" "$name")" "$prefix"
    files_sha="$(fedorapkg_sha256 "$release/application.jar")"
    state_tmp="$(fedorapkg_state_file "$prefix").tmp.$$"
    cat > "$state_tmp" <<EOF
{
  "schemaVersion": $FEDORAPKG_SCHEMA_VERSION,
  "name": "$name",
  "version": "$version",
  "prefix": "$prefix",
  "binDir": "$bin_dir",
  "releaseDir": "$release",
  "previousVersion": "$previous",
  "applicationJarSha256": "$files_sha"
}
EOF
    mv -f "$state_tmp" "$(fedorapkg_state_file "$prefix")"
    fedorapkg_emit PASS INSTALL_RELEASE "installed $name $version at $release"
}

# Flips "current" to an already-installed release. Returns non-zero when the
# requested version was never installed, so a rollback can never invent one.
fedorapkg_rollback() {
    local prefix="$1" version="$2" release current_tmp
    release="$(fedorapkg_release_dir "$prefix" "$version")"
    if [[ ! -d "$release" ]]; then
        fedorapkg_fail ROLLBACK_UNKNOWN "release $version is not installed under $prefix"
        return 2
    fi
    current_tmp="$prefix/.current.$$"
    rm -f "$current_tmp"
    ln -s "releases/$version" "$current_tmp"
    mv -Tf "$current_tmp" "$prefix/current"
    fedorapkg_emit PASS ROLLBACK "current now points at release $version"
}

# Removes the shim, the current pointer, every installed release, the state file
# and the prefix. Refuses to touch anything outside its own recorded layout, so
# it can never delete a user's unrelated files.
fedorapkg_uninstall() {
    local prefix="$1" bin_dir="$2" name="$3" shim
    shim="$(fedorapkg_bin_shim "$bin_dir" "$name")"
    if [[ -n "$prefix" && "$prefix" != "/" && -d "$prefix" ]]; then
        rm -rf "$prefix/releases" "$prefix/current" "$(fedorapkg_state_file "$prefix")"
        find "$prefix" -mindepth 1 -maxdepth 1 -name '.current.*' -exec rm -f {} +
        rmdir "$prefix" 2>/dev/null || true
    fi
    if [[ -n "$shim" && -f "$shim" ]] && grep -q "$prefix/current/launcher" "$shim" 2>/dev/null; then
        rm -f "$shim"
    fi
}

# Reports every path owned by an install so the certification harness can prove
# nothing was left behind. Prints one absolute path per line. Missing entries are
# omitted; callers compare the before/after sets.
fedorapkg_install_paths() {
    local prefix="$1" bin_dir="$2" name="$3" shim
    shim="$(fedorapkg_bin_shim "$bin_dir" "$name")"
    [[ -e "$prefix" ]] && printf '%s\n' "$prefix"
    [[ -e "$shim" ]] && printf '%s\n' "$shim"
}
