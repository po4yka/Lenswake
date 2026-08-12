# GitHub Releases runbook

Lenswake publishes a signed, minified APK for personal sideloading. GitHub Actions is the build and
provenance boundary; a release does not claim that the APK passed the separate physical Pixel 8 Pro
acceptance matrix.

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
4. Create and push an annotated `v<versionName>` tag only after explicit release authorization:

   ```bash
   git tag -a v0.1.0 -m "Lenswake 0.1.0"
   git push origin v0.1.0
   ```

The tag starts `.github/workflows/release.yml`. Validation, the full host gate, API-35/36 AOSP ATD,
and the available API-37 Google APIs preview image run before the protected `release` environment
exposes signing secrets. Approve the `publish` deployment only after those jobs pass and the
tag/commit are correct.

The protected job builds the signed minified APK, verifies package/version/certificate/permissions,
generates `SHA256SUMS.txt`, creates provenance, uploads both assets to a draft, verifies the uploaded
bytes, and only then publishes the release. A rerun resumes the same draft. A published release is
accepted only when its downloaded APK and checksum file are byte-identical to the rebuilt files.

## Verify a published release

Download into a fresh directory and verify both digest and GitHub provenance:

```bash
gh release download v0.1.0 --repo po4yka/Lenswake
sha256sum --check SHA256SUMS.txt
gh attestation verify Lenswake-0.1.0.apk --repo po4yka/Lenswake
scripts/ci/verify-release-apk.sh Lenswake-0.1.0.apk 0.1.0 1
```

Record the release tag, commit, APK SHA-256, certificate fingerprint, workflow run, and verification
result. Physical acceptance must separately follow
[`docs/testing/PHYSICAL_PIXEL.md`](../testing/PHYSICAL_PIXEL.md) using that exact APK.

## One-time post-first-release hardening

After the first release is successfully published and independently downloaded and verified:

1. enable immutable releases for the repository;
2. add an active tag ruleset matching `v*` that blocks update and deletion;
3. verify the published release and its tag are immutable;
4. retain the offline keystore backup and recovery instructions independently of GitHub.

Do not enable these irreversible protections before the first release has demonstrated the complete
publish and recovery path.
