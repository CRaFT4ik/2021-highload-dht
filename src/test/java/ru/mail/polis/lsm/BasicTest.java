/*
 * Copyright 2021 (c) Odnoklassniki
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.mail.polis.lsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.mail.polis.lsm.Utils.assertDaoEquals;
import static ru.mail.polis.lsm.Utils.assertEquals;
import static ru.mail.polis.lsm.Utils.generateMap;
import static ru.mail.polis.lsm.Utils.key;
import static ru.mail.polis.lsm.Utils.mapOf;
import static ru.mail.polis.lsm.Utils.wrap;

class BasicTest {

    private DAO dao;

    @BeforeEach
    void start(@TempDir Path dir) throws IOException {
        dao = TestDaoWrapper.create(new DAOConfig(dir));
    }

    @AfterEach
    void finish() throws IOException {
        dao.close();
    }

    @Test
    void empty() {
        ByteBuffer notExistedKey = ByteBuffer.wrap("NOT_EXISTED_KEY".getBytes(StandardCharsets.UTF_8));
        Iterator<Record> shouldBeEmpty = dao.range(notExistedKey, null);

        assertFalse(shouldBeEmpty.hasNext());
    }

    @Test
    void insert() {
        Map<ByteBuffer, ByteBuffer> map = mapOf(
                "NEW_KEY", "NEW_VALUE"
        );

        map.forEach((k, v) -> dao.upsert(Record.of(k, v)));

        assertDaoEquals(dao, map);
    }

    @Test
    void insertSome() {
        Map<ByteBuffer, ByteBuffer> map = generateMap(0, 10);

        map.forEach((k, v) -> dao.upsert(Record.of(k, v)));

        assertDaoEquals(dao, map);
    }

    @Test
    void insertMany() {
        Map<ByteBuffer, ByteBuffer> map = generateMap(0, 1000);

        map.forEach((k, v) -> dao.upsert(Record.of(k, v)));

        assertDaoEquals(dao, map);
    }

    @Test
    void middleScan() {
        Map<ByteBuffer, ByteBuffer> map = generateMap(0, 10);

        map.forEach((k, v) -> dao.upsert(Record.of(k, v)));

        Iterator<Record> range = dao.range(key(5), null);
        assertEquals(range, new TreeMap<>(generateMap(5, 10)).entrySet());
    }

    @Test
    void rightScan() {
        Map<ByteBuffer, ByteBuffer> map = generateMap(0, 10);

        map.forEach((k, v) -> dao.upsert(Record.of(k, v)));

        Iterator<Record> range = dao.range(key(9), null);
        assertEquals(range, new TreeMap<>(generateMap(9, 10)).entrySet());
    }

    @Test
    void emptyKey() {
        Map<ByteBuffer, ByteBuffer> map = mapOf(
                "", "VALUE"
        );

        map.forEach((k, v) -> dao.upsert(Record.of(k, v)));

        assertDaoEquals(dao, map);
    }

    @Test
    void emptyValue() {
        Map<ByteBuffer, ByteBuffer> map = mapOf(
                "KEY", ""
        );

        map.forEach((k, v) -> dao.upsert(Record.of(k, v)));

        assertDaoEquals(dao, map);
    }

    @Test
    void upsert() {
        Map<ByteBuffer, ByteBuffer> map = generateMap(0, 10);

        map.forEach((k, v) -> dao.upsert(Record.of(k, v)));
        map.forEach((k, v) -> dao.upsert(Record.of(k, v)));

        assertDaoEquals(dao, map);
    }

    @Test
    void upsertDifferent() {
        Map<ByteBuffer, ByteBuffer> map = generateMap(0, 10);

        map.forEach((k, v) -> dao.upsert(Record.of(k, v)));

        ByteBuffer upsertKey = key(5);
        ByteBuffer upsertValue = wrap("VALUE_CHANGED");

        map.put(upsertKey, upsertValue);
        dao.upsert(Record.of(upsertKey, upsertValue));

        assertDaoEquals(dao, map);
    }

    @Test
    void remove() {
        Map<ByteBuffer, ByteBuffer> map = generateMap(0, 10);

        map.forEach((k, v) -> dao.upsert(Record.of(k, v)));

        dao.upsert(Record.of(wrap("KEY_TO_REMOVE"), wrap("VALUE_TO_REMOVE")));
        dao.upsert(Record.tombstone(wrap("KEY_TO_REMOVE")));

        assertDaoEquals(dao, map);
    }

    @Test
    void removeAbsent() {
        dao.upsert(Record.tombstone(wrap("NOT_EXISTED_KEY")));

        assertFalse(dao.range(null, null).hasNext());
    }

    @Test
    void checkTombstoneTimestamp() throws IOException {
        long timestamp = System.currentTimeMillis();
        dao.upsert(Record.tombstone(wrap("RETURNABLE_TOMBSTONE_KEY"), timestamp));
        dao.compact();

        Iterator<Record> iterator = dao.range(null, null, true);
        assertTrue(iterator.hasNext());

        Assertions.assertEquals(timestamp, iterator.next().getTimestamp());
    }
}
