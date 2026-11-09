---
name: deja-release
description: Releases a new version of Déjà. First it bumps the version through a draft pull request. Once that's merged, it builds the signed APK, tags the release and drafts the GitHub release. Use only when someone asks to release a version.
disable-model-invocation: true
argument-hint: "[version, such as 0.0.3]"
---

# Release Déjà

The version comes with the request, such as `0.0.3`. If none was given, ask for it. It's called X.Y.Z below.

A release has two phases, because the version bump reaches `main` through a pull request:

- **Prepare**, when `versionName` in `app/build.gradle.kts` isn't X.Y.Z yet.
- **Publish**, when `main` already has `versionName = "X.Y.Z"` and there's no `vX.Y.Z` tag yet.

If both the version and the tag already exist, X.Y.Z has been released. Say so and stop.

Never read `keystore.properties` or any keystore file. Only check that `keystore.properties` exists.

Don't push, open a pull request, push a tag or create a release until the person has seen what will go out and said yes.

## Prepare

1. Check that:
   - you're on `main`, the working tree is clean, and `main` matches `origin/main` after `git fetch origin`;
   - X.Y.Z is higher than the current `versionName`;
   - there's no `vX.Y.Z` tag, locally or in `git ls-remote --tags origin`.

   If anything fails, say what, and stop.
2. Create the branch `chore/release-X.Y.Z`.
3. In `app/build.gradle.kts`, add 1 to `versionCode` and set `versionName = "X.Y.Z"`.
4. In `README.md`, change the download link to `releases/latest/download/Deja-X.Y.Z.apk`. The link names the file, so it breaks if it isn't updated.
5. Run `./gradlew check`. If it fails, stop and report the failure.
6. Commit with the message `chore(release): X.Y.Z`. The body says: "Bump the version to X.Y.Z (versionCode N) and point the README download link at the new APK."
7. Show the diff. Once the person agrees, push the branch and open a **draft** pull request titled `chore(release): X.Y.Z`. In its body, list what changed since the last tag (`git log v<previous>..HEAD --oneline`).
8. Stop. Tell the person to merge the pull request, then run this skill again with the same version.

## Publish

1. Check that:
   - you're on `main`, the working tree is clean, and `main` matches `origin/main` after `git fetch origin`;
   - `versionName` is X.Y.Z;
   - `keystore.properties` exists (`test -f keystore.properties`).

   If anything fails, say what, and stop.
2. Run `./gradlew check`, then `./gradlew :app:assembleRelease`.
3. The signed APK is `app/build/outputs/apk/release/app-release.apk`. If only `app-release-unsigned.apk` is there, the build wasn't signed: stop. Confirm the signature with `apksigner verify`, from the newest folder in the Android SDK's `build-tools`.
4. Copy the APK to `app/build/outputs/apk/release/Deja-X.Y.Z.apk`, and get its SHA-256 with `shasum -a 256`.
5. Draft the release notes in `app/build/release-notes-X.Y.Z.md`. First read the previous release for format and tone (`gh release view v<previous> --json body --jq .body`), and the commits since it (`git log v<previous>..HEAD`). Write for the people who use the app, not for developers, in short, plain sentences. Use these parts, in this order, and leave out any that would be empty:
   - One opening sentence on what this version brings. If the previous version can be upgraded in place, say so, and that history stays.
   - If any commit since the previous release has a `BREAKING CHANGE:` footer, follow the opening sentence with `## Before you install`, saying plainly what people must do first and what they will lose.
   - `## Fixed`, `## New` and `## Improved`, each item with a bold lead-in where that helps.
   - `## Install`: "Download `Deja-X.Y.Z.apk` below. It needs **Android 15 or newer**. Play Protect may warn you, because the app isn't from the Play Store."
   - `## Known limits`: carry over the previous list, and update anything this release changes. Keep the line that links to the disclaimer.
   - `## Verify`: "SHA-256: `<hash>`".
6. Show the notes and the hash. Once the person agrees:

   ```sh
   git tag -a vX.Y.Z -m "Déjà X.Y.Z"
   git push origin vX.Y.Z
   gh release create vX.Y.Z app/build/outputs/apk/release/Deja-X.Y.Z.apk \
     --verify-tag --draft --title "Déjà X.Y.Z" --notes-file app/build/release-notes-X.Y.Z.md
   ```

7. Give the link to the draft release. The person publishes it. Until they do, the README's download link points at a file that doesn't exist yet, so tell them to publish soon.
