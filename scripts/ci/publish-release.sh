#!/usr/bin/env bash
set -euo pipefail

tag="${1:?Usage: publish-release.sh TAG APK CHECKSUMS PHYSICAL_ACCEPTANCE}"
apk="${2:?Usage: publish-release.sh TAG APK CHECKSUMS PHYSICAL_ACCEPTANCE}"
checksums="${3:?Usage: publish-release.sh TAG APK CHECKSUMS PHYSICAL_ACCEPTANCE}"
physical_acceptance="${4:?Usage: publish-release.sh TAG APK CHECKSUMS PHYSICAL_ACCEPTANCE}"
repository="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
head_sha="${GITHUB_SHA:?GITHUB_SHA is required}"

release_json="$(gh api "repos/$repository/releases/tags/$tag" 2>/dev/null || true)"
if [[ -n "$release_json" ]]; then
  release_tag="$(jq -r '.tag_name' <<<"$release_json")"
  [[ "$release_tag" == "$tag" ]] || {
    echo "Existing release tag mismatch: $release_tag" >&2
    exit 1
  }

  if [[ "$(jq -r '.draft' <<<"$release_json")" == "false" ]]; then
    temp_dir="$(mktemp -d)"
    trap 'rm -rf "$temp_dir"' EXIT
    gh release download "$tag" --repo "$repository" --dir "$temp_dir" \
      --pattern "$(basename "$apk")" --pattern "$(basename "$checksums")" \
      --pattern "$(basename "$physical_acceptance")"
    cmp --silent "$apk" "$temp_dir/$(basename "$apk")" || {
      echo "Published APK differs from the locally verified artifact" >&2
      exit 1
    }
    cmp --silent "$checksums" "$temp_dir/$(basename "$checksums")" || {
      echo "Published checksum file differs from the local checksum file" >&2
      exit 1
    }
    cmp --silent "$physical_acceptance" "$temp_dir/$(basename "$physical_acceptance")" || {
      echo "Published physical acceptance differs from the verified gate record" >&2
      exit 1
    }
    echo "Published release $tag already contains identical assets"
    exit 0
  fi
else
  gh release create "$tag" --repo "$repository" --target "$head_sha" \
    --verify-tag --draft --generate-notes --title "$tag"
fi

gh release upload "$tag" "$apk" "$checksums" "$physical_acceptance" \
  --repo "$repository" --clobber

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
gh release download "$tag" --repo "$repository" --dir "$temp_dir" \
  --pattern "$(basename "$apk")" --pattern "$(basename "$checksums")" \
  --pattern "$(basename "$physical_acceptance")"
cmp --silent "$apk" "$temp_dir/$(basename "$apk")"
cmp --silent "$checksums" "$temp_dir/$(basename "$checksums")"
cmp --silent "$physical_acceptance" "$temp_dir/$(basename "$physical_acceptance")"

release_id="$(gh api "repos/$repository/releases/tags/$tag" --jq '.id')"
gh api --method PATCH "repos/$repository/releases/$release_id" -f draft=false >/dev/null
echo "Published release $tag from $head_sha"
