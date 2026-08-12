#!/usr/bin/env bash
set -euo pipefail

version_file="${1:-version.properties}"

if [[ ! -f "$version_file" ]]; then
  echo "Missing version file: $version_file" >&2
  exit 1
fi

version_name="$(sed -n 's/^versionName=//p' "$version_file")"
version_code="$(sed -n 's/^versionCode=//p' "$version_file")"

if [[ ! "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "Invalid versionName in $version_file: $version_name" >&2
  exit 1
fi
if [[ ! "$version_code" =~ ^[1-9][0-9]*$ ]]; then
  echo "Invalid versionCode in $version_file: $version_code" >&2
  exit 1
fi

printf 'VERSION_NAME=%s\nVERSION_CODE=%s\n' "$version_name" "$version_code"
