# Version Management Rules

## ⚠️ CRITICAL: Do NOT touch version files unless explicitly asked

The `VERSION_NAME` and `VERSION_CODE` files are **off-limits** by default. Do not modify, read-suggest, or touch them unless the user explicitly asks for a version bump. If in doubt, do nothing.

## VERSION_NAME

- **Default format** (pre-release): `YYYY.MM.DD.HH.mm-rc` (UTC time), each segment zero-padded to 2 digits, suffixed with `-rc`
- **Formal release format**: `YYYY.MM.DD.HH.mm` (same but without `-rc` suffix)
- **Regex**: `^\d{4}\.\d{2}\.\d{2}\.\d{2}\.\d{2}(-rc)?$`
- **Examples**:
  - `2026.06.13.05.22-rc` — default pre-release (UTC 2026-06-13 05:22)
  - `2026.06.13.05.22` — formal release (only when user explicitly requests it)
- CI validates this format strictly at build start

## VERSION_CODE

- Plain integer, **+1** on each release (Google-style version code)

## `-rc` Suffix Rules

| User instruction | VERSION_NAME | Tag | Release type |
|------------------|-------------|-----|-------------|
| "version bump" / "bump" / "release" (default) | `2026.06.24.11.28-rc` | `v2026.06.24.11.28-rc` | **pre-release** |
| "formal release" / "正式版本" / "stable" (explicit) | `2026.06.24.11.28` | `v2026.06.24.11.28` | formal release |

- **Default behavior**: always append `-rc` to version name and tag.
- **Only omit `-rc`** when the user explicitly uses keywords like "formal", "正式", "stable", "official", or "release candidate removed".

## Release Tag

- Must start with **`v`**
- **Pre-release**: `v{YYYY.MM.DD.HH.MM}-rc`
- **Formal**: `v{YYYY.MM.DD.HH.MM}`
- **Examples**: `v2026.06.13.05.22-rc`, `v2026.06.13.05.22`
- CI triggers on `startsWith(github.ref, 'refs/tags/v')` for: native build, APK upload, GitHub Release
- Release APK is renamed to `DreamHub-{VERSION}-arm64-v8a-release.apk` (drops `v` prefix)

## ⚠️ Bump = Tag (Mandatory)

**Every version bump MUST create and push a corresponding `v*` tag.** Bumping without tagging is incomplete and will be blocked. This ensures CI always triggers native build, APK upload, and GitHub Release when version changes.

## Release Procedure

1. Get current UTC time, format as `YYYY.MM.DD.HH.mm` (zero-pad each segment to 2 digits)
2. **Decide suffix**: append `-rc` by default; omit `-rc` only if user explicitly requests formal release
3. Update `VERSION_NAME` with this value (e.g. `2026.06.24.11.28-rc`)
4. Increment `VERSION_CODE` by 1
5. `git commit` both files with message format: `release: bump version_name to v{YYYY.MM.DD.HH.MM}[-rc] (version_code {N})`
6. **Create annotated tag `v{VERSION_NAME}`** (required, do not skip)
7. `git push` the commit **and** the tag (`git push --follow-tags` or push tag explicitly)
