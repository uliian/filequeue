/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stimulussoft.filequeue;
import com.google.common.annotations.VisibleForTesting;
import com.stimulussoft.filequeue.processor.Consumer;
import com.stimulussoft.filequeue.processor.Expiration;
import com.stimulussoft.filequeue.processor.QueueProcessor;
import com.stimulussoft.util.AdjustableSemaphore;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * FileQueue is a fast and efficient persistent filequeue written in Java. FileQueue is backed by H2 Database MVStore.
 * To cater for situations where filequeue items could not be processed, it supports retry logic. As the filequeue is
 * persistent, if the program is quit and started again, it will begin where it left off. Refer to
 * <a href="https://github.com/stimulussoft/filequeue">filequeue github page</a> for more info.<br>
 * To see example, refer to com.stimulussoft.filequeue.FileQueueTest<br>
 * <p>
 * To attain higher levels of performance, File Queue will transfer queued items directly to consumers without hitting
 * the database provided there are consumers available. If all consumers are busy, File Queue will automatically
 * persist queued items to the database.
 * </p>
 * <p>
 * File Queue also offers both fixed and exponential back-off retry.
 * </p>
 * <p>
 * Implementation strategy:<br>
 * 1) Implement a Jackson serialization POJO by extending FileQueueItem<br>
 * 2) Implement a consumer class that extends Consumer<br>
 * b) implement consume(item) to perform actual processing work<br>
 * 3) Call config() to configure filequeue<br>
 * 4) Call startQueue(config) to start the filequeue<br>
 * 5) Call stopQueue() to stop the filequeue processing<br>
 * </p>
 * Example usage below:<br>
 *<pre>{@code
 * FileQueue queue = FileQueue.fileQueue();
 * FileQueue.Config config = FileQueue.config(queueName,queuePath,TestFileQueueItem.class, new TestConsumer())
 *                           .maxQueueSize(MAXQUEUESIZE)
 *                           .retryDelayAlgorithm(QueueProcessor.RetryDelayAlgorithm.EXPONENTIAL)
 *                           .retryDelay(RETRYDELAY).maxRetryDelay(MAXRETRYDELAY)
 *                           .maxRetries(0);
 *                           .persistRetryDelay(PERSISTENTRETRYDELAY);
 * queue.startQueue(config);
 * for (int i = 0; i < ROUNDS; i++)
 *     queue.queueItem(new TestFileQueueItem(i));
 * // when finished call stopQueue
 * queue.stopQueue();
 *}</pre>
 * <p>
 * To see example, refer to com.stimulussoft.filequeue.FileQueueTest<br>
 * </p>
 * <p>
 *  To see example, refer to com.stimulussoft.filequeue.FileQueueTest<br>
 * </p>
 * @author Jamie Band (Stimulus Software)
 * @author Valentin Popov (Stimulus Software)
 */

public final class FileQueue<T> {

    public enum RetryDelayAlgorithm { FIXED, EXPONENTIAL}
    private ShutdownHook shutdownHook;
    private final AtomicBoolean isStarted = new AtomicBoolean();
    private final AdjustableSemaphore permits = new AdjustableSemaphore();
    private QueueProcessor<T> transferQueue;
    private Config config;

    /**
     * Create @{@link FileQueue}.
     */

    public FileQueue() {
    }

    /**
     * Start the queue engine
     * @param config queue configuration. call config() to setup file queue configuration.
     * @throws IOException if error reading the db
     * @throws InterruptedException interruption due to shutdown
     * @throws IllegalArgumentException wrong arguments
     */

    public synchronized void startQueue(Config config) throws IOException, IllegalStateException, IllegalArgumentException, InterruptedException {
        if (isStarted.get()) throw new IllegalStateException("already started");
        this.config = config;
        transferQueue = config.builder.consumer(fileQueueConsumer).build();
        permits.setMaxPermits(config.maxQueueSize);
        for (int i = 0; i < transferQueue.size(); i++)
            if (!permits.tryAcquire()) break;
        shutdownHook = new ShutdownHook();
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        isStarted.set(true);
    }

    // this class is neeeded to release permit after processing an item

    private final Consumer<T> fileQueueConsumer = item -> {
        Consumer.Result result = config.getConsumer().consume(item);
        if (result != Consumer.Result.FAIL_REQUEUE)
            permits.release();
        return result;
    };


    /**
     * Get currently active configuration.
     * @return configuration
     */

    public Config getConfig() throws IllegalStateException {
        if (!isStarted.get()) throw new IllegalStateException("already started");
        return config;
    }

    /**
     * Stop the queue. Call this method when the queue engine must be shutdown.
     */

