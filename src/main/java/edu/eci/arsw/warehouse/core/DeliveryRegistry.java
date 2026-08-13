package edu.eci.arsw.warehouse.core;

import edu.eci.arsw.warehouse.model.DeliveryRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Intentionally unsafe shared registry.
 */
public class DeliveryRegistry {

    private int nextPosition = 1;
    private final List<DeliveryRecord> deliveries = new ArrayList<>();

    public synchronized void register(int robotId, int parcelId, long elapsedMillis) { // agregamos Synchronized a los métodos que tocan el mismo estado 
        int assignedPosition = nextPosition;
        nextPosition = nextPosition + 1;
        deliveries.add(new DeliveryRecord(assignedPosition, robotId, parcelId, elapsedMillis));
    }

    public List<DeliveryRecord> snapshot() {
        // Intentionally not coordinated with concurrent writes.
        return List.copyOf(deliveries);
    }
}
