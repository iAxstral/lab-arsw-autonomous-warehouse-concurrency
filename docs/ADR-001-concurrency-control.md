# ADR-001: Concurrency control for warehouse shared state

## Context
Multiple `WarehouseRobot` threads concurrently read and write shared mutable state
(`PackageQueue`, `DeliveryRegistry`, `WarehouseStatistics`) without coordination in the
starter project, producing race conditions: duplicated/lost parcels, duplicated delivery
positions, and lost statistics increments (see `REPORT.md`, Parts I and II).

## Decision
Protect each shared-state class with Java's intrinsic monitor (`synchronized` on
the instance methods that read or write the shared fields), scoping the lock to the
minimum method body needed to preserve each class's invariant — not a single global lock
across the whole simulation.

## Alternatives considered
- **Single global lock** (one lock shared by all three classes): rejected — would
  serialize unrelated operations (e.g., taking a parcel vs. reading statistics) that
  have no invariant in common, hurting throughput for no correctness benefit.
- **`AtomicInteger`/`AtomicLong` for individual counters**: considered for
  `WarehouseStatistics`, but rejected because `processedParcels` and
  `totalProcessingMillis` are tied by the same invariant and must update together;
  independent atomics could expose an inconsistent intermediate state to a reader.
- **`java.util.concurrent.locks.ReentrantLock`**: functionally equivalent to
  `synchronized` for this use case; not adopted because none of the critical regions
  need `tryLock()`, timeouts, or multiple `Condition`s — `synchronized` is simpler and
  sufficient.

## Quality attributes affected
- **Correctness/reliability**: race conditions eliminated (verified: 0/100 anomalous
  runs across multiple robot/parcel configurations, see `REPORT.md` Part VI).
- **Performance/throughput**: locks are scoped to short, non-blocking method bodies;
  the dominant per-parcel cost (`Thread.sleep` simulating processing) happens outside
  any lock, so robots still work in parallel.
- **Maintainability**: each lock's scope maps directly to one class's invariant,
  making it clear what each `synchronized` block is protecting and why.

## Evidence
See `REPORT.md`, Parts I (baseline anomalies) and VI (verification results):
`RaceConditionProbe` converges to `0/100` anomalous runs after these changes, across
8/100, 16/250 and 32/500 robot/parcel configurations.

## Consequences
- Every future change to these classes must keep the read-modify-write sequence for
  the protected invariant inside the same `synchronized` block; adding a new field
  that participates in an existing invariant requires bringing it under the same lock,
  not a separate one.
- If this system were split across multiple JVM instances (see `REPORT.md`, Part VII),
  these locks would no longer be sufficient — they only provide mutual exclusion
  within a single JVM's memory space.

## Risks
- If a future maintainer adds logic inside a `synchronized` method that blocks (I/O,
  waiting on another lock), it would hold the monitor much longer than intended and
  hurt throughput — the current design assumes critical regions stay short and
  non-blocking.
- Intrinsic locks (`synchronized`) don't support timeouts; a future requirement for
  "give up after waiting N ms" would require migrating to `ReentrantLock`.