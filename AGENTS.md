# Aura Launcher Development Requirements

These rules apply to all work in this repository.

## Java Nullability

- Every class declared in a newly added Java source file must use JetBrains Annotations
  `@NotNullByDefault`.
- Any nullable type, field, parameter, return value, local variable, or generic type argument written
  or modified must be explicitly annotated with `@Nullable`.
- Nullability must never be implicit in Java code being written or modified.

## Java Immutability

- Immutable arrays and collections must use `@Unmodifiable` or `@UnmodifiableView` as appropriate.
- Arrays use type-use syntax such as `String @Unmodifiable []`.

## Java Documentation

- Every class, field, and method written or modified must have accurate documentation.
- Documentation uses `///` Markdown-style Javadoc comments.
- Add concise implementation comments only where non-obvious logic materially benefits from them.

## Aura Next Identity

- This repository is the future Aura Launcher product line.
- Every embedded launcher version must end in `-next` exactly once, including versions supplied by
  `BUILD_VERSION` in CI.
- Java and packaged launcher filenames must use the same suffixed version.
- The distributable Shadow JAR must be named `Aura-Launcher-<version>.jar`.
- Do not remove, bypass, or conditionally suppress the suffix during ordinary feature work. It may
  only be removed by an explicit stable-line promotion on the target stable branch.
- When version or packaging logic changes, verify the Gradle project version, Shadow JAR filename,
  and JAR `Implementation-Version` before committing.

## Compatibility Boundaries

- Keep `org.jackhuang.hmcl`, upstream copyrights, GPL terms, and protocol identifiers when changing
  them would break source, binary, data, or plugin compatibility.
- Aura `.npl` packages must use plugin manifest schema v5. Store index schema versions are separate.
- Aura live data must not share HMCL CE's data directory. Migration may copy only explicitly
  allowlisted settings and must not import plugins or plugin security state.
