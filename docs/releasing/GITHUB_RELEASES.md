# GitHub Releases runbook

Lenswake publishes a signed, minified APK for personal sideloading only after that exact artifact
passes the complete physical gate on both Pixel 7 and Pixel 8 Pro. GitHub Actions separates candidate
construction from publication so no tag-triggered run can publish an untested rebuild.

## Permanent signing identity

The release keystore is deliberately outside the repository. Its passwords are stored in the
maintainer's password manager, and the protected GitHub `release` environment contains:

- `LENSWAKE_RELEASE_KEYSTORE_BASE64`;
- `LENSWAKE_RELEASE_STORE_PASSWORD`;
- `LENSWAKE_RELEASE_KEY_ALIAS`;
- `LENSWAKE_RELEASE_KEY_PASSWORD`.

The public SHA-256 certificate fingerprint is committed in
[`release-signing-certificate.sha256`](../../release-signing-certificate.sha256). Losing the private
key prevents compatible upgrades of installed APKs. Before the first production tag, copy the
password-protected keystore to an offline medium and verify that the copy has the same SHA-256 hash
as the primary keystore. A second file on the same computer is only a recovery copy, not an offline
backup.

Never commit, print, upload as a workflow artifact, or place the keystore in a project-local Gradle
property file. To rotate it, treat the new certificate as a distribution-breaking identity change.

## Prepare a release

1. Update `version.properties`. `versionName` must be SemVer and `versionCode` must be greater than
   the previous release's value.
2. Run the release identity and build contracts:

   ```bash
   scripts/ci/test-release-contract.sh
   scripts/ci/test-build-contract.sh
   ./gradlew check assembleDebug \
     :app:assembleDebugAndroidTest \
     :data:assembleDebugAndroidTest
   ```

3. Confirm the intended commit is on `main` and all hosted CI/security checks are green.
4. Create and push an annotated `v<versionName>` tag only after explicit candidate-build
   authorization:

   ```bash
   git tag -a v0.1.0 -m "Lenswake 0.1.0"
   git push origin v0.1.0
   ```

The tag starts the candidate phase of `.github/workflows/release.yml`. Validation, the full host
gate, API-35/36 AOSP ATD, and the available API-37 Google APIs preview image run before the protected
`release` environment exposes signing secrets. Approval builds one signed APK, verifies it, creates
provenance, records `RELEASE-CANDIDATE.txt`, and uploads `release-candidate-<tag>` for 30 days. The
tag-triggered run has no publication job or `contents: write` permission.

## Accept the exact candidate

Download the candidate from the successful tag-triggered run, retaining its run ID:

```bash
gh run download "$CANDIDATE_RUN_ID" --repo po4yka/Lenswake \
  --name "release-candidate-$RELEASE_TAG" --dir release-candidate
cd release-candidate
sha256sum --check SHA256SUMS.txt
gh attestation verify "Lenswake-${RELEASE_TAG#v}.apk" --repo po4yka/Lenswake
```

Install that exact APK on Pixel 7 and Pixel 8 Pro. Follow
[`docs/testing/PHYSICAL_PIXEL.md`](../testing/PHYSICAL_PIXEL.md), including installed-artifact
identity, every offered capture combination, reliability scenarios, failure paths, saved media, and
cleanup. Produce a complete acceptance record for each device, calculate each record's SHA-256, and
store it at a durable HTTPS URL. Neither an older APK nor a rebuild qualifies.

After both records pass, manually run the **Release** workflow from the exact tag ref and provide:

- candidate workflow run ID and tag;
- the accepted APK SHA-256 from `SHA256SUMS.txt`;
- the durable URL and SHA-256 of the Pixel 7 record;
- the durable URL and SHA-256 of the Pixel 8 Pro record.

The publication run checks that its selected ref is the tag, the candidate came from a successful
tag-triggered `release.yml` run at the same commit, the downloaded APK/manifests match the accepted
SHA-256, and both evidence identities are present. The protected `release` environment then requires
a separate final approval. Only this run has `contents: write`; it publishes the unchanged candidate,
`SHA256SUMS.txt`, and `PHYSICAL-ACCEPTANCE.txt`. A rerun resumes an identical draft but refuses any
published asset drift.

## Verify a published release

Download into a fresh directory and verify both digest and GitHub provenance:

```bash
gh release download v0.1.0 --repo po4yka/Lenswake
shasum -a 256 --check SHA256SUMS.txt
gh attestation verify Lenswake-0.1.0.apk --repo po4yka/Lenswake
scripts/ci/verify-release-apk.sh Lenswake-0.1.0.apk 0.1.0 1
test -s PHYSICAL-ACCEPTANCE.txt
```

Record the release tag, commit, APK SHA-256, certificate fingerprint, workflow run, and verification
result. Verify that `PHYSICAL-ACCEPTANCE.txt` names the downloaded APK digest, candidate run, and both
content-addressed device records.

## One-time post-first-release hardening

After the first release is successfully published and independently downloaded and verified:

1. enable immutable releases for the repository;
2. add an active tag ruleset matching `v*` that blocks update and deletion;
3. verify the published release and its tag are immutable;
4. retain the offline keystore backup and recovery instructions independently of GitHub.

Do not enable these irreversible protections before the first release has demonstrated the complete
publish and recovery path.
