# ADR 0011 — Coordinates and packaging

**Status:** accepted
**Date:** 2026-08-13

## Decision

| Field | Value |
|---|---|
| Maven group | `site.asm0dey` |
| Artifact | `kotlin-poet-dsl` |
| Base package | `site.asm0dey.poetdsl` |
| License | Apache-2.0 (assumed; change before the first release if wrong) |
| Module layout | single Gradle module, Kotlin JVM |

`api(kotlinpoet)` — KotlinPoet types (`TypeName`, `FunSpec`, `ClassName`, `MemberName`)
appear throughout the public API, so it must be `api`, not `implementation`.
`implementation(kotlin("reflect"))` for callable-reference resolution.

`explicitApi()` is on and binary-compatibility-validator locks the surface in
`api/kotlin-poet-dsl.api`.

## Consequences

- Every source file, test and the BCV dump carries `site.asm0dey.poetdsl`; settling it now
  avoids a repo-wide rename later.
- Publishing to Maven Central under `site.asm0dey` requires ownership proof for the
  `asm0dey.site` domain (DNS TXT record). Signing and the Central repository block are not
  part of the initial build — added when there is an account to publish to.
