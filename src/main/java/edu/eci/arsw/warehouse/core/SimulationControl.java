package edu.eci.arsw.warehouse.core;

/**
 * Starter pause/resume control.
 *
 * This version intentionally uses active waiting so students can replace it with
 * a monitor-based design using synchronized + wait()/notifyAll().
 */
public class SimulationControl {

    private volatile boolean paused;
    private final Object lock = new Object();


    public void pause() {
        synchronized (lock) {
            paused = true;
        }

    }

    public void resume() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    public void awaitIfPaused() {
        synchronized (lock) {
            while (paused) {
                try {
                    lock.wait();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public boolean isPaused() {
        synchronized (lock) {
            return paused;
        }
    }
}
