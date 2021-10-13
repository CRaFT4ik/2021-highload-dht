package ru.mail.polis.lsm.artem_drozdov;

import one.nio.async.CompletedFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.lsm.DAO;
import ru.mail.polis.lsm.DAOConfig;
import ru.mail.polis.lsm.Record;
import ru.mail.polis.lsm.artem_drozdov.iterators.TombstonesFilterIterator;
import ru.mail.polis.service.exceptions.ServerNotActiveExc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static ru.mail.polis.ServiceUtils.shutdownAndAwaitExecutor;
import static ru.mail.polis.lsm.artem_drozdov.SSTable.sizeOf;
import static ru.mail.polis.lsm.artem_drozdov.Utils.flush;
import static ru.mail.polis.lsm.artem_drozdov.Utils.map;
import static ru.mail.polis.lsm.artem_drozdov.Utils.mergeTwo;
import static ru.mail.polis.lsm.artem_drozdov.Utils.sstableRanges;

@SuppressWarnings({"PMD", "JdkObsolete"})
public class LsmDAO implements DAO {

    private static final Logger LOG = LoggerFactory.getLogger(LsmDAO.class);

    private final DAOConfig config;

    private final ExecutorService executorFlush = Executors.newSingleThreadScheduledExecutor();
    private Future<?> futureFlush = new CompletedFuture<>(null);

    private final ExecutorService executorCompact = Executors.newSingleThreadScheduledExecutor();
    private Future<?> futureCompact = new CompletedFuture<>(null);

    private final AtomicReference<MemTable> memTable = new AtomicReference<>();
    private final AtomicReference<MemTable> flushingMemTable = new AtomicReference<>();
    private final ConcurrentLinkedDeque<SSTable> tables = new ConcurrentLinkedDeque<>();

    private final AtomicInteger memoryConsumption = new AtomicInteger();

    private final ReentrantReadWriteLock rangeRWLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock upsertRWLock = new ReentrantReadWriteLock();

    private volatile boolean serverIsDown;
    private volatile boolean flushIsNotSuccess;

    /**
     * Create LsmDAO from config.
     *
     * @param config - LsmDAO config
     * @throws IOException - in case of io exception
     */
    public LsmDAO(DAOConfig config) throws IOException {
        this.config = config;
        List<SSTable> ssTables = SSTable.loadFromDir(config.dir);
        tables.addAll(ssTables);

        int nextMemTableId = tables.size();
        memTable.set(MemTable.newStorage(nextMemTableId));
        flushingMemTable.set(MemTable.newStorage(nextMemTableId + 1));
    }

    @Override
    public Iterator<Record> range(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        rangeRWLock.readLock().lock();
        try {
            if (serverIsDown) {
                throw new ServerNotActiveExc();
            }
            return rangeImpl(fromKey, toKey);
        } finally {
            rangeRWLock.readLock().unlock();
        }
    }

    @Override
    public void upsert(@Nonnull Record record) {
        upsertImpl(record);
    }

    private Iterator<Record> rangeImpl(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        Iterator<Record> sstableRanges = sstableRanges(tables, fromKey, toKey);

        Iterator<Record> flushingMemTableIterator = map(flushingMemTable.get(), fromKey, toKey).values().iterator();
        Iterator<Record> memTableIterator = map(memTable.get(), fromKey, toKey).values().iterator();
        Iterator<Record> memoryRanges = mergeTwo(flushingMemTableIterator, memTableIterator);

        Iterator<Record> iterator = mergeTwo(sstableRanges, memoryRanges);
        return new TombstonesFilterIterator(iterator);
    }

