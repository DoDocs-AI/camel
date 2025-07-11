/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.support.cache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.apache.camel.support.cache.SimpleLRUCache.MINIMUM_QUEUE_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test class for {@link SimpleLRUCache}.
 */
@Isolated("Some of these tests creates a lot of threads")
@DisabledIfSystemProperty(named = "ci.env.name", matches = ".*",
                          disabledReason = "Apache CI nodes are too resource constrained for this test")
class SimpleLRUCacheTest {

    private final List<String> consumed = new ArrayList<>();
    private final SimpleLRUCache<String, String> map = new SimpleLRUCache<>(16, 3, consumed::add);

    @Test
    void forbiddenOperations() {
        assertThrows(UnsupportedOperationException.class, () -> map.values().add("foo"));
        assertThrows(UnsupportedOperationException.class, () -> map.keySet().add("foo"));
        assertThrows(UnsupportedOperationException.class, () -> map.entrySet().add(Map.entry("x", "y")));
    }

    @Test
    void setValue() {
        assertNull(map.put("1", "One"));
        assertEquals(1, map.size());
        assertEquals(1, map.getQueueSize());
        assertEquals("One", map.get("1"));
        map.entrySet().iterator().next().setValue("bar");
        assertEquals(1, map.size());
        assertEquals(2, map.getQueueSize());
        assertEquals("bar", map.get("1"));
        assertThrows(NullPointerException.class, () -> map.entrySet().iterator().next().setValue(null));
    }

    @Test
    void queueSize() {
        assertEquals(0, map.getQueueSize());
        for (int i = 1; i <= MINIMUM_QUEUE_SIZE; i++) {
            map.put("1", Integer.toString(i));
            assertEquals(1, map.size());
            assertEquals(i, map.getQueueSize());
        }
        map.put("1", "A");
        assertEquals(1, map.size());
        assertEquals(1, map.getQueueSize());
        map.put("1", "B");
        assertEquals(1, map.size());
        assertEquals(2, map.getQueueSize());
        map.put("2", "A");
        assertEquals(2, map.size());
        assertEquals(3, map.getQueueSize());
        map.put("3", "A");
        assertEquals(3, map.size());
        assertEquals(4, map.getQueueSize());
        map.put("4", "A");
        assertEquals(3, map.size());
        assertEquals(3, map.getQueueSize());
    }

    @Test
    void size() {
        assertEquals(0, map.size());
        assertNull(map.put("1", "One"));
        assertEquals(1, map.size());
    }

    @Test
    void isEmpty() {
        assertTrue(map.isEmpty());
        assertNull(map.put("1", "One"));
        assertFalse(map.isEmpty());
        map.remove("1");
        assertTrue(map.isEmpty());
    }

    @Test
    void containsKey() {
        assertFalse(map.containsKey("1"));
        assertNull(map.put("1", "One"));
        assertTrue(map.containsKey("1"));
        map.remove("1");
        assertFalse(map.containsKey("1"));
        assertThrows(NullPointerException.class, () -> map.containsKey(null));
    }

    @Test
    void containsValue() {
        assertFalse(map.containsValue("One"));
        assertNull(map.put("1", "One"));
        assertTrue(map.containsValue("One"));
        map.remove("1");
        assertFalse(map.containsValue("One"));
        assertThrows(NullPointerException.class, () -> map.containsValue(null));
    }

    @Test
    void remove() {
        assertTrue(map.isEmpty());
        map.remove("1");
        assertTrue(map.isEmpty());
        assertNull(map.put("1", "One"));
        assertFalse(map.isEmpty());
        map.remove("1");
        assertTrue(map.isEmpty());
        assertThrows(NullPointerException.class, () -> map.remove(null));
    }

    @Test
    void removeWithValue() {
        assertTrue(map.isEmpty());
        map.remove("1", "One");
        assertTrue(map.isEmpty());
        assertNull(map.put("1", "One"));
        assertFalse(map.isEmpty());
        map.remove("1", "Two");
        assertFalse(map.isEmpty());
        map.remove("2", "One");
        assertFalse(map.isEmpty());
        map.remove("1", "One");
        assertTrue(map.isEmpty());
        assertThrows(NullPointerException.class, () -> map.remove(null, "A"));
        assertThrows(NullPointerException.class, () -> map.remove("A", null));
    }

