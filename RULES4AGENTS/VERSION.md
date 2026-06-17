# Version Management Rules

## ⚠️ CRITICAL: Do NOT touch version files unless explicitly asked

The `VERSION_NAME` and `VERSION_CODE` files are **off-limits** by default. Do not modify, read-suggest, or touch them unless the user explicitly asks for a version bump. If in doubt, do nothing.

## VERSION_NAME

- **Format**: `YYYY.MM.DD.HH.mm` (UTC time), each segment zero-padded to 2 digits
- **Regex**: `^\d{4}\.\d{2}\.\d{2}\.\d{2}\.\d{2}$`
- **Example**: `2026.06.13.05.22` (UTC 2026-06-13 05:22)
- CI validates this format strictly at build start

## VERSION_CODE

- Plain integer, **+1** on each release (Google-style version code)

## Release Tag

- Must start with **`v`**: `v{YYYY.MM.DD.HH.MM}`
- **Example**: `v2026.06.13.05.22`
- CI triggers on `startsWith(github.ref, 'refs/tags/v')` for: native build, APK upload, GitHub Release
- Release APK is renamed to `DreamHub-{VERSION}-arm64-v8a-release.apk` (drops `v` prefix)

## ⚠️ Bump = Tag (Mandatory)

**Every version bump MUST create and push a corresponding `v*` tag.** Bumping without tagging is incomplete and will be blocked. This ensures CI always triggers native build, APK upload, and GitHub Release when version changes.

## Release Procedure

1. Get current UTC time, format as `YYYY.MM.DD.HH.mm` (zero-pad each segment to 2 digits)
2. Update `VERSION_NAME` with this value
3. Increment `VERSION_CODE` by 1
4. `git commit` both files with message format: `release: bump version_name to v{YYYY.MM.DD.HH.MM} (version_code {N})`
5. **Create annotated tag `v{VERSION_NAME}`** (required, do not skip)
6. `git push` the commit **and** the tag (`git push --follow-tags` or push tag explicitly)
