# Changelog

## [Unreleased] — 1.0.3

### Added
- serve-d Language Server (LSP) integration — preference page, JSON-RPC transport, text synchronization, diagnostics bridge (publishDiagnostics → IMarker), and feature routing for code completion, hover documentation, and go-to-definition (2026-06-28)
- Live connection status indicator on the Language Server preference page (2026-06-28)
- Auto-detected compiler and stdlib paths shown in Language Server settings page (2026-07-02)
- dub.sdl manifest file parsing support (2026-06-26)
- dub.recipe manifest filename support with --nodeps fallback (2026-06-30)
- Named argument parsing per DIP1040 (DMD 2.103+) (2026-06-26)
- LDC detection for Ubuntu/Debian package layout (/usr/lib/ldc/\<arch\>/include/d/) (2026-06-22)
- GitHub Actions CI/CD pipeline deploying p2 update site automatically to GitHub Pages (2026-06-28)

### Changed
- Project import paths and stdlib paths are pushed from DDT to serve-d on connect and on model update (2026-06-30)
- Subpackage directories are detected and skipped for dub describe to avoid resolution errors (2026-06-28)
- CDT version constraint changed to greaterOrEqual for Eclipse 2026-06 compatibility (2026-06-22)
- JVM version check updated for Java 9+ format; minimum JVM raised to Java 11 (2026-06-22)
- Eclipse platform updated from Neon to 2025-03; Tycho upgraded from 0.26 to 4.0.13 (2026-06-22, 2026-07-02)
- All bundle execution environments aligned to JavaSE-17 or JavaSE-21 as required by dependencies (2026-07-02)
- Maven plugin versions updated (compiler, surefire, jarsigner, build-helper, download) (2026-06-26)

### Fixed
- False "Running D build" error dialog when a nested Eclipse workspace project exists inside the DDT project tree (2026-07-02)
- SDL-format dub projects stuck permanently with "unresolved dub manifest" error (2026-06-30)
- NPE in DeeLanguageServerHandler caused by component init ordering (2026-06-30)
- D builder incorrectly triggered on non-D projects or projects with uninitialized bundle model (2026-06-30)
- Main bundle source paths now pushed to serve-d even when dependency resolution fails (2026-06-30)
- Null image descriptor crash for LSP completion proposals (2026-06-28)
- serve-d URI format corrected to file:/// (three slashes, RFC 8089) (2026-06-28)
- serve-d crash detection with proper ready-state cleanup (2026-06-28)
- serve-d stderr now logged to Eclipse error log for diagnostics (2026-06-28)

### Security
- gson upgraded 2.2 → 2.12.1 (CVE-2022-25647) (2026-06-26)
- junit upgraded 4.11 → 4.13.2 (CVE-2020-15250) (2026-06-26)