    public synchronized void stopQueue() {
        if (isStarted.compareAndSet(true, false)) {
            try {
                transferQueue.close();
            } finally {
                permits.release(permits.drainPermits());
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
        }
    }

    /**
     * <p>
     *  Queue configuration builder
     * </p>
     **/

    public static class Config<T> {

        private int maxQueueSize = Integer.MAX_VALUE;
        private Consumer consumer;

        private QueueProcessor.Builder builder = QueueProcessor.builder();

        /**
         * Rather use FileQueue.config(..) to build a new queue configuration.
         * @param queueName name of the queue. Any name can be chosen, so long as it is unique among queues.
         * @param queuePath writeable path where the queue database will be stored
         * @param type type of queue item
         * @param consumer callback to process items in the queue
         */

        public Config(String queueName, Path queuePath, Type type, Consumer consumer) {
            builder = builder.type(type).queueName(queueName).queuePath(queuePath);
            this.consumer = consumer;
        }

        public Config() { }

        /**
         * Queue path
         * @param queuePath             path to queue database
         * @return config configuration
         */
        public Config queuePath(Path queuePath) { builder = builder.queuePath(queuePath); return this; }
        public Path getQueuePath() { return builder.getQueuePath(); }

        /**
         * Queue name
         * @param queueName              friendly name for the queue
         * @return config configuration
         */
        public  Config queueName(String queueName) { builder = builder.queueName(queueName); return this; }
        public String getQueueName() { return builder.getQueueName(); }

        /**
         * Type of queue item
         * @param type                   filequeueitem type
         * @return config configuration
         */
        public Config type(Type type) throws IllegalArgumentException {
            if (type == FileQueueItem.class || !FileQueueItem.class.isAssignableFrom(type.getClass()))
                throw new IllegalArgumentException("type must be a subclass of filequeueitem");
            builder = builder.type(type); return this;
        }
        public Type getType() { return builder.getType(); }

        /**
         * Maximum number of tries. Set to zero for infinite.
         * @param maxTries               maximum number of retries
         * @return config configuration
         *
         */
        public  Config maxTries(int maxTries) {builder = builder.maxTries(maxTries); return this; }
        public int getMaxTries() { return builder.getMaxTries(); }

        /**
         * Set fixed delay between retries
         * @param retryDelay             delay between retries
         * @return config configuration
         */
        public  Config retryDelay(int retryDelay) { builder = builder.retryDelay(retryDelay); return this; }
        public int getRetryDelay() { return builder.getRetryDelay(); }

        /**
         * Set retry delay between retries from items in database (on disk)
         * @param retryDelay             delay between retries
         * @return config configuration
         */
        public  Config persistRetryDelay(int retryDelay) { builder = builder.persistRetryDelay(retryDelay); return this; }
        public int getPersistRetryDelay() { return builder.getPersistRetryDelay(); }

        /**
         * Set  persist retry delay time unit
         * @param persistRetryDelayUnit  persist retry delay timeunit
         * @return config configuration
         */
        public  Config persistRetryDelayUnit(TimeUnit persistRetryDelayUnit) { builder = builder.persistRetryDelayUnit(persistRetryDelayUnit); return this; }
        public TimeUnit getPersistRetryDelayUnit() { return builder.getPersistRetryDelayUnit(); }

        /**
         * Set maximum delay between retries assuming exponential backoff enabled
         * @param maxRetryDelay            maximum delay between retries
         * @return config configuration
         */
        public  Config maxRetryDelay(int maxRetryDelay) { builder = builder.maxRetryDelay(maxRetryDelay); return this; }
        public int getMaxRetryDelay() { return builder.getMaxRetryDelay(); }


        /**
         * Set retry delay time unit
         * @param retryDelayUnit           retry delay time unit
         * @return config configuration
         */
        public  Config retryDelayUnit(TimeUnit retryDelayUnit) { builder = builder.retryDelayUnit(retryDelayUnit); return this; }
        public TimeUnit getRetryDelayUnit() { return builder.getRetryDelayUnit(); }

        /**
         * Set retry delay algorithm (FIXED or EXPONENTIAL)
         * @param  retryDelayAlgorithm            set to either fixed or exponential backoff
         * @return config configuration
         */
        public Config retryDelayAlgorithm(RetryDelayAlgorithm retryDelayAlgorithm) {builder = builder.retryDelayAlgorithm(QueueProcessor.RetryDelayAlgorithm.valueOf(retryDelayAlgorithm.name())); return this; }
        public RetryDelayAlgorithm getRetryDelayAlgorithm() { return RetryDelayAlgorithm.valueOf(builder.getRetryDelayAlgorithm().name()); }

        /**
         * Set retry delay consumer
         * @param  consumer            retry delay consumer
         * @return config configuration
         */
        public Config consumer(Consumer<T> consumer) {
            this.consumer = consumer; return this;
        }

        public Consumer getConsumer() { return consumer; }

        /**
         * Set retry delay expiration
         * @param  expiration            retry delay expiration
         * @return config configuration
         */
        public  Config expiration(Expiration<T> expiration) {builder = builder.expiration(expiration); return this; }
        public Expiration getExpiration() { return builder.getExpiration(); }

        /**
         * Set max queue size
         * @param  maxQueueSize            maximum size of queue
         * @return config configuration
         */
        public  Config maxQueueSize(int maxQueueSize) { this.maxQueueSize = maxQueueSize; return this; }
        public int getMaxQueueSize() { return maxQueueSize; }

    }

    /**
     * Setup a file queue configuration for pass to startQueue()
     *  @param queueName   name of the queue
     *  @param queuePath   location of queue database
     *  @param type        type of filequeueitem
     *  @param consumer    consumer
     *  @return config configuration
     */

    public static  Config config(String queueName, Path queuePath, Class type, Consumer consumer) {
        return new Config<FileQueueItem>(queueName, queuePath, type, consumer);
    }

    /**
     * Queue item for delivery. Wait for an open slot for a specified time period.
     * Calls availableSlot when slot becomes available, immediately before queuing
     *
     * @param fileQueueItem   item for queuing
     * @param queueCallback   availableSlot method is executed when slot becomes available
     * @param acquireWait     time to wait before checking if shutdown has occurred
     * @param acquireWaitUnit time unit for acquireWait

     * @throws Exception   thrown if could not obtain an open slot (i.e. queue is full)
     */

    @VisibleForTesting
    public void queueItem(final T fileQueueItem, QueueCallback queueCallback, int acquireWait, TimeUnit acquireWaitUnit) throws Exception {
        acquirePermit(acquireWait, acquireWaitUnit);
        try {
            queueCallback.availableSlot(fileQueueItem);
            _queueItem(fileQueueItem);
        } catch (InterruptedException ie) {
            permits.release();
            throw ie;
        } catch (Exception io) {
            permits.release();
            throw io;
        }
    }

    /**
     * Queue item for delivery. Wait for an open slot for a specified period.
     *
     * @param fileQueueItem   item for queuing
     * @param acquireWait     time to wait before checking if shutdown has occurred
     * @param acquireWaitUnit time unit for acquireWait
     * @throws IOException   thrown if could not obtain an open slot (i.e. queue is full)
     * @throws InterruptedException queuing was interrupted due to shutdown
     */

        public void queueItem(final T fileQueueItem, int acquireWait, TimeUnit acquireWaitUnit) throws IOException, InterruptedException, IllegalArgumentException {
        acquirePermit(acquireWait, acquireWaitUnit);
        try {
            queueItem(fileQueueItem);
        } catch (Exception io) {
            permits.release();
            throw io;
        }
    }
    /**
     * Queue item for delivery (no blocking)
     *
     * @param fileQueueItem item for queuing
     * @throws IllegalArgumentException if the wrong arguments were supplied
     * @throws IOException if the item could not be serialized
     */

    public void queueItem(final T fileQueueItem) throws IOException, IllegalArgumentException, IllegalStateException {
        _queueItem(fileQueueItem);
    }

    /**
     * Private function for queue item for delivery (no blocking)
     *
     * @param fileQueueItem item for queuing
     * @throws IllegalArgumentException if the wrong arguments were supplied
     * @throws IOException if the item could not be serialized
     */

    private void _queueItem(final T fileQueueItem) throws IOException, IllegalArgumentException, IllegalStateException {

        if (fileQueueItem == null)
            throw new IllegalArgumentException("filequeue item cannot be null");

        if (!isStarted.get())
            throw new IllegalStateException("queue not started");
        
        try {
            transferQueue.submit(fileQueueItem);
            // mvstore throws a null ptr exception when out of disk space
        } catch (NullPointerException npe) {
            throw new IOException("not enough disk space");
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * Return filequeue size
     *
     * @return no items in the queue
     */

    public long getQueueSize() throws IllegalStateException {
        if (!isStarted.get()) throw new IllegalStateException("queue not started");
        return transferQueue.size();
    }

    /**
     * Set max queue size
     *
     * @param queueSize size of queue
     */

    public void setMaxQueueSize(int queueSize) {
        if (config != null)
            config = config.maxQueueSize(queueSize);
        permits.setMaxPermits(queueSize);
    }


    /**
     * Private function to acquire an open slot in the queue for processing.
     *
     * @param acquireWait     time to wait for acquisition of free slot
     * @param acquireWaitUnit time unit for acquireWait
     * @throws InterruptedException thrown if waiting was interrupted due to shutdown
     * @throws IOException thrown if could not acquire a free slot
     */
    private void acquirePermit(int acquireWait, TimeUnit acquireWaitUnit) throws IOException, InterruptedException {
        if (!isStarted.get())
            throw new IllegalStateException("queue not started");
            if (!permits.tryAcquire(acquireWait, acquireWaitUnit))
                throw new IOException("filequeue " + transferQueue.getQueuePath() + " is full. {maxQueueSize='" + config.maxQueueSize + "'}");
     }

    /**
     * Return no items in filequeue
     * @return no queued items
     */

    /**
     * Return no items in filequeue
     *
     * @return no queued items
     */

    public long getNoQueueItems() {
        return transferQueue.size();
    }

    class ShutdownHook extends Thread {

        @Override
        public void run() {
            shutdownHook = null;
            try { stopQueue(); } catch (IllegalStateException ignored) {}
        }
    }


    /**
     * Destroy processor thread pool. Call to explicitly shutdown all pools.
     *
     */

    public static void destroy() {
        QueueProcessor.destroy();
    }

    public static FileQueue<FileQueueItem> fileQueue() { return new FileQueue<>(); }


    protected int availablePermits() { return permits.availablePermits(); }
        }

