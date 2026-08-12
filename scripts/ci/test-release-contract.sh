#!/usr/bin/env bash
set -euo pipefail

source_root="$(pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT

new_repository() {
  local directory="$1"
  mkdir -p "$directory/scripts/ci"
  cp "$source_root/scripts/ci/read-version.sh" "$directory/scripts/ci/"
  cp "$source_root/scripts/ci/semver.sh" "$directory/scripts/ci/"
  cp "$source_root/scripts/ci/validate-release-tag.sh" "$directory/scripts/ci/"
  git -C "$directory" init --initial-branch=main --quiet
  git -C "$directory" config user.name "Lenswake CI"
  git -C "$directory" config user.email "ci@lenswake.invalid"
}

commit_version() {
  local directory="$1"
  local version_name="$2"
  local version_code="$3"
  printf 'versionName=%s\nversionCode=%s\n' "$version_name" "$version_code" >"$directory/version.properties"
  git -C "$directory" add version.properties scripts
  git -C "$directory" commit --quiet -m "Version $version_name"
}

expect_failure() {
  local expected="$1"
  shift
  local output
  if output="$("$@" 2>&1)"; then
    echo "Expected command to fail: $*" >&2
    exit 1
  fi
  grep -Fq "$expected" <<<"$output" || {
    echo "Failure did not contain '$expected':" >&2
    echo "$output" >&2
    exit 1
  }
}

success_repo="$test_root/success"
new_repository "$success_repo"
commit_version "$success_repo" 1.2.3-rc.1+build.7 1
git -C "$success_repo" tag v1.2.3-rc.1+build.7
git -C "$success_repo" update-ref refs/remotes/origin/main HEAD
(cd "$success_repo" && scripts/ci/validate-release-tag.sh v1.2.3-rc.1+build.7 origin/main >/dev/null)

expect_failure "Release tag must be v<SemVer>" \
  bash -c "cd '$success_repo' && scripts/ci/validate-release-tag.sh release-0.1.0 origin/main"
expect_failure "Release tag must be v<SemVer>" \
  bash -c "cd '$success_repo' && scripts/ci/validate-release-tag.sh v01.2.3 origin/main"
expect_failure "Release tag must be v<SemVer>" \
  bash -c "cd '$success_repo' && scripts/ci/validate-release-tag.sh v1.2.3-01 origin/main"
expect_failure "Release tag must be v<SemVer>" \
  bash -c "cd '$success_repo' && scripts/ci/validate-release-tag.sh v1.2.3- origin/main"
expect_failure "does not match versionName" \
  bash -c "cd '$success_repo' && scripts/ci/validate-release-tag.sh v0.2.0 origin/main"

nonincrement_repo="$test_root/nonincrement"
new_repository "$nonincrement_repo"
commit_version "$nonincrement_repo" 0.1.0 1
git -C "$nonincrement_repo" tag v0.1.0
commit_version "$nonincrement_repo" 0.2.0 1
git -C "$nonincrement_repo" tag v0.2.0
git -C "$nonincrement_repo" update-ref refs/remotes/origin/main HEAD
expect_failure "versionCode 1 must be greater than 1" \
  bash -c "cd '$nonincrement_repo' && scripts/ci/validate-release-tag.sh v0.2.0 origin/main"

outside_main_repo="$test_root/outside-main"
new_repository "$outside_main_repo"
commit_version "$outside_main_repo" 0.1.0 1
git -C "$outside_main_repo" tag v0.1.0
git -C "$outside_main_repo" update-ref refs/remotes/origin/main HEAD
git -C "$outside_main_repo" switch --quiet --create release-candidate
commit_version "$outside_main_repo" 0.2.0 2
git -C "$outside_main_repo" tag v0.2.0
expect_failure "is not reachable from origin/main" \
  bash -c "cd '$outside_main_repo' && scripts/ci/validate-release-tag.sh v0.2.0 origin/main"

echo "Release identity contract tests passed"
