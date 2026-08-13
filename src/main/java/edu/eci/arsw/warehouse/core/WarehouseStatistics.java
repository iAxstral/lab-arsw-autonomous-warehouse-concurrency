package edu.eci.arsw.warehouse.core;

/**
 * Intentionally unsafe counters. ++ and += are not atomic read-modify-write operations.
 */
public class WarehouseStatistics {

    private int processedParcels;
    private long totalProcessingMillis;

    public synchronized void recordProcessed(long elapsedMillis) {
        processedParcels++; // se necesita synchronized también en los getters
        totalProcessingMillis += elapsedMillis; // me ayudó a auto completar copilot
    }

    public synchronized int processedParcels() {
        return processedParcels;
    }

    public synchronized long totalProcessingMillis() {
        return totalProcessingMillis;
    }
}
