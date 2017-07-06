/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.datasource;

import co.rsk.panic.PanicProcessor;
import org.ethereum.config.SystemProperties;
import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.System.getProperty;
import static org.fusesource.leveldbjni.JniDBFactory.factory;

/**
 * @author Roman Mandeleil
 * @since 18.01.2015
 */
@Component
@Scope("prototype")
public class LevelDbDataSource implements KeyValueDataSource {

    private static final Logger logger = LoggerFactory.getLogger("db");
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    // following might need to be configured at runtime, tbd
    public static final int LDB_CACHE_SIZE = 0;
    public static final int LDB_BLOCK_SIZE = 10 * 1024 * 1024;
    public static final int LDB_WRITE_BUFFER_SIZE = 10 * 1024 * 1024;
    public static final int LDB_MAX_OPEN_FILES = 128;
    public static final boolean LDB_PARANOID_CHECKS = true;
    public static final boolean LDB_VERIFY_CHECKSUMS = true;
    public static final int LDB_JNI_MEMORY_POOL_SIZE = 1024 * 512;

    @Autowired
    private SystemProperties config = SystemProperties.CONFIG; // initialized for standalone test

    private long lastTimeUsed;
    private String name;
    private DB db;

    // The native LevelDB insert/update/delete are normally thread-safe
    // However close operation is not thread-safe and may lead to a native crash when
    // accessing a closed DB.
    // The leveldbJNI lib has a protection over accessing closed DB but it is not synchronized
    // This ReadWriteLock still permits concurrent execution of insert/delete/update operations
    // however blocks them on init/close/delete operations
    private ReadWriteLock resetDbLock = new ReentrantReadWriteLock();

    public LevelDbDataSource() {
    }

    public LevelDbDataSource(String name) {
        this.name = name;
        logger.info("New LevelDbDataSource: " + name);
    }

    @Override
    public void init() {
        resetDbLock.writeLock().lock();
        try {
            updateLastTimeUsed();
            logger.debug("~> LevelDbDataSource.init(): " + name);

            if (isAlive()) throw new IllegalStateException("This is already initialised... bug?");
            if (name == null) throw new NullPointerException("no name set to the db");

            Options options = new Options();
            options.createIfMissing(true);
            options.compressionType(CompressionType.NONE);
            options.blockSize(LDB_BLOCK_SIZE);
            options.writeBufferSize(LDB_WRITE_BUFFER_SIZE);
            options.maxOpenFiles(LDB_MAX_OPEN_FILES);
            options.logger(message -> logger.debug(message));
            options.cacheSize(LDB_CACHE_SIZE);
            options.paranoidChecks(LDB_PARANOID_CHECKS);
            options.verifyChecksums(LDB_VERIFY_CHECKSUMS);

            try {
                logger.debug("Opening database");
                Path dbPath;

                if (Paths.get(config.databaseDir()).isAbsolute())
                    dbPath = Paths.get(config.databaseDir(), name);
                else
                    dbPath = Paths.get(getProperty("user.dir"), config.databaseDir(), name);

                Files.createDirectories(dbPath.getParent());

                logger.debug("Initializing new or existing database: '{}'", name);
                this.db = factory.open(dbPath.toFile(), options);

            } catch (IOException ioe) {
                panicProcessor.panic("leveldb", ioe.getMessage());
                throw new RuntimeException("Can't initialize database", ioe);
            }
            logger.debug("<~ LevelDbDataSource.init(): " + name);
        } finally {
            resetDbLock.writeLock().unlock();
        }
    }

    @Override
    public boolean isAlive() {
        return this.db != null;
    }

