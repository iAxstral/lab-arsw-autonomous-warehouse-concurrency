# Laboratory 2 Report

## 1. Shared-state inventory

| Shared object | Mutable state | Readers | Writers | Possible invariant |
|---|---|---|---|---|
| `PackageQueue` | `pending: List<Parcel>` (ArrayList) | `takeNext()` (checks `isEmpty()`, reads `get(0)`), `pendingCount()` (`size()`) | `takeNext()` (`remove(0)`), constructor (`addAll`, single-threaded) | Each parcel is removed from `pending` at most once; every parcel that starts in `pending` is eventually removed exactly once (no duplication, no loss). |
| `DeliveryRegistry` | `nextPosition: int`, `deliveries: List<DeliveryRecord>` | `snapshot()` (copies `deliveries`) | `register()` (reads `nextPosition`, increments it, appends to `deliveries`) | Every call to `register()` obtains a distinct `assignedPosition`; positions form a contiguous sequence `1..deliveries.size()`; `deliveries.size()` only grows by `+1` per `register()` call. |
| `WarehouseStatistics` | `processedParcels: int`, `totalProcessingMillis: long` | `processedParcels()`, `totalProcessingMillis()` | `recordProcessed()` (read-modify-write on both fields) | `processedParcels` equals the number of completed `recordProcessed()` calls; `totalProcessingMillis` equals the sum of every `elapsedMillis` argument passed in. |
| `SimulationControl` | `paused: boolean` (`volatile`) | `awaitIfPaused()`, `isPaused()` | `pause()`, `resume()` | All robots observe a `pause()`/`resume()` transition promptly and stop consuming CPU while paused (currently violated by the busy-wait spin loop, not by visibility — the field is already `volatile`). |

## 2. Observed anomalies

All evidence below was captured on this branch's baseline (pre-fix) commit, JDK 21.0.11, running `mvn clean compile` then invoking the compiled classes directly. Each run is fully reproducible by re-running the same command; the *specific* run number that reproduced the anomaly varies because the defects are genuine data races, not deterministic bugs.

### Evidence 1 — `PackageQueue`: the same parcel is delivered twice

- **Command:** `java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 20 64 80`
- **Execution:** Run 10 of 20 (high contention: 64 robot threads competing for only 80 parcels, so `pending` is drained fast and many threads race on the last elements)
- **Console output:**
  ```text
  Run 10 -> RACE/ANOMALY | pending=0, processedCounter=79, registry=81, uniqueParcels=79, uniquePositions=79, positionsContiguous=false
  ```
- **Suspected class/method:** `PackageQueue.takeNext()`
- **Explanation:** `initialParcels` was 80, yet `DeliveryRegistry` ended up with **81** delivery records, and only **79** distinct `parcelId`s appear among them. That means two robots received the *same* `Parcel` instance from `takeNext()` and both processed and registered it, while at least one other parcel was never dispensed. This is exactly the check-then-act race the starter comment calls out: `isEmpty()` → `get(0)` → (`Thread.yield()`) → `remove(0)` is not atomic, so two threads can both pass the `isEmpty()`/`get(0)` step for the same head element before either removes it.

### Evidence 2 — `DeliveryRegistry`: two robots receive the same arrival position

- **Command:** `java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 30 24 250`
- **Execution:** Run 04 of 30
- **Console output:**
  ```text
  Run 04 -> RACE/ANOMALY | pending=0, processedCounter=250, registry=250, uniqueParcels=250, uniquePositions=249, positionsContiguous=false
  ```
- **Suspected class/method:** `DeliveryRegistry.register()`
- **Explanation:** Here `processedCounter == registry.size() == 250`, so `WarehouseStatistics` and the record count are internally consistent — this run isolates the registry defect specifically. `deliveries` has 250 records but only **249** distinct `position` values, and the positions are not a contiguous `1..250` sequence. `register()` reads `nextPosition` into a local variable, calls `Thread.yield()`, and only then writes `nextPosition = nextPosition + 1`. Two robots can read the same `nextPosition` before either writes it back, so both `DeliveryRecord`s are stamped with the same `assignedPosition` and one position value is skipped entirely.

