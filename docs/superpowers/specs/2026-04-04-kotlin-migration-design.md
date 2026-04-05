# P3-D: Strategic Kotlin Migration Design

## Scope
~30 high-value Java files in `com.hippo.ehviewer`. Excludes `com.hippo.*` framework (230 files) and large UI scenes (3 files). Cluster-based: all files in a cluster convert together, `@JvmStatic` removed within cluster.

## 7 Clusters (dependency order)

| # | Cluster | ~Files | Key Benefit |
|---|---|---|---|
| 1 | DAO entities + data classes | 15 | `data class`, `@Parcelize`, null safety |
| 2 | Client utilities | 4 | Remove dead code, scope functions |
| 3 | Download core | 3 | Full coroutine async, eliminate `blockingDb` |
| 4 | Gallery providers | 3 | Structured concurrency, coroutine page loading |
| 5 | Preferences/Settings | 4 | Complete settings Kotlin migration |
| 6 | Application layer | 2 | Clean init, coroutine lifecycle |
| 7 | Small UI helpers | 3 | Scope functions, null safety |

## Rules
- Pure refactor per cluster — no behavior changes mixed with language changes
- Each cluster is one commit (or one commit per sub-batch for large clusters)
- Build + test after every commit
- `@JvmStatic` removed between Kotlin files in same cluster; kept for remaining Java callers
- Room `@Entity`/`@Dao` annotations preserved exactly
- `Parcelable` → `@Parcelize` where possible
- No `data class` for Room entities with mutable fields (Room requires mutable setters)
