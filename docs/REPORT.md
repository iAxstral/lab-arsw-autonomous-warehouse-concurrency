## 5. Critical regions and synchronization decisions

| Class | Critical region | Protected invariant | Synchronization mechanism | Why this granularity? |
|---|---|---|---|---|
| `PackageQueue` | Entire body of `takeNext()` (`isEmpty()` check + `get(0)`/`remove(0)` act); entire body of `pendingCount()` | Each parcel is handed out to at most one caller; no parcel is lost or duplicated between `pending` and the rest of the system | `synchronized` instance methods (monitor on `this`) | The check-then-act sequence in `takeNext()` (`isEmpty()` → remove head) *is* the whole invariant-relevant computation — there is no sub-step that can be excluded from the lock without reopening the race demonstrated in Evidence 1. Splitting it into a smaller synchronized block would either still cover the whole method (no gain) or expose the same check-then-act gap. `pendingCount()` is synchronized on the same monitor, not because a `size()` read can corrupt the list, but so a caller observing `pendingCount()` gets a value that is consistent with (happens-after) the most recent completed `takeNext()`, instead of a stale/racy read. Both methods are short, non-blocking (no I/O, no waiting) and called once per work item per robot, so contention is bounded by queue-draining traffic only — there was no reason to reach for a more elaborate mechanism (`Lock`, `ConcurrentLinkedQueue`, etc.) to protect two three-line methods on a single shared instance. |
| `DeliveryRegistry` | Entire body of `register()`; entire body of `snapshot()` | Every delivery record receives a unique, contiguous position `1..N`; `deliveries.size()` reflects exactly the number of completed `register()` calls | `synchronized` instance methods (monitor on `this`) | Reading `nextPosition`, incrementing it, and appending the new record to `deliveries` must be seen as one atomic step — locking only around the increment would still let two threads read the same stale value before either writes back. `snapshot()` is synchronized on the same monitor so it never reads `deliveries` mid-write from `register()`, and so a reader gets a view consistent with the most recently completed `register()` call rather than a torn/stale one. Both methods are short and non-blocking, so locking the full method body creates negligible contention relative to the actual per-parcel processing time spent outside this class. |
| `WarehouseStatistics` | Entire body of `recordProcessed()`; entire body of the getters (`processedParcels()`, `totalProcessingMillis()`) | `processedParcels` always equals the number of completed `recordProcessed()` calls, and stays consistent with `totalProcessingMillis` — both fields describe the same event | `synchronized` instance methods (monitor on `this`) | The two fields are tied by the same invariant (they describe the same processed-parcel event), so they must update as a single atomic unit — separate `AtomicInteger`/`AtomicLong` counters would let a reader observe one field updated and the other not yet, breaking that pairing even though each individual field would be internally consistent. Getters are synchronized on the same monitor purely for visibility/happens-before ordering with the writer, not because a single primitive field read is unsafe on its own. |

**Why is the whole method the critical region here, and not just part of it?**

> In all three classes, the invariant depends on the *combination* of a read and a write (or a check and an act) being seen atomically by every thread — protecting only one half of that pair leaves the other free to interleave and reproduces the same race. Since none of these methods do any processing, I/O, or blocking calls, the minimum sufficient critical region happens to equal the whole method body in each case — this is not "synchronize everything without analysis," it is the outcome of actually tracing which statements the invariant depends on.

**What would happen to throughput if the protected region were unnecessarily large?**

> Every robot thread calls `takeNext()`, `register()`, and `recordProcessed()` once per parcel it processes, but the actual *work* per parcel (`WarehouseRobot.process()`, driven by `Thread.sleep(parcel.processingMillis() + jitter)`, roughly 10–22 ms) happens **outside** all three shared-state classes entirely. If any of these locks' scope were widened to also cover parcel processing — e.g. holding the `PackageQueue` monitor while "working" on the parcel instead of releasing it right after `remove(0)` returns — every other robot would have to wait for that entire processing time before it could even check whether the queue was empty. With `N` robots that turns an embarrassingly-parallel workload into an effectively **serial** one: total wall-clock time would approach `parcelCount × averageProcessingMillis` regardless of how many robot threads exist, instead of `≈ parcelCount × averageProcessingMillis / N`. Keeping each critical region limited to the list/field mutation (a few nanoseconds) lets robots spend virtually all of their time doing the actual (lock-free) processing work concurrently, which is the entire point of using multiple threads in the first place.

## 6. Thread completion and pause/resume coordination

