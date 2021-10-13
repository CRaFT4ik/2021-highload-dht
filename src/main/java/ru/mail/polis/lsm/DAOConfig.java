package ru.mail.polis.lsm;

import java.nio.file.Path;

public class DAOConfig {
    public static final int DEFAULT_MEMORY_LIMIT = 4 * 1024 * 1024;
    public static final int DEFAULT_COMPACT_SSTABLES_LIMIT = 4;

    public final Path dir;
    public final int memoryLimit;
    public final int compactLimit;

    public DAOConfig(Path dir) {
        this(dir, DEFAULT_MEMORY_LIMIT, DEFAULT_COMPACT_SSTABLES_LIMIT);
    }

    public DAOConfig(Path dir, int memoryLimit, int compactLimit) {
        this.dir = dir;
        this.memoryLimit = memoryLimit;
        this.compactLimit = compactLimit;
    }
}
