package edu.eci.arsw.warehouse.core;

import edu.eci.arsw.warehouse.model.Parcel;

import java.util.ArrayList;
import java.util.List;

/**
 * The invariant to protect is: each parcel in {@code pending} is handed out by
 * {@code takeNext()} to at most one caller. That requires the check
 * ({@code isEmpty()}), the read ({@code get(0)}) and the act ({@code remove(0)})
 * to happen as a single atomic step, so the critical region is the entire body
 * of {@code takeNext()} — there is no smaller region that still preserves the
 * invariant, since any split reintroduces the check-then-act race. {@code
 * pendingCount()} is synchronized on the same monitor purely so its reads
 * cannot observe a torn/mid-mutation state and so it establishes a
 * happens-before edge with concurrent writers.
 */
public class PackageQueue {

    private final List<Parcel> pending = new ArrayList<>();

    public PackageQueue(List<Parcel> parcels) {
        pending.addAll(parcels);
    }

    public synchronized Parcel takeNext() {
        if (pending.isEmpty()) {
            return null;
        }
        return pending.remove(0);
    }

    public synchronized int pendingCount() {
        return pending.size();
    }
}
