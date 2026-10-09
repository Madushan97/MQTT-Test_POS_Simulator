package com.hnb.wireme.tms.simulator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A bounded, thread-safe history of exchanges (oldest dropped first), with change listeners. One per
 * UI tab, and one per simulated device in the bulk fleet, so a fleet run can't grow without limit.
 */
final class ExchangeLog {

    private final int capacity;
    private final Deque<Exchange> items = new ArrayDeque<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    ExchangeLog(int capacity) {
        this.capacity = capacity;
    }

    Exchange add(Exchange e) {
        e.onChange(this::fire);
        synchronized (items) {
            items.addFirst(e);
            while (items.size() > capacity) {
                items.removeLast();
            }
        }
        fire();
        return e;
    }

    /** Newest first. */
    List<Exchange> snapshot() {
        synchronized (items) {
            return new ArrayList<>(items);
        }
    }

    void clear() {
        synchronized (items) {
            items.clear();
        }
        fire();
    }

    void addListener(Runnable r) {
        listeners.add(r);
    }

    void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void fire() {
        for (Runnable r : listeners) {
            r.run();
        }
    }
}