    public void destroyDB(File fileLocation) {
        resetDbLock.writeLock().lock();
        try {
            logger.debug("Destroying existing database: " + fileLocation);
            Options options = new Options();
            try {
                factory.destroy(fileLocation, options);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                panicProcessor.panic("leveldb", e.getMessage());
            }
        } finally {
            resetDbLock.writeLock().unlock();
        }
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public byte[] get(byte[] key) {
        resetDbLock.readLock().lock();
        try {
            updateLastTimeUsed();
            if (logger.isTraceEnabled())
                logger.trace("~> LevelDbDataSource.get(): {}, key: {}", name, Hex.toHexString(key));
            try {
                byte[] ret = db.get(key);
                if (logger.isTraceEnabled())
                    logger.trace("<~ LevelDbDataSource.get(): {}, key: {}, {}", name, Hex.toHexString(key), ret == null ? "null" : ret.length);

                return ret;
            } catch (DBException e) {
                logger.error("Couldn't read from LevelDb, very bad.", e);
                panicProcessor.panic("leveldb", String.format("Couldn't read from LevelDb, very bad. %s", e.getMessage()));
                throw e;
            }
        } finally {
            resetDbLock.readLock().unlock();
        }
    }

    @Override
    public byte[] put(byte[] key, byte[] value) {
        resetDbLock.readLock().lock();
        try {
            updateLastTimeUsed();
            if (logger.isTraceEnabled())
                logger.trace("~> LevelDbDataSource.put(): {}, key: {}, {}", name, Hex.toHexString(key), value == null ? "null" : value.length);
            db.put(key, value);
            logger.trace("<~ LevelDbDataSource.put(): {}, key: {}, {}", name, Hex.toHexString(key), value == null ? "null" : value.length);
            return value;
        } finally {
            resetDbLock.readLock().unlock();
        }
    }

    @Override
    public void delete(byte[] key) {
        resetDbLock.readLock().lock();
        try {
            updateLastTimeUsed();
            if (logger.isTraceEnabled())
                logger.trace("~> LevelDbDataSource.delete(): {}, key: {}", name, Hex.toHexString(key));
            db.delete(key);
            if (logger.isTraceEnabled())
                logger.trace("<~ LevelDbDataSource.delete(): {}, key: {}", name, Hex.toHexString(key));
        } finally {
            resetDbLock.readLock().unlock();
        }
    }

    @Override
    public Set<byte[]> keys() {
        resetDbLock.readLock().lock();
        try {
            updateLastTimeUsed();
            if (logger.isTraceEnabled()) logger.trace("~> LevelDbDataSource.keys(): {}", name);
            JniDBFactory.pushMemoryPool(LDB_JNI_MEMORY_POOL_SIZE);
            try (DBIterator iterator = db.iterator()) {
                Set<byte[]> result = Stream.generate(iterator::next).map(Map.Entry::getKey).collect(Collectors.toSet());
                if (logger.isTraceEnabled()) logger.trace("<~ LevelDbDataSource.keys(): {}, {} ", name, result.size());
                return result;
            } catch (IOException e) {
                logger.error("Error retrieving Keys from LevelDBDataSource", e);
                panicProcessor.panic("leveldb", String.format("Unexpected while retrieving keys: %s", e.getMessage()));
                throw new RuntimeException(e);
            }
        } finally {
            JniDBFactory.popMemoryPool();
            resetDbLock.readLock().unlock();
        }
    }

    private void updateBatchInternal(Map<byte[], byte[]> rows) throws IOException {
        try (WriteBatch batch = db.createWriteBatch()) {
            for (Map.Entry<byte[], byte[]> entry : rows.entrySet()) {
                batch.put(entry.getKey(), entry.getValue());
            }
            db.write(batch);
        }
    }

    @Override
    public void updateBatch(Map<byte[], byte[]> rows) {
        resetDbLock.readLock().lock();
        try {
            updateLastTimeUsed();
            if (logger.isTraceEnabled()) logger.trace("~> LevelDbDataSource.updateBatch(): {}, {}", name, rows.size());
            JniDBFactory.pushMemoryPool(LDB_JNI_MEMORY_POOL_SIZE);
            try {
                updateBatchInternal(rows);
                if (logger.isTraceEnabled())
                    logger.trace("<~ LevelDbDataSource.updateBatch(): {}, {}", name, rows.size());
            } catch (IOException e) {
                logger.error("Failed to update batch", e);
                panicProcessor.panic("leveldb", String.format("Failed to update batch with error %s", e.getMessage()));
                throw new RuntimeException(e);
            }
        } finally {
            JniDBFactory.popMemoryPool();
            resetDbLock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        resetDbLock.writeLock().lock();
        try {
            if (!isAlive()) return;

            try {
                logger.debug("Close db: {}", name);
                db.close();
                this.db = null;
            } catch (IOException e) {
                logger.error("Failed to find the db file on the close: {} ", name);
                panicProcessor.panic("leveldb", String.format("Failed to find the db file on the close: %s", name));
            }
        } finally {
            resetDbLock.writeLock().unlock();
        }
    }

    public long getLastTimeUsed() {
        return this.lastTimeUsed;
    }

    private void updateLastTimeUsed() {
        this.lastTimeUsed = System.currentTimeMillis();
    }
}