    @Test
    void put() {
        assertEquals(0, map.size());
        assertNull(map.put("1", "One"));
        assertEquals(1, map.size());
        assertNull(map.put("2", "Two"));
        assertEquals(2, map.size());
        assertNull(map.put("3", "Three"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertNull(map.put("4", "Four"));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertFalse(map.containsKey("1"));
        assertTrue(consumed.contains("One"));
        assertEquals("Two", map.put("2", "Two v2"));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertTrue(map.containsKey("2"));
        assertEquals("Two v2", map.get("2"));
        assertThrows(NullPointerException.class, () -> map.put("A", null));
        assertThrows(NullPointerException.class, () -> map.put(null, "A"));
    }

    @Test
    void putAll() {
        assertEquals(0, map.size());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("1", "One");
        data.put("2", "Two");
        data.put("3", "Three");
        map.putAll(data);
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        data.clear();
        data.put("4", "Four");
        data.put("5", "Five");
        map.putAll(data);
        assertEquals(3, map.size());
        assertEquals(2, consumed.size());
        assertFalse(map.containsKey("1"));
        assertFalse(map.containsKey("2"));
        assertTrue(consumed.contains("One"));
        assertTrue(consumed.contains("Two"));
        assertThrows(NullPointerException.class, () -> map.putAll(null));
    }

    @Test
    void clear() {
        assertEquals(0, map.size());
        map.putAll(Map.of("1", "One", "2", "Two", "3", "Three"));
        assertEquals(3, map.size());
        map.clear();
        assertEquals(0, map.size());
    }

    @Test
    void replaceAll() {
        map.replaceAll((k, v) -> v + " v2");
        assertEquals(0, map.size());
        map.putAll(Map.of("1", "One", "2", "Two", "3", "Three"));
        assertEquals(3, map.size());
        map.replaceAll((k, v) -> v + " v2");
        assertEquals(3, map.size());
        assertEquals("One v2", map.get("1"));
        assertEquals("Two v2", map.get("2"));
        assertEquals("Three v2", map.get("3"));
        assertThrows(NullPointerException.class, () -> map.replaceAll(null));
    }

    @Test
    void putIfAbsent() {
        assertEquals(0, map.size());
        assertNull(map.putIfAbsent("1", "One"));
        assertEquals(1, map.size());
        assertNull(map.putIfAbsent("2", "Two"));
        assertEquals(2, map.size());
        assertNull(map.putIfAbsent("3", "Three"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertNull(map.putIfAbsent("4", "Four"));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertFalse(map.containsKey("1"));
        assertTrue(consumed.contains("One"));
        assertEquals("Two", map.putIfAbsent("2", "Two v2"));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertTrue(map.containsKey("2"));
        assertEquals("Two", map.get("2"));
        assertNull(map.putIfAbsent("5", "Five"));
        assertEquals(3, map.size());
        assertEquals(2, consumed.size());
        assertFalse(map.containsKey("2"));
        assertTrue(consumed.contains("Two"));
        assertThrows(NullPointerException.class, () -> map.putIfAbsent("A", null));
        assertThrows(NullPointerException.class, () -> map.putIfAbsent(null, "A"));
    }

    @Test
    void computeIfAbsent() {
        assertEquals(0, map.size());
        assertEquals("One", map.computeIfAbsent("1", k -> "One"));
        assertEquals(1, map.size());
        assertEquals("Two", map.computeIfAbsent("2", k -> "Two"));
        assertEquals(2, map.size());
        assertEquals("Three", map.computeIfAbsent("3", k -> "Three"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertEquals("Four", map.computeIfAbsent("4", k -> "Four"));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertFalse(map.containsKey("1"));
        assertTrue(consumed.contains("One"));
        assertNull(map.computeIfAbsent("1", k -> null));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertNull(map.computeIfAbsent("5", k -> null));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertEquals("Two", map.computeIfAbsent("2", k -> "Two v2"));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertTrue(map.containsKey("2"));
        assertEquals("Two", map.get("2"));
        assertEquals("Five", map.computeIfAbsent("5", k -> "Five"));
        assertEquals(3, map.size());
        assertEquals(2, consumed.size());
        assertFalse(map.containsKey("2"));
        assertTrue(consumed.contains("Two"));
        assertEquals("Five", map.computeIfAbsent("5", k -> null));
        assertEquals(3, map.size());
        assertEquals(2, consumed.size());
        assertThrows(NullPointerException.class, () -> map.computeIfAbsent(null, k -> null));
        assertThrows(NullPointerException.class, () -> map.computeIfAbsent("A", null));
    }

    @Test
    void computeIfPresent() {
        assertEquals(0, map.size());
        map.putIfAbsent("1", "One");
        assertEquals(1, map.size());
        map.putIfAbsent("2", "Two");
        assertEquals(2, map.size());
        map.putIfAbsent("3", "Three");
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertNull(map.computeIfPresent("4", (k, v) -> "Four"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertFalse(map.containsKey("4"));
        assertEquals("One v2", map.computeIfPresent("1", (k, v) -> "One v2"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertTrue(map.containsKey("1"));
        assertEquals("One v2", map.get("1"));
        assertNull(map.computeIfPresent("1", (k, v) -> null));
        assertEquals(2, map.size());
        assertEquals(0, consumed.size());
        assertFalse(map.containsKey("1"));
        assertThrows(NullPointerException.class, () -> map.computeIfPresent(null, (k, v) -> null));
        assertThrows(NullPointerException.class, () -> map.computeIfPresent("A", null));
    }

    @Test
    void compute() {
        assertEquals(0, map.size());
        assertEquals("One", map.compute("1", (k, v) -> "One"));
        assertEquals(1, map.size());
        assertEquals("Two", map.compute("2", (k, v) -> "Two"));
        assertEquals(2, map.size());
        assertEquals("Three", map.compute("3", (k, v) -> "Three"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertEquals("Four", map.compute("4", (k, v) -> "Four"));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertFalse(map.containsKey("1"));
        assertTrue(consumed.contains("One"));
        assertEquals("Two v2", map.compute("2", (k, v) -> "Two v2"));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertTrue(map.containsKey("2"));
        assertEquals("Two v2", map.get("2"));
        assertNull(map.compute("2", (k, v) -> null));
        assertEquals(2, map.size());
        assertEquals(1, consumed.size());
        assertFalse(map.containsKey("2"));
        assertThrows(NullPointerException.class, () -> map.compute(null, (k, v) -> null));
        assertThrows(NullPointerException.class, () -> map.compute("A", null));
    }

    @Test
    void merge() {
        assertEquals(0, map.size());
        assertEquals("One", map.merge("1", "One", String::concat));
        assertEquals(1, map.size());
        assertEquals("Two", map.merge("2", "Two", String::concat));
        assertEquals(2, map.size());
        assertEquals("Three", map.merge("3", "Three", String::concat));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertEquals("Four", map.merge("4", "Four", String::concat));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertFalse(map.containsKey("1"));
        assertTrue(consumed.contains("One"));
        assertEquals("TwoV2", map.merge("2", "V2", String::concat));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertNull(map.merge("2", "V2", (v1, v2) -> null));
        assertEquals(2, map.size());
        assertEquals(1, consumed.size());
        assertThrows(NullPointerException.class, () -> map.merge("A", "B", null));
        assertThrows(NullPointerException.class, () -> map.merge("A", null, (v1, v2) -> null));
        assertThrows(NullPointerException.class, () -> map.merge(null, "A", (v1, v2) -> null));
    }

    @Test
    void replace() {
        assertEquals(0, map.size());
        assertNull(map.replace("1", "One"));
        assertEquals(0, map.size());
        map.put("1", "One");
        assertEquals(1, map.size());
        map.put("2", "Two");
        assertEquals(2, map.size());
        map.put("3", "Three");
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertEquals("One", map.replace("1", "One v2"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertEquals("Three", map.replace("3", "Three v2"));
        assertEquals("Three v2", map.get("3"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertThrows(NullPointerException.class, () -> map.replace("A", null));
        assertThrows(NullPointerException.class, () -> map.replace(null, "A"));
    }

    @Test
    void replaceWithOldValue() {
        assertEquals(0, map.size());
        map.put("1", "One");
        map.put("2", "Two");
        map.put("3", "Three");
        assertEquals(3, map.size());
        assertFalse(map.replace("1", "foo", "One"));
        assertEquals(3, map.size());
        assertFalse(map.replace("1", "foo", "One v2"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertEquals("One", map.get("1"));
        assertTrue(map.replace("1", "One", "One v2"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertEquals("One v2", map.get("1"));
        assertFalse(map.replace("3", "foo", "Three v2"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertTrue(map.replace("3", "Three", "Three v2"));
        assertEquals("Three v2", map.get("3"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertThrows(NullPointerException.class, () -> map.replace("A", "B", null));
        assertThrows(NullPointerException.class, () -> map.replace("A", null, "B"));
        assertThrows(NullPointerException.class, () -> map.replace(null, "A", "B"));
    }

    @Test
    void ignoreDuplicates() {
        assertEquals(0, map.size());
        for (int i = 0; i < 100; i++) {
            map.put("1", Integer.toString(i));
            assertEquals(1, map.size(), String.format("The expected size is 1 but it fails after %d puts", i + 1));
        }
        assertEquals("99", map.get("1"));
        assertNull(map.put("2", "Two"));
        assertEquals(2, map.size());
        assertEquals("99", map.get("1"));
        assertNull(map.put("3", "Three"));
        assertEquals(3, map.size());
        assertEquals(0, consumed.size());
        assertEquals("99", map.get("1"));
        assertNull(map.put("4", "Four"));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertFalse(map.containsKey("1"));
        assertTrue(consumed.contains("99"));
    }

    @Test
    void ensureEvictionOrdering() {
        assertEquals(0, map.size());
        assertNull(map.put("1", "One"));
        assertNotNull(map.put("1", "One"));
        assertNotNull(map.put("1", "One"));
        assertNotNull(map.put("1", "One"));
        assertNotNull(map.put("1", "One"));
        assertNotNull(map.put("1", "One"));
        assertNull(map.put("2", "Two"));
        assertNotNull(map.put("1", "One"));
        assertNull(map.put("3", "Three"));
        assertEquals(3, map.size());
        assertNull(map.put("4", "Four"));
        assertEquals(3, map.size());
        assertEquals(1, consumed.size());
        assertFalse(map.containsKey("2"));
        assertTrue(consumed.contains("Two"));
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, -1 })
    void validateCacheSize(int maximumCacheSize) {
        assertThrows(IllegalArgumentException.class, () -> new SimpleLRUCache<>(16, maximumCacheSize, x -> {
        }));
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 5, 10, 20, 50, 100, 1_000 })
    void concurrentPut(int maximumCacheSize) throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        int totalKeysPerThread = 1_000;
        AtomicInteger counter = new AtomicInteger();
        SimpleLRUCache<String, String> cache = new SimpleLRUCache<>(16, maximumCacheSize, v -> counter.incrementAndGet());

        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    for (int j = 0; j < totalKeysPerThread; j++) {
                        cache.put(threadId + "-" + j, Integer.toString(j));
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS),
                "Should have completed within a reasonable timeframe. Latch at: " + latch.getCount());
        assertEquals(maximumCacheSize, cache.size());
        assertEquals(totalKeysPerThread * threads - maximumCacheSize, counter.get());
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 5, 10, 20, 50, 100, 500 })
    void concurrentPutWithCollisions(int maximumCacheSize) throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        int totalKeys = 1_000;
        AtomicInteger counter = new AtomicInteger();
        SimpleLRUCache<String, String> cache = new SimpleLRUCache<>(16, maximumCacheSize, v -> counter.incrementAndGet());
        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < totalKeys; j++) {
                        cache.put(Integer.toString(j), Integer.toString(j));
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS),
                "Should have completed within a reasonable timeframe. Latch at: " + latch.getCount());
        assertEquals(maximumCacheSize, cache.size());
        counter.set(0);
        for (int j = 0; j < maximumCacheSize; j++) {
            cache.put(Integer.toString(j), "OK");
        }
        assertEquals(maximumCacheSize, counter.get());
        for (int j = 0; j < maximumCacheSize; j++) {
            assertEquals("OK", cache.get(Integer.toString(j)));
        }
    }
}
