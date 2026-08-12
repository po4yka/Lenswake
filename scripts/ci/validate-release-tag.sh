#!/usr/bin/env bash
set -euo pipefail

tag="${1:-${GITHUB_REF_NAME:-}}"
main_ref="${2:-origin/main}"

if [[ -z "$tag" || ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "Release tag must be v<SemVer>: ${tag:-<empty>}" >&2
  exit 1
fi

eval "$(scripts/ci/read-version.sh)"
if [[ "$tag" != "v$VERSION_NAME" ]]; then
  echo "Tag $tag does not match versionName $VERSION_NAME" >&2
  exit 1
fi

tag_commit="$(git rev-list -n 1 "$tag")"
head_commit="$(git rev-parse HEAD)"
if [[ "$tag_commit" != "$head_commit" ]]; then
  echo "Checked-out commit $head_commit does not match $tag at $tag_commit" >&2
  exit 1
fi
if ! git merge-base --is-ancestor "$tag_commit" "$main_ref"; then
  echo "Tag $tag is not reachable from $main_ref" >&2
  exit 1
fi

previous_tag="$(
  git tag --merged "$main_ref" --list 'v*' --sort=-version:refname |
    while IFS= read -r candidate; do
      [[ "$candidate" == "$tag" ]] && continue
      [[ "$candidate" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || continue
      printf '%s\n' "$candidate"
      break
    done
)"

if [[ -n "$previous_tag" ]]; then
  previous_properties="$(git show "$previous_tag:version.properties" 2>/dev/null)" || {
    echo "$previous_tag does not contain version.properties" >&2
    exit 1
  }
  previous_code="$(sed -n 's/^versionCode=//p' <<<"$previous_properties")"
  if [[ ! "$previous_code" =~ ^[1-9][0-9]*$ ]]; then
    echo "$previous_tag has an invalid versionCode: $previous_code" >&2
    exit 1
  fi
  if (( VERSION_CODE <= previous_code )); then
    echo "versionCode $VERSION_CODE must be greater than $previous_code from $previous_tag" >&2
    exit 1
  fi
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    printf 'version_name=%s\n' "$VERSION_NAME"
    printf 'version_code=%s\n' "$VERSION_CODE"
  } >>"$GITHUB_OUTPUT"
fi

printf 'Validated %s at %s (versionCode=%s)\n' "$tag" "$head_commit" "$VERSION_CODE"