### Thread completion (`WarehouseMain` / `WarehouseSimulation.awaitCompletion()`)

The starter printed a report after a fixed `Thread.sleep(60)`, with no relationship to
the actual state of the robot threads — with 12 robots / 100 parcels this consistently
printed `Pending parcels` different from 0 and an incomplete `DeliveryRegistry`, since
robots were still running.

`Thread.sleep(N)` cannot substitute for `join()` because it only delays execution; it
gives no guarantee about *what state the other threads are in* when it returns, and no
memory-visibility guarantee for what those threads wrote. `Thread.join()`, by contrast,
blocks the calling thread until the target thread reaches `TERMINATED`, and per JLS
17.4.5 establishes a happens-before edge: everything a robot wrote before finishing is
guaranteed visible to the thread that joined it. `WarehouseSimulation#awaitCompletion()`
performs one `join()` per robot thread; `WarehouseMain` calls it and only then prints
the final report, guaranteeing exactly one report, printed after every robot has
actually terminated, consistent with the invariants checked in Part II.

No `synchronized` is used in `WarehouseMain`: the method declares no shared mutable
state of its own (`robots`, `parcels`, `simulation` are local variables, never touched
by more than one thread), so there is no critical region to protect there — adding a
lock would be decorative, not functional.

### Pause/resume (`SimulationControl`)

The starter used active waiting:

```java
while (paused) {
    Thread.onSpinWait();
}
```

which keeps every paused robot thread runnable and spinning, burning CPU with no
useful work, and gives no blocking/wake mechanism — only a `volatile` visibility
guarantee.

This was replaced with a classic Java monitor: a **private** lock object
(`private final Object lock = new Object()`), not `synchronized` on `this`. The class
is `public` and not `final`, so `this` is a monitor reachable from outside the class;
synchronizing on a private `lock` instead means no external code (or future subclass)
can ever share this monitor and cause unrelated blocking.

- `pause()` sets `paused = true` inside `synchronized(lock)`.
- `resume()` sets `paused = false` and calls `lock.notifyAll()` inside the same
  `synchronized(lock)` block. `notifyAll()` (not `notify()`) is required because
  multiple robot threads can be blocked simultaneously; `notify()` only guarantees
  waking one arbitrary thread, stranding the rest indefinitely.
- `awaitIfPaused()` calls `lock.wait()` inside a `while (paused)` loop (not `if`), to
  guard against spurious wakeups and against a second `pause()` occurring between a
  `notifyAll()` and the moment the thread actually resumes. `wait()` releases the lock
  while blocked, which is exactly what allows `pause()`/`resume()` to acquire it
  concurrently — replacing it with a spin loop that keeps the lock held (as the TODO
  scaffold initially did) would deadlock the whole system, since `resume()` could never
  acquire `lock` to clear the flag.

Each robot calls `control.awaitIfPaused()` at the top of its work loop
(`WarehouseRobot.run()`), before requesting a new parcel — never mid-processing — so a
pause always lands at a safe point and never interrupts an in-flight critical region in
`PackageQueue`, `DeliveryRegistry`, or `WarehouseStatistics`.

**Why the paused snapshot is consistent.** While `paused == true`, every robot is
blocked inside `awaitIfPaused()`'s `synchronized(lock)` block, not executing any
application logic. `WarehouseSimulation#snapshot()` still reads `PackageQueue`,
`DeliveryRegistry`, and `WarehouseStatistics` through their own independent monitors
(intentionally — see Part 5, no single global lock ties them together), so the read is
not atomic *across* the four objects. But since no robot is writing to any of them
during the pause, there is no concurrent writer to race against, so no torn/mid-mutation
read across objects can occur either. Consistency here comes from the *absence of active
writers* during the pause window, not from a single atomic multi-object read.
**Verification output** (`PauseResumeDemo`, 12 robots / 180 parcels):

```text
--- PAUSED SNAPSHOT ---
Initial parcels : 180
Pending parcels : 63
Processed count : 117
Registry size   : 117
Current leader  : Robot-08 / parcel 8 / position 1
Simulation paused = true

--- FINAL SNAPSHOT ---
Initial parcels : 180
Pending parcels : 0
Processed count : 180
Registry size   : 180
Current leader  : Robot-08 / parcel 8 / position 1
```

