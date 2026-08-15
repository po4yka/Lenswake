#!/usr/bin/env bash
set -euo pipefail

candidate_manifest="${1:?Usage: verify-physical-release-gate.sh CANDIDATE APK CHECKSUMS TAG COMMIT RUN_ID APK_SHA256 P7_URL P7_SHA256 P7_PROFILE P8P_URL P8P_SHA256 P8P_PROFILE OUTPUT}"
apk="${2:?Usage: verify-physical-release-gate.sh CANDIDATE APK CHECKSUMS TAG COMMIT RUN_ID APK_SHA256 P7_URL P7_SHA256 P7_PROFILE P8P_URL P8P_SHA256 P8P_PROFILE OUTPUT}"
checksums="${3:?Usage: verify-physical-release-gate.sh CANDIDATE APK CHECKSUMS TAG COMMIT RUN_ID APK_SHA256 P7_URL P7_SHA256 P7_PROFILE P8P_URL P8P_SHA256 P8P_PROFILE OUTPUT}"
expected_tag="${4:?Missing accepted tag}"
expected_commit="${5:?Missing accepted commit}"
candidate_run_id="${6:?Missing candidate run ID}"
expected_apk_sha256="${7:?Missing accepted APK SHA-256}"
pixel_7_evidence_url="${8:?Missing Pixel 7 evidence URL}"
pixel_7_evidence_sha256="${9:?Missing Pixel 7 evidence SHA-256}"
pixel_7_profile_fingerprint="${10:?Missing Pixel 7 accepted profile fingerprint}"
pixel_8_pro_evidence_url="${11:?Missing Pixel 8 Pro evidence URL}"
pixel_8_pro_evidence_sha256="${12:?Missing Pixel 8 Pro evidence SHA-256}"
pixel_8_pro_profile_fingerprint="${13:?Missing Pixel 8 Pro accepted profile fingerprint}"
output="${14:?Missing physical acceptance output path}"

require_sha256() {
  local label="$1"
  local value="$2"
  [[ "$value" =~ ^[0-9a-f]{64}$ ]] || {
    echo "$label must be 64 lowercase hexadecimal characters" >&2
    exit 1
  }
}

require_evidence_url() {
  local label="$1"
  local value="$2"
  [[ "$value" =~ ^https://[^[:space:]]+$ ]] || {
    echo "$label must be a durable HTTPS URL without whitespace" >&2
    exit 1
  }
}

manifest_value() {
  local key="$1"
  local matches
  matches="$(sed -n "s/^${key}=//p" "$candidate_manifest")"
  [[ -n "$matches" && "$matches" != *$'\n'* ]] || {
    echo "Candidate manifest must contain exactly one $key value" >&2
    exit 1
  }
  printf '%s' "$matches"
}

for required_file in "$candidate_manifest" "$apk" "$checksums"; do
  [[ -f "$required_file" ]] || {
    echo "Required release candidate file is missing: $required_file" >&2
    exit 1
  }
done

[[ "$candidate_run_id" =~ ^[1-9][0-9]*$ ]] || {
  echo "Candidate run ID must be a positive integer" >&2
  exit 1
}
require_sha256 "Accepted APK SHA-256" "$expected_apk_sha256"
require_sha256 "Pixel 7 evidence SHA-256" "$pixel_7_evidence_sha256"
require_sha256 "Pixel 7 accepted profile fingerprint" "$pixel_7_profile_fingerprint"
require_sha256 "Pixel 8 Pro evidence SHA-256" "$pixel_8_pro_evidence_sha256"
require_sha256 "Pixel 8 Pro accepted profile fingerprint" "$pixel_8_pro_profile_fingerprint"
require_evidence_url "Pixel 7 evidence URL" "$pixel_7_evidence_url"
require_evidence_url "Pixel 8 Pro evidence URL" "$pixel_8_pro_evidence_url"
[[ "$pixel_7_evidence_url" != "$pixel_8_pro_evidence_url" ]] || {
  echo "Pixel 7 and Pixel 8 Pro must have separate evidence URLs" >&2
  exit 1
}
[[ "$pixel_7_evidence_sha256" != "$pixel_8_pro_evidence_sha256" ]] || {
  echo "Pixel 7 and Pixel 8 Pro must have separate evidence records" >&2
  exit 1
}

[[ "$(manifest_value schemaVersion)" == "1" ]] || {
  echo "Unsupported release candidate manifest schema" >&2
  exit 1
}
[[ "$(manifest_value tag)" == "$expected_tag" ]] || {
  echo "Candidate tag does not match the physically accepted tag" >&2
  exit 1
}
[[ "$(manifest_value commit)" == "$expected_commit" ]] || {
  echo "Candidate commit does not match the physically accepted commit" >&2
  exit 1
}
[[ "$(manifest_value apkFilename)" == "$(basename "$apk")" ]] || {
  echo "Candidate APK filename does not match the downloaded artifact" >&2
  exit 1
}
[[ "$(manifest_value apkSha256)" == "$expected_apk_sha256" ]] || {
  echo "Candidate manifest APK SHA-256 does not match physical acceptance" >&2
  exit 1
}

actual_apk_sha256="$(sha256sum "$apk" | awk '{print $1}')"
[[ "$actual_apk_sha256" == "$expected_apk_sha256" ]] || {
  echo "Downloaded APK SHA-256 does not match physical acceptance" >&2
  exit 1
}
expected_checksum_line="$expected_apk_sha256  $(basename "$apk")"
[[ "$(cat "$checksums")" == "$expected_checksum_line" ]] || {
  echo "SHA256SUMS.txt does not name only the physically accepted APK" >&2
  exit 1
}

{
  printf 'schemaVersion=2\n'
  printf 'tag=%s\n' "$expected_tag"
  printf 'commit=%s\n' "$expected_commit"
  printf 'candidateRunId=%s\n' "$candidate_run_id"
  printf 'apkFilename=%s\n' "$(basename "$apk")"
  printf 'apkSha256=%s\n' "$expected_apk_sha256"
  printf 'pixel7EvidenceUrl=%s\n' "$pixel_7_evidence_url"
  printf 'pixel7EvidenceSha256=%s\n' "$pixel_7_evidence_sha256"
  printf 'pixel7ProfileFingerprint=%s\n' "$pixel_7_profile_fingerprint"
  printf 'pixel8ProEvidenceUrl=%s\n' "$pixel_8_pro_evidence_url"
  printf 'pixel8ProEvidenceSha256=%s\n' "$pixel_8_pro_evidence_sha256"
  printf 'pixel8ProProfileFingerprint=%s\n' "$pixel_8_pro_profile_fingerprint"
} >"$output"

echo "Verified physical release gate for $expected_tag at $expected_commit"