### Evidence 3 — `WarehouseStatistics`: lost updates on `processedParcels`

- **Command:** `java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 30 24 250`
- **Execution:** Run 01 of 30
- **Console output:**
  ```text
  Run 01 -> RACE/ANOMALY | pending=0, processedCounter=247, registry=249, uniqueParcels=243, uniquePositions=247, positionsContiguous=false
  ```
- **Suspected class/method:** `WarehouseStatistics.recordProcessed()`
- **Explanation:** `deliveries.size()` is 249 (one call to `deliveryRegistry.register()` per successfully processed parcel), but `processedParcels` only reports **247** — two increments were lost. `recordProcessed()` performs `int current = processedParcels; Thread.yield(); processedParcels = current + 1;`, a classic non-atomic read-modify-write. When two robots interleave inside that window, both read the same `current` value and the second write overwrites the first, silently dropping one increment.

## 3. Interleaving analysis

Chosen race condition: **`PackageQueue.takeNext()` duplicate dispatch** (Evidence 1).

| Step | Robot A (thread) | Robot B (thread) | Shared state (`pending`) |
|---:|---|---|---|
| 1 | calls `takeNext()`; evaluates `pending.isEmpty()` → `false` | — | `[P7]` (last remaining parcel) |
| 2 | reads `pending.get(0)` → `selected = P7` | calls `takeNext()`; evaluates `pending.isEmpty()` → `false` (A has not removed anything yet) | `[P7]` |
| 3 | `Thread.yield()` — scheduler switches to B | reads `pending.get(0)` → `selected = P7` | `[P7]` |
| 4 | (suspended) | `Thread.yield()` — scheduler switches back to A | `[P7]` |
| 5 | `pending.remove(0)` removes `P7`; returns `P7` to A | (suspended) | `[]` |
| 6 | — | resumes; calls `pending.remove(0)` on an **empty list** → either throws `IndexOutOfBoundsException` (caught in `WarehouseRobot.run()` and retried) *or*, if a subsequent parcel had already been pushed back in a different interleaving, removes and returns the *next* parcel while still holding a stale reference to `P7` | `[]` |
| 7 | processes `P7`, registers delivery, updates statistics | processes `P7` (its own local `selected` variable, obtained in step 3) again, registers a **second** delivery for `P7`, updates statistics | — |

**Why is the final result dependent on scheduling?**

> Because `takeNext()` performs a **check** (`isEmpty()`), a **read** (`get(0)`), and an **act** (`remove(0)`) as three separate, unsynchronized operations on the same mutable list, with a `Thread.yield()` deliberately widening the gap between them. Nothing prevents the OS/JVM scheduler from suspending Robot A between the read and the act and running Robot B through the very same sequence on the very same head element. Whether that happens — and therefore whether a parcel gets delivered once, delivered twice, or a robot instead hits an `IndexOutOfBoundsException` on an empty list — depends entirely on the interleaving the scheduler happens to choose on that particular run. That is why some runs report `OK` and others report `RACE/ANOMALY` with the exact same code and inputs: the outcome is a function of thread scheduling, not of the input data.

## 4. System invariants

## 5. Critical regions and synchronization decisions

> This report documents the `PackageQueue` fix. `DeliveryRegistry` and `WarehouseStatistics` critical regions are documented by the teammates responsible for those classes.