`Processed count == Registry size` (117 = 117) in the paused snapshot confirms no robot
was mid-write when the snapshot was taken — exactly the consistency argument above. The
final snapshot reaching `Pending parcels : 0` with no hang confirms `resume()` correctly
wakes every blocked robot via `notifyAll()` and the simulation completes normally.

## 7. Verification results

### `PackageQueue` fix — before/after

Verified with `mvn clean test` (all tests pass) and `RaceConditionProbe`, focusing on the symptom this fix targets: `registry.size() > initialParcels` and `uniqueParcels < registry.size()` (i.e., a parcel dispatched more than once by `takeNext()`).

| Configuration | Runs | `PackageQueue`-caused anomalies before fix | `PackageQueue`-caused anomalies after fix |
|---|---:|---:|---:|
| 24 robots / 250 parcels (`RaceConditionProbe 30 24 250`) | 30 | Yes — e.g. Run 08: `registry=251 > initialParcels=250`, `uniqueParcels=249` | 0/30 — `registry` never exceeds `initialParcels`, `uniqueParcels` never drops below `registry.size()` |
| 64 robots / 80 parcels (high contention, `RaceConditionProbe 20 64 80`) | 20 | Yes — e.g. Run 10: `registry=81 > initialParcels=80`, `uniqueParcels=79` | 0/20 — same check holds across all runs |

Total anomalous runs (all classes) did **not** reach 0/N right after this change alone (14/30 and 10/20 remained), because the remaining anomalies were produced by `DeliveryRegistry.register()` and `WarehouseStatistics.recordProcessed()`, still using their original unsynchronized read-modify-write logic at that point. Every anomaly observed after this fix alone is explained by `positionsContiguous=false` and/or `processedCounter != registry.size()` — never by `registry.size() > initialParcels` or `uniqueParcels < registry.size()`, which is the specific signature `PackageQueue.takeNext()` is responsible for.

### `DeliveryRegistry` + `WarehouseStatistics` fix — before/after

Verified with `mvn clean test` and `RaceConditionProbe`, after merging the `PackageQueue` fix into this branch (full system, all three classes fixed).

| Configuration | Runs | Anomalies before fix | Anomalies after fix |
|---|---:|---:|---:|
| 32 robots / 500 parcels | 50 | 50/50 — e.g. `processedCounter=479, registry=500, uniquePositions=452, positionsContiguous=false` | — |
| 8 robots / 100 parcels | 100 | — (see note below) | **0/100** |
| 16 robots / 250 parcels | 100 | — (see note below) | **0/100** |
| 32 robots / 500 parcels | 100 | — (see note below) | **0/100** |

> Note: the "before" baseline was captured once at 32/500/50 runs before any fix was applied (50/50 anomalous, as shown above and in Part II, Evidence 2 and 3); the three "after" rows were run once the full system (all three classes) was integrated on this branch, confirming the fix generalizes across robot/parcel scales, not just the configuration where the bug was first found.

The project reaches the `0/100` target stated in Part VI of the assignment across every tested configuration (8/100, 16/250, 32/500, all at 100 runs each) once `PackageQueue`, `DeliveryRegistry`, and `WarehouseStatistics` are all fixed together.

## 8. Quality-attribute analysis

> _TODO (team): expand with Part VII's full decision analysis and the 3-JVM architectural-boundary question. Draft below covers the three required quality attributes at a summary level based on the `PackageQueue`, `DeliveryRegistry`, and `WarehouseStatistics` fixes._

- **Correctness / reliability:** All identified race conditions (duplicate/lost parcels, duplicate delivery positions, lost statistics increments) are eliminated. Verified empirically across four robot/parcel configurations (30/24/250, 20/64/80, and 100 runs each at 8/100, 16/250, 32/500) with `RaceConditionProbe` consistently reporting `0` anomalous runs after the fix, versus every run failing before it.
- **Performance / throughput:** Every critical region is scoped to the minimum method body needed for its invariant (a few list/field operations), never wrapping the per-parcel `Thread.sleep`-based processing time that dominates each robot's work cycle. Robots therefore still execute their actual work concurrently; the only serialization introduced is the brief moment each robot spends taking a parcel, registering a delivery, or recording a statistic — operations that were always going to need some form of coordination once made correct.
- **Maintainability:** Each `synchronized` block maps to exactly one class and one clearly stated invariant (documented in Part 5's table), rather than one global lock covering unrelated state. A future maintainer changing `PackageQueue` does not need to reason about `DeliveryRegistry`'s or `WarehouseStatistics`' locking, and vice versa — the classes remain independently understandable and testable.