    @SuppressWarnings("LockNotBeforeTry")
    private void upsertImpl(Record record) {
        upsertRWLock.readLock().lock();
        while (memoryConsumption.addAndGet(sizeOf(record)) > config.memoryLimit) {
            upsertRWLock.readLock().unlock();
            synchronized (this) {
                if (memoryConsumption.get() <= config.memoryLimit) {
                    upsertRWLock.readLock().lock();
                    continue;
                }

                upsertRWLock.writeLock().lock();
                upsertRWLock.readLock().lock();
                try {
                    if (serverIsDown) {
                        throw new ServerNotActiveExc();
                    }

                    scheduleFlush();
                    memoryConsumption.getAndSet(sizeOf(record));
                    break;
                } catch (RuntimeException e) {
                    upsertRWLock.readLock().unlock();
                    throw e;
                } finally {
                    upsertRWLock.writeLock().unlock();
                }
            }
        }

        try {
            if (serverIsDown) {
                throw new ServerNotActiveExc();
            }

            memTable.get().put(record.getKey(), record);
        } finally {
            upsertRWLock.readLock().unlock();
        }
    }

    /**
     * Осуществляет свертку нескольких SSTable в один.
     * Внутри класса вызывается из flush task, который работает в единственном
     * экземпляре в один момент времени.
     * Метод использует synchronized, чтобы обеспечить безопасность для future compact.
     * Это не должно быть проблемой производительности, потому что метод вызывается редко.
     */
    @Override
    public void compact() {
        synchronized (executorCompact) {
            waitForCompactingComplete();
            futureCompact = executorCompact.submit(this::compactImpl);
        }
    }

    private void compactImpl() {
        LOG.info("Compact operation started");

        SSTable table;
        try {
            table = SSTable.compact(config.dir, sstableRanges(tables, null, null));
        } catch (IOException e) {
            throw new UncheckedIOException("Can't compact", e);
        }

        rangeRWLock.writeLock().lock();
        try {
            tables.clear();
            tables.add(table);
        } finally {
            rangeRWLock.writeLock().unlock();
        }

        LOG.info("Compact operation finished");
    }

    @Override
    public void close() {
        LOG.info("{} is closing...", getClass().getName());

        upsertRWLock.writeLock().lock();
        try {
            serverIsDown = true;
            scheduleFlush();
            waitForFlushingComplete();
            waitForCompactingComplete();
        } finally {
            upsertRWLock.writeLock().unlock();
            shutdownAndAwaitExecutor(executorFlush, LOG);
            shutdownAndAwaitExecutor(executorCompact, LOG);
        }

        LOG.info("{} closed", getClass().getName());
    }

    @GuardedBy("upsertRWLock")
    private void scheduleFlush() {
        waitForFlushingComplete();

        if (flushIsNotSuccess) { // Restoring not flushed data.
            MemTable flushingTable = flushingMemTable.get();
            flushingTable.putAll(memTable.get());
            memTable.set(flushingTable);
        }

        MemTable flushingTable = memTable.get();
        flushingMemTable.set(flushingTable);
        memTable.set(MemTable.newStorage(flushingTable.getId() + 1));

        assert !rangeRWLock.isWriteLockedByCurrentThread();

        futureFlush = executorFlush.submit(() -> {
            SSTable flushResult = flush(flushingTable, config.dir, LOG);
            if (flushResult == null) {
                flushIsNotSuccess = true;
                return;
            }

            boolean needCompact;
            rangeRWLock.writeLock().lock();
            try {
                tables.add(flushResult);
                needCompact = tables.size() > config.compactLimit;
            } finally {
                rangeRWLock.writeLock().unlock();
            }
            if (needCompact) {
                compact();
            }
        });
    }

    @GuardedBy("upsertRWLock")
    private void waitForFlushingComplete() {
        // Protects futureFlush variable.
        assert upsertRWLock.isWriteLockedByCurrentThread();

        try {
            futureFlush.get();
        } catch (InterruptedException | ExecutionException e) {
            LOG.error("flush future wait error: {}", e.getMessage(), e);
        }
    }

    private void waitForCompactingComplete() {
        synchronized (executorCompact) {
            try {
                futureCompact.get();
            } catch (InterruptedException | ExecutionException e) {
                LOG.error("compact future wait error: {}", e.getMessage(), e);
            }
        }
    }
}
