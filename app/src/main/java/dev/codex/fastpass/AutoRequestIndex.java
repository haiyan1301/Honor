package dev.codex.fastpass;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Bounds observer memory and keeps old native callbacks associated after a newer attempt. */
public final class AutoRequestIndex<T> {
    private final int capacity;
    private final IdentityHashMap<Object,T> values = new IdentityHashMap<>();
    private final List<Object> order = new ArrayList<>();
    public AutoRequestIndex(int capacity) { if (capacity < 1) throw new IllegalArgumentException(); this.capacity = capacity; }
    public synchronized void put(Object request, T value) {
        if (!values.containsKey(request)) order.add(request);
        values.put(request, value);
        while (order.size() > capacity) values.remove(order.remove(0));
    }
    public synchronized T get(Object request) { return values.get(request); }
    public synchronized List<T> snapshot() {
        List<T> list = new ArrayList<>();
        for (Object key : order) list.add(values.get(key));
        return list;
    }
}
