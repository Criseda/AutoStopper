# SpotBugs baseline

SpotBugs runs during the Maven `verify` phase with maximum analysis effort and a medium-confidence threshold. A clean result is the baseline: any new unsuppressed medium- or high-confidence finding fails `verify`.

Run the same gate locally and in CI with the pinned Maven Wrapper:

```text
./mvnw verify
```

On Windows, use `mvnw.cmd verify`.

## Refreshed audit

The issue #20 audit recorded eight findings on an earlier codebase, including platform-default encoding. A fresh scan of `master` with the configured SpotBugs toolchain found twelve medium-confidence findings:

| Findings | Category | Resolution |
| ---: | --- | --- |
| 6 | `CT_CONSTRUCTOR_THROW` in `DockerManager`, `ProcessCommandRunner`, and `AutoStopperExecutor` constructor overloads | Made each implementation class `final`, eliminating the finalizer-attack surface while retaining constructor validation. |
| 2 | `EI_EXPOSE_REP` from test-only `AutoStopperPlugin` accessors | Removed the mutable `config` and `activityTracker` accessors and their tests. |
| 4 | `EI_EXPOSE_REP2` on adapter fields receiving shared lifecycle collaborators | Narrowly excluded by bug pattern, concrete class, and field in `config/spotbugs/exclude.xml`. These adapters intentionally share coordinator-owned state and do not expose the references. |

No default-encoding finding remains. Process output, the Minecraft status protocol, packaged descriptor verification, and relevant test byte conversions all use explicit UTF-8.

## Suppression policy

The exclusion file is not a general warning baseline. Each entry must identify one bug pattern and one field or method on one concrete class, with a written reason. Package-wide, class-wide, and bug-category-wide exclusions are not permitted.