| Class | Critical region | Protected invariant | Synchronization mechanism | Why this granularity? |
|---|---|---|---|---|
| `PackageQueue` | Entire body of `takeNext()` (`isEmpty()` check + `get(0)`/`remove(0)` act); entire body of `pendingCount()` | Each parcel is handed out to at most one caller; no parcel is lost or duplicated between `pending` and the rest of the system | `synchronized` instance methods (monitor on `this`) | The check-then-act sequence in `takeNext()` (`isEmpty()` → remove head) *is* the whole invariant-relevant computation — there is no sub-step that can be excluded from the lock without reopening the race demonstrated in [Part I, Evidence 1](#evidence-1--packagequeue-the-same-parcel-is-delivered-twice). Splitting it into a smaller synchronized block would either still cover the whole method (no gain) or expose the same check-then-act gap. `pendingCount()` is synchronized on the same monitor, not because a `size()` read can corrupt the list, but so a caller observing `pendingCount()` gets a value that is consistent with (happens-after) the most recent completed `takeNext()`, instead of a stale/racy read. Both methods are short, non-blocking (no I/O, no waiting) and called once per work item per robot, so contention is bounded by queue-draining traffic only — there was no reason to reach for a more elaborate mechanism (`Lock`, `ConcurrentLinkedQueue`, etc.) to protect two three-line methods on a single shared instance. |

**Why is the whole method the critical region here, and not just part of it?**

> `takeNext()`'s invariant ("give each parcel to at most one caller") depends on the *combination* of the check and the act being seen atomically by every thread — protecting only `get(0)` or only `remove(0)` leaves the other operation free to interleave and reproduces the exact race from Evidence 1. Since the method already does the minimum work needed (no processing, no I/O, no blocking calls) inside that region, `synchronized` on the whole method is the *minimum sufficient* critical region, not an example of "synchronize everything without analysis" — it just happens that here the minimum region equals the whole method body.

**What would happen to throughput if the protected region were unnecessarily large?**

> Every robot thread calls `takeNext()` once per parcel it processes, but the actual *work* per parcel (`WarehouseRobot.process()`, driven by `Thread.sleep(parcel.processingMillis() + jitter)`, roughly 10–22 ms) happens **outside** `PackageQueue` entirely. If the lock scope were widened to also cover parcel processing — e.g. by holding the `PackageQueue` monitor while sleeping/"working" on the parcel instead of releasing it right after `remove(0)` returns — every other robot would have to wait for that entire processing time before it could even check whether the queue was empty. With `N` robots that turns an embarrassingly-parallel workload into an effectively **serial** one: total wall-clock time would approach `parcelCount × averageProcessingMillis` regardless of how many robot threads exist, instead of `≈ parcelCount × averageProcessingMillis / N`. Keeping the critical region limited to the list mutation (a few nanoseconds) lets robots spend virtually all of their time doing the actual (lock-free) processing work concurrently, which is the entire point of using multiple threads in the first place.

## 6. Thread completion and pause/resume coordination

## 7. Verification results

### `PackageQueue` fix — before/after

Verified with `mvn clean test` (all tests pass) and `RaceConditionProbe`, focusing on the symptom this fix targets: `registry.size() > initialParcels` and `uniqueParcels < registry.size()` (i.e., a parcel dispatched more than once by `takeNext()`).

| Configuration | Runs | `PackageQueue`-caused anomalies before fix | `PackageQueue`-caused anomalies after fix |
|---|---:|---:|---:|
| 24 robots / 250 parcels (`RaceConditionProbe 30 24 250`) | 30 | Yes — e.g. Run 08: `registry=251 > initialParcels=250`, `uniqueParcels=249` | 0/30 — `registry` never exceeds `initialParcels`, `uniqueParcels` never drops below `registry.size()` |
| 64 robots / 80 parcels (high contention, `RaceConditionProbe 20 64 80`) | 20 | Yes — e.g. Run 10: `registry=81 > initialParcels=80`, `uniqueParcels=79` | 0/20 — same check holds across all runs |

Total anomalous runs (all classes) did **not** reach 0/N after this change (14/30 and 10/20 remained), because the remaining anomalies are produced by `DeliveryRegistry.register()` and `WarehouseStatistics.recordProcessed()`, which are out of scope for this branch and still use their original unsynchronized read-modify-write logic. Every anomaly observed after the fix is explained by `positionsContiguous=false` and/or `processedCounter != registry.size()` — never by `registry.size() > initialParcels` or `uniqueParcels < registry.size()`, which is the specific signature `PackageQueue.takeNext()` is responsible for. The project reaches the `0/100` target only once the other two classes are fixed by the corresponding teammates.

## 8. Quality-attribute analysis
