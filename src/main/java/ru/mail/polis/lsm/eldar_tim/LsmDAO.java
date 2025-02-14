package ru.mail.polis.lsm.eldar_tim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.lsm.DAO;
import ru.mail.polis.lsm.DAOConfig;
import ru.mail.polis.lsm.Record;
import ru.mail.polis.lsm.eldar_tim.components.LimitedMemTable;
import ru.mail.polis.lsm.eldar_tim.components.MemTable;
import ru.mail.polis.lsm.eldar_tim.components.SSTable;
import ru.mail.polis.lsm.eldar_tim.components.Storage;
import ru.mail.polis.lsm.eldar_tim.iterators.TombstonesFilterIterator;
import ru.mail.polis.service.exceptions.ServiceClosedException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static ru.mail.polis.lsm.eldar_tim.components.SSTable.sizeOf;
import static ru.mail.polis.lsm.eldar_tim.components.Utils.map;
import static ru.mail.polis.lsm.eldar_tim.components.Utils.mergeTwo;
import static ru.mail.polis.lsm.eldar_tim.components.Utils.sstableRanges;

@SuppressWarnings("NonAtomicOperationOnVolatileField")
public class LsmDAO implements DAO {

    private static final Logger LOG = LoggerFactory.getLogger(LsmDAO.class);

    private final AwaitableExecutor executorFlush = new AwaitableExecutor("flush");
    private final AwaitableExecutor executorCompact = new AwaitableExecutor("compact");

    private final DAOConfig config;
    private volatile Storage storage;

    private volatile boolean serverIsDown;

    /**
     * Create LsmDAO from config.
     *
     * @param config LsmDAO config
     * @throws IOException in case of io exception
     */
    public LsmDAO(DAOConfig config) throws IOException {
        this.config = config;
        List<SSTable> ssTables = SSTable.loadFromDir(config.dir);
        storage = new Storage(ssTables, config.memoryLimit);
    }

    @Override
    public Iterator<Record> range(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey, boolean withTombstones) {
        Storage st = this.storage;
        throwIfClosed();

        Iterator<Record> sstableRanges = sstableRanges(st.sstables, fromKey, toKey);

        Iterator<Record> flushingMemTableIterator = map(st.memTableToFlush, fromKey, toKey).values().iterator();
        Iterator<Record> memTableIterator = map(st.memTable, fromKey, toKey).values().iterator();
        Iterator<Record> memoryRanges = mergeTwo(flushingMemTableIterator, memTableIterator);

        Iterator<Record> iterator = mergeTwo(sstableRanges, memoryRanges);
        if (withTombstones) {
            return iterator;
        } else {
            return new TombstonesFilterIterator(iterator);
        }
    }

    @Override
    public void upsert(@Nonnull Record record) {
        int recordSize = sizeOf(record);
        while (true) {
            throwIfClosed();
            LimitedMemTable limitedMemTable = this.storage.memTable;

            if (limitedMemTable.reserveSize(recordSize)) {
                limitedMemTable.put(record, recordSize);
                break;
            } else if (limitedMemTable.requestFlush()) {
                scheduleFlush();
            }
        }
    }

    @Override
    public void compact() {
        synchronized (executorCompact) {
            Storage compactStorage = storage;
            if (compactStorage.sstables.size() < config.compactThreshold || !executorCompact.isDone()) {
                return;
            }

            executorCompact.execute(context -> {
                try {
                    LOG.info("Compacting...");
                    performCompact(compactStorage);
                    LOG.info("Compact completed");
                } catch (IOException e) {
                    LOG.error("Can't compact", e);
                }
            });
        }
    }

    @Override
    public void close() throws IOException {
        LOG.info("Closing...");

        serverIsDown = true;

        executorFlush.await();
        executorFlush.shutdown();
        executorCompact.shutdown();

        storage = storage.beforeFlush();
        flush(storage.memTableToFlush);
        storage = null;

        LOG.info("Closed");
    }

    private synchronized void scheduleFlush() {
        LOG.debug("Waiting to flush...");
        executorFlush.await();

        storage = storage.beforeFlush();

        AtomicInteger flushAttempts = new AtomicInteger();
        executorFlush.execute(context -> {
            try {
                LOG.debug("Flushing...");
                SSTable flushedTable = flush(storage.memTableToFlush);
                storage = storage.afterFlush(flushedTable);
                LOG.debug("Flush completed");
            } catch (IOException e) {
                int attempt = flushAttempts.incrementAndGet();
                LOG.error("Flush error, attempt: {}", attempt, e);
                if (attempt < 3) {
                    context.sleep(config.flushRetryTimeMs);
                    context.relaunch();
                } else {
                    throw new UncheckedIOException("Flush error", e);
                }
            }
            compact();
        });
    }

    private SSTable flush(MemTable memTable) throws IOException {
        Path dir = config.dir;
        Path file = dir.resolve(SSTable.SSTABLE_FILE_PREFIX + memTable.getId());
        return SSTable.write(memTable.raw().values().iterator(), file);
    }

    private void performCompact(Storage compactStorage) throws IOException {
        SSTable compacted = SSTable.compact(config.dir, sstableRanges(compactStorage.sstables, null, null));

        /* После входа в synchronized и ожидания завершения flush,
         * мы можем быть уверены в безопасности перезаписи storage. */
        synchronized (this) {
            executorFlush.await();
            storage = storage.afterCompact(compactStorage.sstables, compacted);
        }
    }

    private void throwIfClosed() {
        if (serverIsDown) {
            throw new ServiceClosedException();
        }
    }
}
