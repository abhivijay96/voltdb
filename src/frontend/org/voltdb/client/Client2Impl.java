/* This file is part of VoltDB.
 * Copyright (C) 2021 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.net.ssl.SSLEngine;
import javax.security.auth.Subject;

import com.google_voltpatches.common.net.HostAndPort;
import org.json_voltpatches.JSONException;
import org.json_voltpatches.JSONObject;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;

import org.voltcore.network.CipherExecutor;
import org.voltcore.network.Connection;
import org.voltcore.network.QueueMonitor;
import org.voltcore.network.VoltNetworkPool;
import org.voltcore.network.VoltProtocolHandler;
import org.voltdb.ClientResponseImpl;
import org.voltdb.VoltTable;
import org.voltdb.common.Constants;
import org.voltdb.utils.Encoder;
import org.voltcore.utils.Pair;
import org.voltcore.utils.ssl.SSLConfiguration;

/**
 * Client2Impl implements the so-called "version 2" client interface.
 * The overall intent is to provide an easier way to asynchronously
 * queue procedure calls.
 *
 * Requests are delegated to a per-connection servicing thread as
 * soon as possible. Other than use of Java synchronization primitives,
 * there is no blocking in the caller's thread.
 *
 * Public methods generally come in two flavours: the Async variety
 * returns a CompletableFuture to represent the in-progress request.
 * A Sync version waits on that future before returning.
 *
 * Allocation is intended to be via the ClientFactory class.
 * Application use is intended to be via the Client2 interface.
 */
public class Client2Impl implements Client2 {

    private final AtomicLong handleGenerator = new AtomicLong(0);
    private final AtomicLong sysHandleGenerator = new AtomicLong(0);
    private final static boolean tracing = false; // compile-time debug option
    private final static boolean datadump = false; // likewise (needs tracing true)

    // Internal-only exceptions, not exposed to client user
    private static class SerializationException extends Exception {
        SerializationException(String msg) {
            super(msg);
        }
    }

    private static class UnavailableException extends Exception {
        UnavailableException(String msg) {
            super(msg);
        }
    }

    ////////////////////////////////////////
    // INSTANCE DATA AND RELATED CLASSES
    ///////////////////////////////////////

    // Username and password as set from config
    private final String username;
    private final byte[] passwordHash;
    private final ClientAuthScheme hashScheme;
    private final Subject subject; // JAAS authentication subject

    // TLS/SSL context, null if not using TLS/SSL
    private final SslContext sslContext;
    private CipherExecutor cipherService;

    // Random number source, for arbitrary choices; not required to be secure
    private final Random randomizer = new Random();

    // Default request priorities (larger number is lower priority)
    // User-request priority affects connection queue order, and is sent to server.
    // System requests are not queued, so priority is only used by server.
    static final int DEFAULT_REQUEST_PRIORITY = ProcedureInvocation.LOWEST_PRIORITY / 2;
    static final int DEFAULT_SYSREQ_PRIORITY = ProcedureInvocation.HIGHEST_PRIORITY + 1;
    private int defaultRequestPriority = DEFAULT_REQUEST_PRIORITY; // Override via config
    private int systemRequestPriority = DEFAULT_SYSREQ_PRIORITY; // No override yet

    // Configurable timeouts; all are in nanoseconds. Constants that have
    // package access are used in Client2Config.
    static final long DEFAULT_CONNECTION_SETUP_TIMEOUT = TimeUnit.SECONDS.toNanos(30);
    static final long DEFAULT_CONNECTION_RESPONSE_TIMEOUT = TimeUnit.MINUTES.toNanos(2);
    static final long DEFAULT_PROCEDURE_TIMEOUT = TimeUnit.MINUTES.toNanos(2);
    private long connectTimeout = DEFAULT_CONNECTION_SETUP_TIMEOUT; // Override via config
    private long connectionResponseTimeout = DEFAULT_CONNECTION_RESPONSE_TIMEOUT; // Override via config
    private long procedureCallTimeout = DEFAULT_PROCEDURE_TIMEOUT; // Override via config
    private static final long MINIMUM_LONG_SYSPROC_TIMEOUT = TimeUnit.MINUTES.toNanos(30);
    private static final long ONE_SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);

    // Data for client affinity operation; constructed on topology updates
    private final AtomicReference<HashinatorLite> hashinator = new AtomicReference<>();
    private static final Map<Integer,ClientConnection> noPartitionLeaders = Collections.emptyMap();
    private final AtomicReference<Map<Integer,ClientConnection>> partitionLeaders = new AtomicReference<>(noPartitionLeaders);
    private final Map<Integer,ClientAffinityStats> clientAffinityStats = new HashMap<>();

    // Partition id/key map, used for all-partition calls.
    private static final Map<Integer,Integer> noPartitionKeys = Collections.emptyMap();
    private final AtomicReference<Map<Integer,Integer>> partitionKeys = new AtomicReference(noPartitionKeys);
    private final AtomicLong partitionKeysTimestamp = new AtomicLong(0); // millisecs since epoch
    private final List<Consumer<Throwable>> partitionKeysWaiters = new ArrayList<>();
    private final AtomicBoolean partitionKeysUpdateInProgress = new AtomicBoolean(false); // not merely 'pending'
    private static final long DEFAULT_PARTITION_KEYS_CACHE_REFRESH = TimeUnit.SECONDS.toNanos(1);
    private long partitionKeysCacheRefresh = DEFAULT_PARTITION_KEYS_CACHE_REFRESH; // No override yet

    // ProcInfo holds facts about a procedure itself
    private static class ProcInfo {
        final static int PARAMETER_NONE = -1;
        final boolean multiPart;
        final boolean readOnly;
        final int partitionParameter;
        final int parameterType;
        ProcInfo(boolean multiPart, boolean readOnly, int partitionParameter, int parameterType) {
            this.multiPart = multiPart;
            this.readOnly = readOnly;
            this.partitionParameter = multiPart ? PARAMETER_NONE : partitionParameter;
            this.parameterType = multiPart ? PARAMETER_NONE : parameterType;
        }
    }

    // Procedure name to ProcInfo; constructed from @SystemCatalog responses
    private static final Map<String,ProcInfo> noProcInfo = Collections.emptyMap();
    private final AtomicReference<Map<String,ProcInfo>> procInfoMap = new AtomicReference<>(noProcInfo);

    // Context for in-progress procedure calls
    private static class RequestContext {
        final static AtomicLong sequencer = new AtomicLong(0);
        final long sequence;
        final CompletableFuture<ClientResponse> future;
        final ProcedureInvocation invocation;
        final long startTime;
        final long timeout;
        final ClientConnection cxn;
        boolean holdsPermit;
        public RequestContext(CompletableFuture<ClientResponse> future,
                              ProcedureInvocation invocation, long timeout,
                              ClientConnection cxn) {
            this.sequence = sequencer.incrementAndGet();
            this.future = future;
            this.invocation = invocation;
            this.startTime = System.nanoTime();
            this.timeout = timeout;
            this.cxn = cxn;
        }
    }

    // Handle to in-progress call context, and hard limit on
    // the number we allow to be in progess. Warning and resume
    // levels affect feedback to application. Default values are
    // used in Client2Config.
    private final Map<Long,RequestContext> requestMap = new ConcurrentHashMap<>();
    final static int DEFAULT_REQUEST_HARD_LIMIT = 1000;
    final static int DEFAULT_REQUEST_WARNING_LEVEL = 800;
    final static int DEFAULT_REQUEST_RESUME_LEVEL = 200;
    private int requestHardLimit = DEFAULT_REQUEST_HARD_LIMIT; // Override via config or dynamically
    private int requestWarningLevel = DEFAULT_REQUEST_WARNING_LEVEL; // Override via config or dynamically
    private int requestResumeLevel= DEFAULT_REQUEST_RESUME_LEVEL; // Override via config or dynamically
    private final Object requestBackpressureLock = new Object();
    private volatile boolean requestBackpressureOn;

    // Outgoing flow control. Default value is used in Client2Config.
    final static int DEFAULT_TXN_OUT_LIMIT = 100;
    private final Semaphore sendPermits = new Semaphore(DEFAULT_TXN_OUT_LIMIT);
    private int outLimit = DEFAULT_TXN_OUT_LIMIT; // Override via config or dynamically

    // Per-connection network backpressure configuration
    final static int DEFAULT_BACKPRESSURE_QUEUE_LIMIT = 100;
    private int backpressureQueueLimit = DEFAULT_BACKPRESSURE_QUEUE_LIMIT; // Override via config

    // Comparator for priority ordering of requests. Uses request
    // sequence to ensure FIFO for equal-priority entries.
    private static class PrioOrder implements Comparator<RequestContext> {
        public int compare(RequestContext a, RequestContext b) {
            int cmp = Integer.compare(a.invocation.getRequestPriority(), b.invocation.getRequestPriority());
            if (cmp == 0) cmp = Long.compare(a.sequence, b.sequence);
            return cmp;
        }
    }
    private final static PrioOrder priorityOrder = new PrioOrder();

    // For development we (temporarily) can build with or without
    // priority queueing.
    private final static boolean USE_PRIORITY_QUEUE = true;
    private final static int INITIAL_QUEUE_CAPACITY = 1000;
    private static BlockingQueue<RequestContext> allocateQueue() {
        if (USE_PRIORITY_QUEUE) {
            return new PriorityBlockingQueue<>(INITIAL_QUEUE_CAPACITY, priorityOrder);
        }
        else {
            return new LinkedBlockingQueue<>();
        }
    }

    // Per-connection data, including queue of requests destined
    // to be sent on this connection by the worker thread. The need
    // to make this a VoltProtocolHandler means that a ClientConnection
    // is not merely plain old data, but as a design choice, most
    // actual processing occurs in Client2Impl methods.
    private class ClientConnection extends VoltProtocolHandler {
        private final BlockingQueue<RequestContext> pending = allocateQueue();
        private Thread worker;
        private Connection connection;
        private int hostId = -1;
        private final Object backpressureLock = new Object();
        private boolean backpressure;
        private final Map<String,ClientStats> stats = new ConcurrentHashMap<>();
        // Shared state
        volatile boolean connected;
        volatile long lastResponseTime;
        volatile boolean outstandingPing;

        void setConnection(Connection c, int i) {
            connection = c;
            hostId = i;
            connected = true;
        }

        Connection getConnection() {
            return connection;
        }

        boolean isConnected() {
            return connected;
        }

        void start() {
            String name = String.format("Client2-Worker-%d", connection.connectionId());
            worker = new Thread(workerGroup, () -> connectionWorker(ClientConnection.this), name);
            worker.start();
        }

        void enqueue(RequestContext req) {
            if (connected) {
                if (!pending.offer(req)) {
                    throw new IllegalStateException("Internal error: unbounded queue refused offer");
                }
            }
        }

        RequestContext dequeue() throws InterruptedException {
            return pending.take();
        }

        void clearQueue() {
            pending.clear();
        }

        void writeToNetwork(ByteBuffer buf) {
            connection.writeStream().enqueue(buf);
        }

        ClientStats clientStats(String procName) {
            ClientStats st = null;
            synchronized (stats) {
                st = stats.get(procName);
                if (st == null) {
                    st = new ClientStats();
                    st.m_connectionId = connectionId();
                    st.m_hostname = connection.getHostnameOrIP();
                    st.m_port = connection.getRemotePort();
                    st.m_procName = procName;
                    st.m_startTS = System.currentTimeMillis();
                    st.m_endTS = Long.MIN_VALUE;
                    stats.put(procName, st);
                }
            }
            return st;
        }

        @Override
        public void handleMessage(ByteBuffer buf, Connection conn) {
            lastResponseTime = System.nanoTime();
            responseService.submit(() -> handleResponse(ClientConnection.this, buf, lastResponseTime));
        }

        @Override
        public int getMaxRead() {
            return Integer.MAX_VALUE;
        }

        @Override
        public Runnable onBackPressure() {
            return new Runnable() {
                @Override
                public void run() {
                    networkBackpressure(ClientConnection.this, true);
                }
            };
        }

        @Override
        public Runnable offBackPressure() {
            return new Runnable() {
                @Override
                public void run() {
                    networkBackpressure(ClientConnection.this, false);
                }
            };
        }

        @Override
        public QueueMonitor writestreamMonitor() {
            return null;
        }

        @Override
        public void stopping(Connection conn) {
            super.stopping(conn);
            connected = false;
            worker.interrupt();
            removeConnection(this);
        }
    }

    // Connection database and related data.
    // The connection lock must be used when adding to/removing connections, since
    // there are actions beyond just updating the connection list. The copy-on-write
    // array means that iterators do not need a lock to see a consistent list.
    private final List<ClientConnection> connectionList = new CopyOnWriteArrayList<>();
    private final Map<Integer,ClientConnection> hostIdToConnection = new HashMap<>();
    private final Object connectionLock = new Object();
    private volatile int nextConnection = -1;
    private volatile String infoTablePortKey;
    private VoltNetworkPool networkPool;
    private final ThreadGroup workerGroup = new ThreadGroup("Client2-ConnectionWorkers");

    // Tracks active handles, for use by timeout handler. This is a clunky
    // way to get a 'ConcurrentHashSet', which Java does not have.
    private final ConcurrentHashMap.KeySetView<Long,Boolean> activeHandles =
        ConcurrentHashMap.newKeySet();

    // Tracks historical connections for when we have nothing
    // to ask for topo info. Protected by connectionLock.
    private final Set<HostAndPort> connectHistory = new HashSet<>();

    // Cluster identification papers; protected by connectionLock.
    private long clusterTimestamp;
    private int clusterLeader;
    private String clusterBuildString;

    // We subscribe to topology updates, generally on the first connection to
    // come up. Resubscription is required if that connection fails. We impose
    // a short delay before the first attempt (so that we're aware if other
    // connections are failing), and a longer delay between retries in case
    // the entire cluster is trying to recover.
    private ClientConnection subscribedConnection;
    private final AtomicBoolean subscriptionTaskPending = new AtomicBoolean(false);
    private static final long DEFAULT_RESUBSCRIPTION_DELAY = TimeUnit.SECONDS.toNanos(5);
    private static final long RESUBSCRIPTION_FAILURE_DELAY = TimeUnit.SECONDS.toNanos(120);
    private long resubscriptionDelay = DEFAULT_RESUBSCRIPTION_DELAY; // No override yet
    private long resubscriptionFailureDelay = RESUBSCRIPTION_FAILURE_DELAY; // No override yet

    // Sometimes we have to do our own refreshery, when a new connection comes
    // up at an arbitary time. We won't get notified of a topo change by the
    // cluster, since the cluster itself has not changed. This explicit request
    // drives our internal data-structure updates.
    private final AtomicBoolean topoRefreshTaskPending = new AtomicBoolean(false);
    private static final long DEFAULT_TOPO_REFRESH_DELAY = TimeUnit.SECONDS.toNanos(1);
    private static final long TOPO_REFRESH_FAILURE_DELAY = TimeUnit.SECONDS.toNanos(120);
    private long topoRefreshDelay = DEFAULT_TOPO_REFRESH_DELAY; // No override yet
    private long topoRefreshFailureDelay = TOPO_REFRESH_FAILURE_DELAY; // No override yet

    // Background connection task data
    private final AtomicBoolean connectionTaskPending = new AtomicBoolean(false);
    static final long DEFAULT_RECONNECT_DELAY = TimeUnit.SECONDS.toNanos(1);
    static final long DEFAULT_RECONNECT_RETRY_DELAY = TimeUnit.SECONDS.toNanos(15);
    private long reconnectDelay = DEFAULT_RECONNECT_DELAY; // Override via config
    private long reconnectRetryDelay = DEFAULT_RECONNECT_RETRY_DELAY; // Override via config

    // Enable automatic connection management: normally true. Set false
    // to disable: we won't automatically connect to hosts discovered through
    // topology updates, and we won't automatically reconnect failed
    // connections. Application assumes responsibility.
    private boolean autoConnectionMgmt = true; // Override via config

    // Magic handles for subscribed notfications; must match ClientInterface.
    // MAX_CLIENT_HANDLE is the highest handle number that is not magic.
    private static final long ASYNC_TOPO_HANDLE = Long.MAX_VALUE - 1;
    private static final long ASYNC_PROC_HANDLE = Long.MAX_VALUE - 2;
    private static final long MAX_CLIENT_HANDLE = Long.MAX_VALUE - 3;

    // Background executor used for timeout tasks and for
    // all system procedure calls issued by Client2 itself
    private ScheduledExecutorService execService =
        Executors.newSingleThreadScheduledExecutor((r) -> new Thread(r, "Client2-Exec"));

    // Response handler; this is used to handle responses from the network
    // so that we do not execute completions on network threads. Application
    // may provide an ExecutorService, or else we default it.
    static final int DEFAULT_RESPONSE_THREADS = 4;
    private int responseThreadCount = DEFAULT_RESPONSE_THREADS; // Override via config
    private ExecutorService responseService;
    private boolean stopResponseServiceAtShutdown;

    // Rate limiter: this artificially reduces the rate at which we issue
    // transactions to the server; not generally used in production.
    private final RateLimiter2 rateLimiter;

    // Registered notifications (set up in constructor and unchanged thereafter)
    private final Client2Notification.ConnectionStatus notificationConnectionUp;
    private final Client2Notification.ConnectionStatus notificationConnectionDown;
    private final Client2Notification.ConnectionStatus notificationConnectFailure;
    private final Client2Notification.LateResponse notificationLateResponse;
    private final Client2Notification.RequestBackpressure notificationRequestBackpressure;

    // Error logging. Unlike the 'real' notifications, this has a default
    // implementation, but we allow the app to intercept.
    private Client2Notification.ErrorLog errorLog = this::printError;

    // Client is shutting down
    private volatile boolean isShutdown;

    ////////////////////////////////////////
    // PUBLIC METHODS
    ///////////////////////////////////////

    /**
     * Constructor. All operational parameters are conveyed through
     * a Client2Config instance.
     */
    public Client2Impl(Client2Config config) {

        subject = config.subject; // may be null and usually is
        username = config.username != null ? config.username : "";

        hashScheme = config.hashScheme;
        if (config.cleartext) {
            String passwd = config.password != null ? config.password : "";
            passwordHash = ConnectionUtil.getHashedPassword(hashScheme, passwd);
        } else {
            passwordHash = Encoder.hexDecode(config.password);
        }

        if (config.enableSsl) {
            sslContext = SSLConfiguration.createClientSslContext(config.sslConfig);
            cipherService = CipherExecutor.CLIENT;
            cipherService.startup();
        } else {
            sslContext = null;
            cipherService = null;
        }

        if (config.txnPerSecRateLimit > 0) {
            rateLimiter = new RateLimiter2(config.txnPerSecRateLimit);
        }
        else {
            rateLimiter = null;
        }

        notificationConnectionUp = config.notificationConnectionUp;
        notificationConnectionDown = config.notificationConnectionDown;
        notificationConnectFailure = config.notificationConnectFailure;
        notificationLateResponse = config.notificationLateResponse;
        notificationRequestBackpressure = config.notificationRequestBackpressure;
        if (config.notificationErrorLog != null) {
            errorLog = config.notificationErrorLog;
        }

        if (config.responseExecutorService != null) {
            responseService = config.responseExecutorService;
            stopResponseServiceAtShutdown = config.stopResponseServiceOnClose;
        }
        else {
            final AtomicInteger respThreadNum = new AtomicInteger();
            responseThreadCount = config.responseThreadCount;
            responseService =
                Executors.newFixedThreadPool(responseThreadCount,
                                             (r) -> new Thread(r, "Client2-Response-" + respThreadNum.incrementAndGet()));
            stopResponseServiceAtShutdown = true;
        }

        networkPool = new VoltNetworkPool(1, 1, null, "Client2");
        networkPool.start();

        defaultRequestPriority = clipRequestPriority(config.requestPriority);
        connectTimeout = config.connectionSetupTimeout;
        procedureCallTimeout = config.procedureCallTimeout;
        connectionResponseTimeout = config.connectionResponseTimeout;
        setOutstandingTxnLimit(config.outstandingTxnLimit);
        setRequestLimits(config.requestHardLimit, config.requestWarningLevel, config.requestResumeLevel);
        backpressureQueueLimit = config.networkBackpressureLevel;
        execService.scheduleAtFixedRate(new TimeoutTask(), 1, 1, TimeUnit.SECONDS);

        reconnectDelay = config.reconnectDelay;
        reconnectRetryDelay = config.reconnectRetryDelay;
        autoConnectionMgmt = !config.disableConnectionMgmt;
    }

    /**
     * Configuration: set request limits.
     * Initially set from the Client2Config, but may be adjusted
     * dynamically if the application wishes to tune the value.
     */
    @Override
    public void setRequestLimits(int limit, int warning, int resume) {
        requestHardLimit = Math.max(limit, 1);
        requestWarningLevel = Math.min(Math.max(warning, 1), requestHardLimit);
        requestResumeLevel = Math.min(Math.max(resume, 0), requestWarningLevel);
    }

    /**
     * Returns an estimate of the number of requests queued
     * (via callProcedure) but not yet completed.
     */
    @Override
    public int currentRequestCount() {
        return requestMap.size();
    }

    /**
     * Configuration: set outstanding transaction limit.
     * Initially set from the Client2Config, but may be adjusted
     * dynamically if the application wishes to tune the value.
     * Attempting to reduce the limit below the current in-use
     * count will only reduce it by however many permits
     * are currently available, rather than blocking.
     * Returns actual new limit.
     */
    @Override
    public int setOutstandingTxnLimit(int limit) {
        int newLimit = Math.max(limit, 1);
        int delta = newLimit - outLimit;
        if (delta > 0) { // increasing
            sendPermits.release(delta);
        }
        else if (delta < 0) { // decreasing
            int drained = sendPermits.drainPermits();
            if (-delta < drained) { // drained more than we need
                sendPermits.release(drained + delta);
            }
            else if (-delta > drained) { //  did not drain enough
                newLimit = outLimit - drained;
            }
        }
        if (tracing) {
            trace("Outstanding txn limit %d, available permits %d",
                  newLimit, sendPermits.availablePermits());
        }
        outLimit = newLimit;
        return newLimit;
    }

    /**
     * Returns an estimate of the number of outstanding
     * transactions. This is only useful for debugging.
     */
    @Override
    public int outstandingTxnCount() {
        return outLimit - sendPermits.availablePermits();
    }

    /**
     * Connect to specified server string, in host:port form.
     * Host can be IPv6, IPv4, or hostname. If IPv6, it must be
     * enclosed in brackets. Port specification is optional.
     * A single attempt will be made; no retries.
     */
    @Override
    public void connectSync(String server)
    throws IOException {
        HostAndPort hp = HostAndPort.fromString(server.trim())
                                    .withDefaultPort(21212)
                                    .requireBracketsForIPv6();
        connectSync(hp.getHost(), hp.getPort());
    }

    /**
     * Connect to specified host on specified port.
     * A single attempt will be made; no retries.
     */
    @Override
    public void connectSync(String host, int port)
    throws IOException {
        toSyncReturn(doConnect(host, port, 0, 0));
    }

    /**
     * Connect to specified server string, in host:port form.
     * Retries are supported. with user-supplied overall
     * timeout. The operation will complete asynchronously,
     */
    @Override
    public CompletableFuture<Void> connectAsync(String server, long timeout, long delay, TimeUnit unit) {
        HostAndPort hp = HostAndPort.fromString(server.trim())
                                    .withDefaultPort(21212)
                                    .requireBracketsForIPv6();
        return connectAsync(hp.getHost(), hp.getPort(), timeout, delay, unit);
    }

    /**
     * Connect to specified host on specified port.
     * Retries are supported. with user-supplied overall
     * timeout. The operation will complete asynchronously,
     */
    @Override
    public CompletableFuture<Void> connectAsync(String host, int port, long timeout, long delay, TimeUnit unit) {
        long tmoNs = unit.toNanos(Math.max(0, timeout));
        long delayNs = unit.toNanos(Math.max(0, delay));
        return doConnect(host, port, tmoNs, delayNs);
    }

    /**
     * Gets a list of currently-connected hosts.
     */
    @Override
    public List<InetSocketAddress> connectedHosts() {
        List<InetSocketAddress> list = new ArrayList<>();
        for (ClientConnection cxn : connectionList) {
            list.add(cxn.connection.getRemoteSocketAddress());
        }
        return list;
    }

    /**
     * Return various facts about the most-recently
     * connected cluster
     */
    @Override
    public String clusterBuildString() {
        return clusterBuildString;
    }

    @Override
    public Object[] clusterInstanceId() {
        return new Object[] { clusterTimestamp, clusterLeader };
    }

    /**
     * Call stored procedure with configured values for timeouts and priority
     */
    @Override
    public CompletableFuture<ClientResponse> callProcedureAsync(String procName, Object... parameters) {
        return doProcCall(procedureCallTimeout, BatchTimeoutOverrideType.NO_TIMEOUT,
                          ProcedureInvocation.NO_PARTITION, defaultRequestPriority,
                          procName, parameters);
    }

    @Override
    public ClientResponse callProcedureSync(String procName, Object... parameters)
    throws IOException, ProcCallException {
        return toSyncProcCall(callProcedureAsync(procName, parameters));
    }

    /**
     * Call stored procedure with optional overrides for timeouts and priority
     */
    @Override
    public CompletableFuture<ClientResponse> callProcedureAsync(Client2CallOptions options, String procName, Object... parameters) {
        long clientTmo = procedureCallTimeout;
        long batchTmo = BatchTimeoutOverrideType.NO_TIMEOUT;
        int reqPrio = defaultRequestPriority;
        if (options != null) {
            if (options.clientTimeout != null) clientTmo = options.clientTimeout;
            if (options.batchTimeout != null) batchTmo = options.batchTimeout;
            if (options.requestPriority != null) reqPrio = clipRequestPriority(options.requestPriority);
        }
        return doProcCall(clientTmo, batchTmo, ProcedureInvocation.NO_PARTITION, reqPrio, procName, parameters);
    }

    @Override
    public ClientResponse callProcedureSync(Client2CallOptions options, String procName, Object... parameters)
    throws IOException, ProcCallException {
        return toSyncProcCall(callProcedureAsync(options, procName, parameters));
    }

    /**
     * Call an all-partition stored procedure.
     */
    @Override
    public CompletableFuture<ClientResponseWithPartitionKey[]> callAllPartitionProcedureAsync(Client2CallOptions options, String procName, Object... parameters) {
        long clientTmo = procedureCallTimeout;
        long batchTmo = BatchTimeoutOverrideType.NO_TIMEOUT;
        int reqPrio = defaultRequestPriority;
        if (options != null) {
            if (options.clientTimeout != null) clientTmo = options.clientTimeout;
            if (options.batchTimeout != null) batchTmo = options.batchTimeout;
            if (options.requestPriority != null) reqPrio = clipRequestPriority(options.requestPriority);
        }
        return doAllPartitionCall(clientTmo, batchTmo, reqPrio, procName, parameters);
    }

    @Override
    public ClientResponseWithPartitionKey[] callAllPartitionProcedureSync(Client2CallOptions options, String procName, Object... parameters)
    throws IOException {
        return toSyncAllPartCall(callAllPartitionProcedureAsync(options, procName, parameters));
    }

    /**
     * Drain all transactions. This is inherently synchronous.
     */
    @Override
    public void drain()
    throws InterruptedException {
        doDrainRequests();
    }

    /**
     * Shut down client. This is inherently synchronous.
     */
    @Override
    public void close() {
        try {
            doClientShutdown();
            ClientFactory.decreaseClientNum();
        }
        catch (InterruptedException ex) {
            //ignore
        }
    }

    /**
     * Statistics support.
     */
    @Override
    public ClientStatsContext createStatsContext() {
        return new ClientStatsContext(this, getStatsSnapshot(), getIOStatsSnapshot(), getAffinityStatsSnapshot());
    }

    ////////////////////////////////////////
    // IMPLEMENTATION
    ///////////////////////////////////////

    /*
     * Internal implementation of connection establishment.
     * Executes the actual connection setup in a separate thread,
     * since the underlying routines are synchronous. Retry
     * is supported, with provision for approximate timeout.
     */
    private CompletableFuture<Void> doConnect(String host, int port, long timeout, long delay) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        execService.schedule(new UserConnectionTask(host, port, timeout, delay, future), 0, TimeUnit.NANOSECONDS);
        return future;
    }

    private class UserConnectionTask implements Runnable {
        private final String host;
        private final int port;
        private final long startTime;
        private final long timeout;
        private final long retryDelay;
        private final CompletableFuture<Void> future;

        UserConnectionTask(String h, int p, long t, long d, CompletableFuture<Void> f) {
            host = h;
            port = p;
            startTime = System.nanoTime();
            timeout = t;
            retryDelay = d;
            future = f;
        }

        @Override
        public void run() {
            boolean retry = false;
            Exception fail = null;
            try {
                createConnection(host, port);
                future.complete(null);
                return;
            }
            catch (SocketException | UnknownHostException ex) {
                fail = ex;
                retry = (System.nanoTime() - startTime < timeout);
            }
            catch (Exception ex) {
                fail = ex;
            }
            HostAndPort hap = HostAndPort.fromParts(host, port);
            if (retry) {
                logError("Failed to connect to host at %s: %s", hap, fail.getMessage());
                execService.schedule(this, retryDelay, TimeUnit.NANOSECONDS);
            }
            else {
                logError("Failed to connect to host at %s: %s (no retry)", hap, fail.getMessage());
                future.completeExceptionally(fail);
            }
        }
    }

    /*
     * Create connection and associated data structures. Used for
     * all connections: user-initiated, topology changes, and
     * recovery from lost connections.
     */
    private void createConnection(String host, int port) throws IOException {

        // Shutdown check
        if (isShutdown) {
            throw new IllegalStateException("shutting down");
        }

        // Using TLS/SSL?
        SSLEngine sslEngine = null;
        if (sslContext != null) {
            sslEngine = sslContext.newEngine(ByteBufAllocator.DEFAULT, host, port);
        }

        // Create connection; no synchronization for this possibly-lengthy part
        ClientConnection cxn = new ClientConnection();
        SocketChannel channel = null;
        int hostId, leaderAddr;
        long timestamp;
        String buildStr;
        try {
            Object[] connectResults = ConnectionUtil.getAuthenticatedConnection(host, username, passwordHash, port,
                                                                                subject, hashScheme, sslEngine,
                                                                                TimeUnit.NANOSECONDS.toMillis(connectTimeout));
            channel = (SocketChannel)connectResults[0];
            long[] instanceId = (long[])connectResults[1];
            hostId = (int)instanceId[0];
            timestamp = instanceId[2];
            leaderAddr = (int)instanceId[3];
            buildStr = (String)connectResults[2];

            Connection c = networkPool.registerChannel(channel, cxn, cipherService, sslEngine);
            c.writeStream().setPendingWriteBackpressureThreshold(backpressureQueueLimit);
            cxn.setConnection(c, hostId);
        }
        catch (IOException | RuntimeException ex) {
            closeChannel(channel);
            notifyConnectFailure(host, port);
            throw ex;
        }

        // Absorb new connection into our data. Synchronization is
        // needed for the cluster identity check.
        IOException fail = null;
        synchronized (connectionLock) {
            if (connectionList.size() == 0 || clusterTimestamp == 0) {
                clusterTimestamp = timestamp;
                clusterLeader = leaderAddr;
                clusterBuildString = buildStr;
                connectHistory.clear();
            }
            else if (clusterTimestamp != timestamp || clusterLeader != leaderAddr) {
                String msg = String.format("Cluster instance id mismatch: current is %d,%d, server's is %d,%d",
                                           clusterTimestamp, clusterLeader, timestamp, leaderAddr);
                fail = new IOException(msg);
            }
            if (fail == null) {
                if (hostIdToConnection.put(hostId, cxn) != null) {
                    logError("Warning: replaced connection for host id %d", hostId);
                }
                connectHistory.add(HostAndPort.fromParts(host, port));
                connectionList.add(cxn); // remember: copy-on-write array
                cxn.start();
                if (!ensureSubscription(0)) {
                    refreshTopology(topoRefreshDelay);
                }
            }
        }

        // Either way, it's complete. This is intentionally outside
        // the sync block, since application code may immediately run.
        if (fail == null) {
            notifyConnectionUp(cxn);
        }
        else {
            cxn.getConnection().unregister();
            notifyConnectFailure(host, port);
            throw fail;
        }
    }

    /*
     * Close channel, ignore failure
     */
    private void closeChannel(SocketChannel channel) {
        try {
            if (channel != null) {
                channel.close();
            }
        }
        catch (IOException ex) {
            // Don't care
        }
    }

    /*
     * Map host id to connection object. Requires connection lock
     * to avoid concurrent modifications. This map is only looked
     * at during topo change processing, it's not highly active.
     */
    private ClientConnection getConnectionForHost(int id) {
        synchronized (connectionLock) {
            return hostIdToConnection.get(id);
        }
    }

    /*
     * Removes a connection object from the system when it is being
     * disconnected. Requires connection lock to make sure that
     * various data remain consistent, even though the connection
     * list itself permits concurrent modification.
     */
    private void removeConnection(ClientConnection cxn) {
        notifyConnectionDown(cxn);
        synchronized (connectionLock) {
            connectionList.remove(cxn);
            hostIdToConnection.remove(cxn.hostId);

            Iterator<Map.Entry<Integer,ClientConnection>> it = partitionLeaders.get().entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() == cxn) {
                    it.remove();
                }
            }

            if (connectionList.isEmpty()) {
                subscribedConnection = null;
                scheduleFirstConnection(connectHistory, reconnectDelay);
            }
            else if (subscribedConnection == cxn) {
                subscribedConnection = null;
                ensureSubscription(resubscriptionDelay);
            }
        }

        for (RequestContext req : requestMap.values()) {
            if (req.cxn == cxn) {
                completeRequestOnHostDown(req);
            }
        }
    }

    /*
     * Connection-related notification routines. Must not be
     * called with any locks (synchronization) held.
     */
    private void notifyConnectFailure(String host, int port) {
        if (tracing || notificationConnectFailure != null) {
            trace("Connect failed: %s port %d", host, port);
            notifyConnectionEvent(notificationConnectFailure, host, port);
        }
    }

    private void notifyConnectionUp(ClientConnection cxn) {
        if (tracing || notificationConnectionUp != null) {
            String host = cxn.connection.getHostnameOrIP();
            int port = cxn.connection.getRemotePort();
            trace("Connection up: %s port %d", host, port);
            notifyConnectionEvent(notificationConnectionUp, host, port);
        }
    }

    private void notifyConnectionDown(ClientConnection cxn) {
        if (tracing || notificationConnectionDown != null) {
            String host = cxn.connection.getHostnameOrIP();
            int port = cxn.connection.getRemotePort();
            trace("Connection down: %s port %d", host, port);
            notifyConnectionEvent(notificationConnectionDown, host, port);
        }
    }

    private void notifyConnectionEvent(Client2Notification.ConnectionStatus notification,
                                       String host, int port) {
        if (notification != null) {
            try {
                notification.accept(host, port);
            }
            catch (Exception ex) {
                logError("Unhandled exception from notification handler: " + ex);
            }
        }
    }

    /*
     * Internal implementation of procedure call. All optional arguments have
     * been resolved at this point. All time intervals are in nanoseconds.
     * The destination partition is usually NO_PARTITION, allowing it to
     * be determined from procedure parameters where appropriate.
     */
    private CompletableFuture<ClientResponse> doProcCall(long clientTimeout, long batchTimeout, int destinationPartition,
                                                         int requestPrio, String procName, Object... params) {
        CompletableFuture<ClientResponse> future = new CompletableFuture<>();

        // Shutdown check
        if (isShutdown) {
            future.completeExceptionally(new IllegalStateException("shutting down"));
            return future;
        }

        // Check procedure name is set, since we rely on this elsewhere
        if (procName == null || procName.isEmpty()) {
            future.completeExceptionally(new IllegalArgumentException("Procedure name required"));
            return future;
        }

        // Hard limit on total number of requests, whether awaiting send or actually sent.
        // This races with insertions and removals; that's ok.
        int requestCount = requestMap.size();
        if (requestCount >= requestHardLimit) {
            String msg = String.format("In-progress request limit %d exceeded", requestHardLimit);
            future.completeExceptionally(new RequestLimitException(msg));
            return future;
        }

        // Locate the appropriate connection
        long handle = handleGenerator.incrementAndGet();
        int batchTmoMs = (int)(batchTimeout > 0 ? TimeUnit.NANOSECONDS.toMillis(batchTimeout) : batchTimeout);
        ProcedureInvocation invocation = new ProcedureInvocation(handle, batchTmoMs, destinationPartition,
                                                                 requestPrio, procName, params);
        ClientConnection cxn = findConnection(invocation);
        if (cxn == null) {
            completeUnqueuedRequest(future, handle, "No connections to cluster at this time");
            return future;
        }

        // Save pending request context
        RequestContext reqCtx = new RequestContext(future, invocation, clientTimeout, cxn);
        requestMap.put(handle, reqCtx);

        // Are we entering the yellow zone for the request map level? Trigger a
        // warning. Slightly racy with respect to the actual level, but ok.
        if (requestCount+1 >= requestWarningLevel && !requestBackpressureOn) {
            reportRequestBackpressure(true);
        }

        // And queue the request to the selected connection
        cxn.enqueue(reqCtx);
        return future;
    }

    /*
     * Notify application of entry into/exit from yellow zone (i.e., nearing
     * the hard limit) for pending requests. It is vital for correct sequencing
     * that a lock is held across the notification, otherwise the resume can
     * overtake the warning.
     */
    private void reportRequestBackpressure(boolean slowdown) {
        if (notificationRequestBackpressure != null) {
            int count = requestMap.size();
            boolean trigger = (slowdown ? count >= requestWarningLevel : count <= requestResumeLevel);
            if (trigger) {
                synchronized (requestBackpressureLock) {
                    if (slowdown ^ requestBackpressureOn) {
                        requestBackpressureOn = slowdown;
                        try {
                            notificationRequestBackpressure.accept(slowdown);
                        }
                        catch (Exception ex) {
                            logError("Unhandled exception from notification handler: " + ex);
                        }
                    }
                }
            }
        }
    }

    /*
     * Execute a procedure call on all known partitions, returning a separate response
     * for each instance of the call (after the entire set has completed).
     *
     * Execution starts by requesting an up-to-date list of knpwn partitions.
     */
    private CompletableFuture<ClientResponseWithPartitionKey[]> doAllPartitionCall(long clientTimeout, long batchTimeout,
                                                                                   int requestPrio,
                                                                                   String procName, Object... params) {
        AllPartitionCallContext context = new AllPartitionCallContext(clientTimeout, batchTimeout, requestPrio, procName, params);
        if (isShutdown) {
            context.future.completeExceptionally(new IllegalStateException("shutting down"));
        }
        else {
            refreshPartitionKeys((th) -> {
                    if (th != null) {
                        context.future.completeExceptionally(th);
                    }
                    else {
                        doAllPartitionCall(context);
                    }});
        }
        return context.future;
    }

    private class AllPartitionCallContext {
        final CompletableFuture<ClientResponseWithPartitionKey[]> future;
        final long clientTimeout;
        final long batchTimeout;
        final int requestPrio;
        final String procName;
        final Object[] params;
        AllPartitionCallContext(long ct, long bt, int rp, String proc, Object[] pars) {
            future = new CompletableFuture<ClientResponseWithPartitionKey[]>();
            clientTimeout = ct;
            batchTimeout = bt;
            requestPrio = rp;
            procName = proc;
            params = pars;
        }
    }

    private void doAllPartitionCall(AllPartitionCallContext context) {
        Object[] args = new Object[context.params.length + 1];
        System.arraycopy(context.params, 0, args, 1, context.params.length);

        Map<Integer,Integer> idToKey = partitionKeys.get();
        ClientResponseWithPartitionKey[] responses = new ClientResponseWithPartitionKey[idToKey.size()];
        AtomicInteger count = new AtomicInteger(idToKey.size());
        int index = 0;
        for (Map.Entry<Integer,Integer> ent : idToKey.entrySet()) {
            Integer partitionId = ent.getKey();
            Integer partitionKey = ent.getValue();
            args[0] = partitionKey; // this is safe: the args are synchronously copied in to a ProcedureInvocation
            final int thisIndex = index;
            doProcCall(context.clientTimeout, context.batchTimeout, partitionId, context.requestPrio, context.procName, args)
                .whenComplete((resp, th) -> onePartitionComplete(context.future, responses, thisIndex, count, partitionKey, resp, th));
            index++;
        }
    }

    private void onePartitionComplete(CompletableFuture<ClientResponseWithPartitionKey[]> future,
                                      ClientResponseWithPartitionKey[] respArray, int index,
                                      AtomicInteger count, Integer key,
                                      ClientResponse resp, Throwable th) {
        if (resp == null) {
            String err = null;
            if (th != null) err = th.getMessage();
            if (err == null) err = "unspecified error";
            resp = new ClientResponseImpl(ClientResponse.UNEXPECTED_FAILURE, new VoltTable[0], err);
        }
        respArray[index] = new ClientResponseWithPartitionKey(key, resp);
        if (count.decrementAndGet() == 0) {
            future.complete(respArray);
        }
    }

    /*
     * Waits until there are no more requests in the system.
     * It is the client app's responsibility to not queue any
     * more procedure calls while draining.
     */
    private void doDrainRequests() throws InterruptedException {
        int sleep = 500_000; // start with 500 uSec sleep
        final int incSleep = 500_000; // increase by 500 uSec at a time
        final long maxSleep = 5_000_000; // but no more than 5 mSec
        while (!requestMap.isEmpty()) {
            LockSupport.parkNanos(sleep);
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted in drain");
            }
            if (sleep < maxSleep) {
                sleep += incSleep;
            }
        }
    }

    /*
     * Check for known tasks that are queued pending execution (which
     * state is of course dynamically changing while we're looking at it).
     * Does not imply waiting for running tasks to finish execution.
     */
    private void doDrainTasks() throws InterruptedException {
        int sleep = 500_000; // start with 500 uSec sleep
        final int incSleep = 500_000; // increase by 500 uSec at a time
        final long maxSleep = 5_000_000; // but no more than 5 mSec
        while (subscriptionTaskPending.get() ||
               topoRefreshTaskPending.get() ||
               connectionTaskPending.get()) {
            LockSupport.parkNanos(sleep);
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted in drain");
            }
        }
    }

    /**
     * Shut down the client, closing all network connections and
     * releasing all resources.
     */
     public void doClientShutdown() {
        isShutdown = true;

        try {
            doDrainTasks();
            doDrainRequests();
        }
        catch (InterruptedException ex) {
            // ignore
        }

        if (execService != null) {
            stopService(execService);
            execService = null;
        }

        if (networkPool != null) {
            try {
                networkPool.shutdown();
            }
            catch (InterruptedException ex) {
                // ignore
            }
            networkPool = null;
        }

        if (responseService != null) {
            if (stopResponseServiceAtShutdown) {
                stopService(responseService);
            }
            responseService = null;
        }

        hashinator.set(null);
        partitionLeaders.set(noPartitionLeaders);
        partitionKeys.set(noPartitionKeys);
        clientAffinityStats.clear();
        procInfoMap.set(noProcInfo);

        if (cipherService != null) {
            cipherService.shutdown();
            cipherService = null;
        }

        activeHandles.clear();
        requestMap.clear();
        connectionList.clear();
        hostIdToConnection.clear();
        connectHistory.clear();
        subscribedConnection = null;
    }

    private void stopService(ExecutorService service) {
        service.shutdown();
        try {
            if (!service.awaitTermination(10, TimeUnit.SECONDS)) {
                service.shutdownNow();
            }
        }
        catch (InterruptedException ex) {
            service.shutdownNow();
        }
    }

    /*
     * Per-connection queue processing. Loops dequeueing requests, ultimately
     * forwarding them to network code. May block on acquiring a send permit or
     * on network backpressure. Request timeouts are honoured while blocking.
     */
    private void connectionWorker(ClientConnection cxn) {
        while (cxn.isConnected()) {
            RequestContext req = null;
            try {
                req = cxn.dequeue();
                if (rateLimiter != null) {
                    rateLimiter.limitSendRate();
                }
                ByteBuffer buf = serializeInvocation(req.invocation);
                req.holdsPermit = sendPermits.tryAcquire();
                if (!req.holdsPermit) {
                    req.holdsPermit = sendPermits.tryAcquire(remainingTime(req.startTime, req.timeout), TimeUnit.NANOSECONDS);
                    if (!req.holdsPermit) {
                        completeRequestOnLocalFailure(req, true, "Procedure call timed out before sending");
                        continue;
                    }
                }
                if (!clearToSend(cxn, req.startTime, req.timeout)) {
                    completeRequestOnLocalFailure(req, true, "Procedure call timed out before sending");
                    continue;
                }
                activeHandles.add(req.invocation.getHandle());
                if (req.timeout < ONE_SECOND_NANOS && shortTimeoutExpired(req)) {
                    completeRequestOnLocalFailure(req, true, "Procedure call timed out before sending");
                    continue;
                }
                cxn.writeToNetwork(buf);
            }
            catch (SerializationException ex) {
                completeRequestOnLocalFailure(req, false, ex.getMessage());
            }
            catch (InterruptedException ex) {
                // Probably stopping; dispose of current request then loop to check
                if (req != null) {
                    completeRequestOnLocalFailure(req, false, "interrupted");
                }
            }
            catch (Exception ex) {
                String msg = String.format("Unexpected exception in sender: %s", ex.getMessage());
                logError(msg);
                completeRequestOnLocalFailure(req, false, msg);
            }
        }
        cxn.clearQueue();
    }

    /*
     * Considerations for sub-second timeouts. Checks whether we already
     * timed out, and if not, queues up a task to handle this specific request.
     */
    private boolean shortTimeoutExpired(RequestContext req) {
        boolean expired = false;
        if (!isLongOp(req.invocation.getProcName())) {
            long remaining = remainingTime(req.startTime, req.timeout);
            expired = remaining <= 0;
            if (!expired) {
                execService.schedule(new SingleTimeoutTask(req), remaining, TimeUnit.NANOSECONDS);
            }
        }
        return expired;
    }

    /*
     * Release permit if held by request
     */
    private void releasePermit(RequestContext req) {
        if (req.holdsPermit) {
            req.holdsPermit = false;
            sendPermits.release();
        }
    }

    /*
     * Await no backpressure on a particular connection
     */
    private boolean clearToSend(ClientConnection cxn, long startTime, long timeout) throws InterruptedException {
        synchronized (cxn.backpressureLock) {
            while (cxn.backpressure) {
                long remaining = remainingTime(startTime, timeout);
                if (remaining <= 0) {
                    return false;
                }
                cxn.backpressureLock.wait(remaining / 1_000_000, (int)(remaining % 1_000_000));
            }
        }
        return true;
    }

    /*
     * Network backpressure reporting (from network layer to us).
     * Distinct from request backpressure handling (between us and application).
     */
    private void networkBackpressure(ClientConnection cxn, boolean state) {
        synchronized (cxn.backpressureLock) {
            cxn.backpressure = state;
            if (!state) {
                cxn.backpressureLock.notifyAll();
            }
        }
    }

    /*
     * Response processing (executing in a "response thread" owned
     * by this API)
     */
    private void handleResponse(ClientConnection cxn, ByteBuffer buf, long endTime) {
        try {
            handleResponseImpl(cxn, buf, endTime);
        }
        catch (Exception ex) {
            logError("Unhandled exception in response processing: %s", ex);
            // Amongst the reasons we can get here are:
            // - problems parsing the response buffer
            // - exceptions from execution of completion stages
            // - exceotions from execution of notifications
            // Unsafe to do anything else except to log it.
        }
    }

    private void handleResponseImpl(ClientConnection cxn, ByteBuffer buf, long endTime)
    throws IOException {
        ClientResponseImpl response = new ClientResponseImpl();
        response.initFromBuffer(buf);

        // Race with expiration thread to be the first to remove the
        // request from the map and process it
        long handle = response.getClientHandle();
        RequestContext context = removeRequest(handle);

        // We distinguish the normal client case by handle value
        if (handle >= 0 && handle <= MAX_CLIENT_HANDLE) {

            // Standard case, response arrives before timeout
            if (context != null) {
                sendPermits.release();

                long elapsedTime = Math.max(endTime - context.startTime, 1);
                response.setClientRoundtrip(elapsedTime);
                int clusterRTT = response.getClusterRoundtrip(); // msec

                boolean abort = false,  fail = false;
                switch (response.getStatus()) {
                case ClientResponse.SUCCESS:
                    break;
                case ClientResponse.USER_ABORT:
                case ClientResponse.GRACEFUL_FAILURE:
                case ClientResponse.UNSUPPORTED_DYNAMIC_CHANGE:
                    abort = true;
                    break;
                default:
                    fail = true;
                    break;
                }

                String procName = context.invocation.getProcName();
                context.cxn.clientStats(procName).update(elapsedTime, clusterRTT, abort, fail, false);
                if (tracing && abort|fail) {
                    trace("Procedure %s failed, %d", procName, response.getStatus());
                }
                context.future.complete(response);
            }

            // Assumed to be a late response for something we already timed out.
            // Note we already released the permit, so don't do it here.
            else {
                notifyLateResponse(response, cxn);
            }
        }

        // Internally-generated requests have negative handle numbers
        else if (handle < 0) {
            if (context != null) {
                context.future.complete(response);
            } else {
                logError("Late response to system procedure call");
            }
        }

        // Maybe it's a notification we subscribed to?
        // These are identified by magic handle numbers.
        else if (handle == ASYNC_TOPO_HANDLE) {
            if (tracing) {
                trace("Received notification of topology change");
            }
            topoStatsCompletion(response, null);
        }

        else if (handle == ASYNC_PROC_HANDLE) {
            if (tracing) {
                trace("Received notification of catalog change");
            }
            procedureCatalogCompletion(response, null);
        }

        // No idea what this is. It'd have to be a large positive handle
        // number (greater than MAX_CLIENT_HANDLE) but not one of the
        // known magic numbers.
        else {
            logError("Received notification with unexpected handle %d: ignored", handle);
        }

        // Can we now exit from request backpressure?
        if (requestBackpressureOn) {
            reportRequestBackpressure(false);
        }
    }

    /*
     * Common routine to remove a pending RequestContext from the
     * maps of such contexts, prior to completion.
     */
    private RequestContext removeRequest(long handle) {
        activeHandles.remove(handle); // if present
        return requestMap.remove(handle);
    }

    /*
     * Notify application of late response. Already executing
     * on a response thread.
     */
    private void notifyLateResponse(ClientResponse resp, ClientConnection cxn) {
        if (tracing || notificationLateResponse != null) {
            String host = cxn.connection.getHostnameOrIP();
            int port = cxn.connection.getRemotePort();
            int status = resp.getStatus();
            if (tracing) {
                trace("Late response received from %s port %d with status %d", host, port, status);
            }
            if (notificationLateResponse != null) {
                try {
                    notificationLateResponse.accept(resp, host, port);
                }
                catch (Exception ex) {
                    logError("Unhandled exception from notification handler: " + ex);
                }
            }
        }
    }

    /*
     * Handles periodic timeouts for procedure calls: runs once a
     * second to check on outstanding calls. Sends pings to keep the
     * connection alive.
     */
    private class TimeoutTask implements Runnable {
        @Override
        public void run() {
            try {
                long now = System.nanoTime();

                // Keepalive handling for connections
                for (ClientConnection cxn : connectionList) {
                    long sinceLastResponse = Math.max(now - cxn.lastResponseTime, 1);

                    // If outstanding ping and timed out, close the connection
                    if (cxn.outstandingPing && sinceLastResponse > connectionResponseTimeout) {
                        logError("Connection to %s port %d timed out", cxn.connection.getHostnameOrIP(),
                                 cxn.connection.getRemotePort());
                        cxn.connection.unregister();
                    }

                    // If one-third of the timeout since last response, send a ping
                    if (!cxn.outstandingPing && sinceLastResponse > connectionResponseTimeout / 3) {
                        cxn.outstandingPing = true;
                        callSystemProcedure(cxn, (r,t) -> { cxn.outstandingPing = false; }, "@Ping");
                    }
                }

                // Active request timeout (transactions that have been sent to write stream)
                int timedOut = 0;
                for (Long handle : activeHandles) {
                    RequestContext req = requestMap.get(handle);
                    if (req != null) { // null: request completed since the loop iterator was obtained
                        long delta = Math.max(now - req.startTime, 1);
                        if (delta > req.timeout) {
                            // For operations expected to be long-running, don't use the default timeout
                            // unless it exceeds our minimum timeout for long ops
                            if (!isLongOp(req.invocation.getProcName()) || delta >= MINIMUM_LONG_SYSPROC_TIMEOUT) {
                                completeRequestOnTimeout(req, delta);
                                timedOut++;
                            }
                        }
                    }
                }

                // If we completed any requests, this may affect backpressure
                if (timedOut > 0 && requestBackpressureOn) {
                    reportRequestBackpressure(false);
                }

            } catch (Exception ex) {
                logError("Unexpected exception in timeout task: %s", ex.getMessage());
            }
        }
    }

    /*
     * Handles timeout for a single procedure call. Generally used
     * when the timeout is less than a second.
     *
     * Caveat: we don't complete the request if it has not yet been
     * written to the network layer. The connection worker will take
     * care of that.
     */
    private class SingleTimeoutTask implements Runnable {
        private final RequestContext req;
        SingleTimeoutTask(RequestContext req) {
            this.req = req;
        }
        @Override
        public void run() {
            if (activeHandles.contains(req.invocation.getHandle())) {
                long delta = Math.max(System.nanoTime() - req.startTime, 1);
                completeRequestOnTimeout(req, delta);
            }
        }
    }

    /*
     * Check if the proc name is a procedure that is expected to run long.
     * Make the minimum timeout for certain long running system procedures
     * higher than the default 2 minutes.
     * You can still set the default timeout higher than even this value.
     */
    private static boolean isLongOp(String procName) {
        return procName.charAt(0) == '@' &&
            (procName.equals("@UpdateApplicationCatalog") ||
             procName.equals("@SnapshotSave"));
    }

    /*
     * Find connection for invocation preferably using hashinator data,
     * but falling back to round-robin where necesssary.  Updates affinity
     * stats as a side-effect.
     */
    private ClientConnection findConnection(ProcedureInvocation invocation) {
        ProcInfo procInfo = procInfoMap.get().get(invocation.getProcName());
        boolean readOnly = (procInfo != null && procInfo.readOnly);
        HashinatorLite hashi = hashinator.get(); // local reference for thread safety

        int hashedPartition = -1; // no partition
        if (invocation.hasPartitionDestination()) {
            hashedPartition = invocation.getPartitionDestination();
        }
        else if (hashi != null && procInfo != null) {
            if (procInfo.partitionParameter != ProcInfo.PARAMETER_NONE && // always NONE for multipart proc
                procInfo.partitionParameter < invocation.getPassedParamCount()) {
                hashedPartition = hashi.getHashedPartitionForParameter(procInfo.parameterType,
                                                                       invocation.getPartitionParamValue(procInfo.partitionParameter));
            }
            else {
                hashedPartition = Constants.MP_INIT_PID;
            }
        }

        boolean byAffinity = true;
        ClientConnection cxn = partitionLeaders.get().get(hashedPartition);
        if (cxn == null || !cxn.isConnected()) {
            cxn = findCxnByRoundRobin(invocation);
            byAffinity = false;
        }

        if (cxn != null && hashedPartition != -1) {
            updateAffinityStats(hashedPartition, readOnly, byAffinity);
        }

        return cxn;
    }

    /*
     * Find connection for invocaton using round-robin algorithm.
     * Prefer connections that are currently backpressured, but if
     * there are no such, then take the next available.
     *
     * The backpressure check is inherently racy: if there's no
     * backpressure now, it could start in the next few nanoseconds.
     * That's ok.
     */
    private ClientConnection findCxnByRoundRobin(ProcedureInvocation invocation) {
        List<ClientConnection> localList = new ArrayList<>(connectionList.size());
        for (ClientConnection cxn : connectionList) {
            localList.add(cxn); // iteration is safe, indexing is not
        }

        int cxnCount = localList.size();
        for (int i=0; i<2; i++) { // second pass ignores backpressure
            for (int j=0; j<cxnCount; ++j) {
                int n = (nextConnection + 1) % cxnCount;
                nextConnection = n;  // race is ok, this is just a hint
                ClientConnection cxn = localList.get(n);
                if (cxn.isConnected() && (i>0 || !cxn.backpressure)) {
                    return cxn;
                }
            }
        }
        return null;
    }

    /*
     * Update affinity stats for the target partition for a procedure
     * invocation. A counter is incremented based on whether we found
     * the target by affinity or round-robin, and whether or not it
     * is a read-only procedure.
     */
    private void updateAffinityStats(Integer hashedPartition, boolean readOnly, boolean affinityUsed) {
        ClientAffinityStats stats = null;
        synchronized (clientAffinityStats) {
            stats = clientAffinityStats.computeIfAbsent(hashedPartition, ClientAffinityStats::new);
        }
        if (affinityUsed) {
            if (readOnly) {
                stats.addAffinityRead();
            } else {
                stats.addAffinityWrite();
            }
        }
        else {
            if (readOnly) {
                stats.addRrRead();
            } else {
                stats.addRrWrite();
            }
        }
    }

    /*
     * Utility: serialize the procedure invocation for transmission
     */
    private ByteBuffer serializeInvocation(ProcedureInvocation pi) throws SerializationException {
        try {
            int size = pi.getSerializedSize();
            ByteBuffer buf = ByteBuffer.allocate(size + 4);
            buf.putInt(size);
            pi.flattenToBuffer(buf);
            buf.flip();
            return buf;
        }
        catch (IOException ex) {
            throw new SerializationException(ex.getMessage());
        }
    }

    /*
     * Utility: compute time remaining until timeout expired
     */
    private long remainingTime(long startTime, long timeout) {
        return Math.max(startTime + timeout - System.nanoTime(), 0);
    }

    /*
     * Utility: clip priority range, just for safety's sake.
     * Out-of-range priorities are forced to the lowest possible
     * priority. The result is always non-negative, due to
     * the values assigned for HIGHEST and LOWEST. Remember
     * that lower numbers mean higher priority.
     */
    private int clipRequestPriority(int prio) {
        if (prio < ProcedureInvocation.HIGHEST_PRIORITY || prio > ProcedureInvocation.LOWEST_PRIORITY) {
            prio = ProcedureInvocation.LOWEST_PRIORITY;
        }
        return prio;
    }

    /*
     * Fail a request when we didn't even get as far as setting
     * up a request context for it.
     */
    private void completeUnqueuedRequest(CompletableFuture<ClientResponse> future, long handle, String err) {
        ClientResponseImpl resp = new ClientResponseImpl(ClientResponse.CLIENT_ERROR_TXN_NOT_SENT,
                                                         new VoltTable[0], err);
        resp.setClientHandle(handle);
        future.complete(resp);
    }

    /*
     * Fail a request when there has been no possibility that it
     * has been transmitted to the server.
     */
    private void completeRequestOnLocalFailure(RequestContext req, boolean tmo, String err) {
        long handle = req.invocation.getHandle();
        if (removeRequest(handle) != null) {
            byte status = tmo ? ClientResponse.CLIENT_REQUEST_TIMEOUT : ClientResponse.CLIENT_ERROR_TXN_NOT_SENT;
            ClientResponseImpl resp = new ClientResponseImpl(status, new VoltTable[0], err);
            resp.setClientHandle(handle);
            resp.setClientRoundtrip(Math.max(System.nanoTime() - req.startTime, 1));
            releasePermit(req);
            req.future.complete(resp);
        }
    }

    /*
     * Time out a request when it may have already been sent to
     * the server. Permits are released even though the request
     * may still be outstanding at the server. This seems like
     * the safer option, but requires late responses to not also
     * release permits.
     */
    private void completeRequestOnTimeout(RequestContext req, long elapsed) {
        long handle = req.invocation.getHandle();
        if (removeRequest(handle) != null) {
            String err = String.format("No response received in the allotted time (set to %,d ms)",
                                       TimeUnit.NANOSECONDS.toMillis(req.timeout));
            ClientResponseImpl resp = new ClientResponseImpl(ClientResponse.CLIENT_RESPONSE_TIMEOUT,
                                                             new VoltTable[0], err);
            resp.setClientHandle(handle);
            resp.setClientRoundtrip(elapsed);
            int elapsedMS = (int)TimeUnit.NANOSECONDS.toMillis(elapsed);
            resp.setClusterRoundtrip(elapsedMS);
            if (handle >= 0) {
                req.cxn.clientStats(req.invocation.getProcName()).update(elapsed, elapsedMS, false, false, true);
            }
            releasePermit(req);
            req.future.complete(resp);
        }
    }

    /*
     * Host down. Complete request, including releasing permits.
     */
    private void completeRequestOnHostDown(RequestContext req) {
        long handle = req.invocation.getHandle();
        if (removeRequest(handle) != null) {
            String err = "Connection to host was lost before response was received";
            ClientResponseImpl resp = new ClientResponseImpl(ClientResponse.CONNECTION_LOST,
                                                             new VoltTable[0], err);
            resp.setClientHandle(handle);
            resp.setClientRoundtrip(Math.max(System.nanoTime() - req.startTime, 1));
            releasePermit(req);
            req.future.complete(resp);
        }
    }

    /*
     * Internally-generated call to a system procedure. These calls bypass
     * all flow control and are not queued in this client (though they
     * may be queued in the network code). They are distinguishable by
     * having negative handles.
     *
     * For implementation convenience, caller provides a consumer object
     * rather than dealing directly with the CompletableFuture. Exception
     * wrappers (like ExecutionException) are removed prior to the consumer.
     */
    private void callSystemProcedure(ClientConnection cxn,
                                     BiConsumer<ClientResponse,Throwable> completion,
                                     String procName, Object... procParams)
            throws SerializationException {
        CompletableFuture<ClientResponse> future = new CompletableFuture<>();
        future.whenComplete((resp, th) -> completion.accept(resp, unwrapThrowable(th)));
        long handle = sysHandleGenerator.decrementAndGet();
        ProcedureInvocation pi = new ProcedureInvocation(handle, BatchTimeoutOverrideType.NO_TIMEOUT, -1,
                                                         systemRequestPriority, procName, procParams);
        ByteBuffer buf = serializeInvocation(pi);
        RequestContext reqCtx = new RequestContext(future, pi, procedureCallTimeout, cxn);
        requestMap.put(handle, reqCtx);
        cxn.writeToNetwork(buf);
    }

    private Throwable unwrapThrowable(Throwable th) {
        while ((th instanceof ExecutionException || th instanceof CompletionException) &&
               th.getCause() != null) {
            th = th.getCause();
        }
        return th;
    }

    /*
     * Utility routine for performing common checks on responses to
     * internally-generated system procedure calls.
     */
    private boolean checkSystemResponse(ClientResponse resp, Throwable th,
                                        String what, int minTableCount) {
        boolean ok = false;
        if (th != null) {
            logError("Call to %s completed exceptionally: %s", what, th);
        }
        else if (resp.getStatus() == ClientResponse.SUCCESS) {
            VoltTable[] results = resp.getResults();
            int count = (results != null ? results.length : 0);
            if (count < minTableCount) {
                logError("Unexpected results from %s; needed %d tables, got %d",
                         what, minTableCount, count);
            }
            else {
                ok = true;
            }
        }
        else if (resp.getStatus() != ClientResponse.CONNECTION_LOST) {
            logError("Unexpected error %d returned from %s", resp.getStatus(), what);
        }
        return ok;
    }

    /*
     * This method makes sure that we have a subscription to topology updates.
     * If not, then it will schedule a task to issue the required procedure
     * calls. For the 'expected' cases when adding/removing connections, we
     * hold the connection lock. For recovery from task failure, this is
     * not the case, but it is safe.
     */
    private boolean ensureSubscription(long delay) {
        boolean pending = false;
        if (!isShutdown && !connectionList.isEmpty() && subscribedConnection == null) {
            if (!subscriptionTaskPending.getAndSet(true)) {
                execService.schedule(new SubscriberTask(), delay, TimeUnit.NANOSECONDS);
            }
            pending = true;
        }
        return pending;
    }

    private class SubscriberTask implements Runnable {
        @Override
        public void run() {
            try {
                ClientConnection cxn = arbitraryConnection();
                subscribedConnection = cxn;
                subscriptionTaskPending.set(false);
                callSystemProcedure(cxn, Client2Impl.this::subscribeCompletion, "@Subscribe", "TOPOLOGY");
                callSystemProcedure(cxn, Client2Impl.this::topoStatsCompletion, "@Statistics", "TOPO");
                callSystemProcedure(cxn, Client2Impl.this::procedureCatalogCompletion, "@SystemCatalog", "PROCEDURES");
            } catch (UnavailableException ex) {
                // can happen if all connections went down after we were queued.
                // next connection-up event will handle recovery.
                subscriptionTaskPending.set(false);
            } catch (Exception ex) {
                logError("Unexpected exception in subscriber task: %s", ex.getMessage());
                subscribedConnection = null;
                subscriptionTaskPending.set(false);
                ensureSubscription(resubscriptionFailureDelay);
            }
        }
    }

    private ClientConnection arbitraryConnection() throws UnavailableException {
        synchronized (connectionLock) { // required since we make two accesses
            int sz = connectionList.size();
            if (sz == 0) throw new UnavailableException("no connection available");
            return connectionList.get(randomizer.nextInt(sz));
        }
    }

    private void subscribeCompletion(ClientResponse resp, Throwable th) {
        if (!checkSystemResponse(resp, th, "@Subscribe", 0)) {
            ensureSubscription(resubscriptionDelay); // recover by retry?
        }
        else if (tracing) {
            trace("Subscribing to topology changes");
        }
    }

    private void topoStatsCompletion(ClientResponse resp, Throwable th) {
        if (!checkSystemResponse(resp, th, "@Statistics TOPO", 2)) {
            return; // no immediate recovery
        }
        if (tracing) {
            trace("Processing new topology data");
        }

        // Invalidate partition keys cache
        partitionKeysTimestamp.set(0);

        // Hashinator configuration
        VoltTable hashConfig = resp.getResults()[1];
        hashConfig.advanceRow();
        hashinator.set(new HashinatorLite(hashConfig.getVarbinary("HASHCONFIG"), false));

        // Partition leadership data
        VoltTable partInfo = resp.getResults()[0];
        Map<Integer,ClientConnection> newPartitionLeaders = new HashMap<>(partInfo.getRowCount());
        Set<Integer> unconnected = new HashSet<>();
        while (partInfo.advanceRow()) {
            Integer partition = (int)partInfo.getLong("Partition");
            String leader = partInfo.getString("Leader");
            String sites = partInfo.getString("Sites");
            if (sites != null && !sites.isEmpty()) {
                for (String site : sites.split(",")) {
                    site = site.trim();
                    Integer hostId = Integer.valueOf(site.split(":")[0]);
                    if (getConnectionForHost(hostId) == null) {
                        unconnected.add(hostId);
                    }
                }
            }
            if (leader != null && !leader.isEmpty()) {
                Integer leaderId = Integer.valueOf(leader.split(":")[0]);
                ClientConnection cxn = getConnectionForHost(leaderId);
                if (datadump) {
                    String where = "no connection";
                    if (cxn != null) {
                        where = "at " + cxn.connection.getHostnameOrIP() +
                                " " + cxn.connection.getRemotePort();
                    }
                    trace("  Partition %2d : leader %d, %s", partition, leaderId, where);
                }
                if (cxn != null) {
                    newPartitionLeaders.put(partition, cxn);
                }
            }
        }

        // Swap in the new map
        partitionLeaders.set(newPartitionLeaders);

        // Schedule the connector task
        if (!unconnected.isEmpty()) {
            trace("%d hosts are not currently connected", unconnected.size());
            scheduleConnectionTask(unconnected, 0);
        }
    }

    private void procedureCatalogCompletion(ClientResponse resp, Throwable th) {
        if (!checkSystemResponse(resp, th, "@SystemCatalog PROCEDURES", 1)) {
            return; // no immediate recovery
        }
        if (tracing) {
            trace("Processing new procedure catalogue");
        }

        int badJson = 0;
        VoltTable procTable = resp.getResults()[0];
        Map<String,ProcInfo> newProcInfoMap = new HashMap<>(procTable.getRowCount());
        while (procTable.advanceRow()) {
            String procName = "<unknown>";
            try {
                procName = procTable.getString(2);
                JSONObject jsObj = new JSONObject(procTable.getString(6));
                boolean readOnly = jsObj.getBoolean(Constants.JSON_READ_ONLY);
                boolean single = jsObj.getBoolean(Constants.JSON_SINGLE_PARTITION);
                int partitionParam = ProcInfo.PARAMETER_NONE;
                int paramType = ProcInfo.PARAMETER_NONE;
                if (single) {
                    partitionParam = jsObj.getInt(Constants.JSON_PARTITION_PARAMETER);
                    paramType = jsObj.getInt(Constants.JSON_PARTITION_PARAMETER_TYPE);
                }
                if (datadump) {
                    if (single) {
                        trace("  Proc %s : SP, param %d, type %d", procName, partitionParam, paramType);
                    }
                    else {
                        trace("  Proc %s : MP", procName);
                    }
                }
                newProcInfoMap.put(procName, new ProcInfo(!single, readOnly, partitionParam, paramType));
            } catch (JSONException ex) {
                if (++badJson <= 10) { // let's not go too crazy
                    logError("Catalog parse error for procedure '%s'", procName);
                }
            }
        }

        procInfoMap.set(Collections.unmodifiableMap(newProcInfoMap));
    }

    /*
     * Refreshes topology data (only). This is used when a new connection
     * comes up; we don't necessarily get a topology update from the
     * subscription, since the cluster has not changed.
     */
    private void refreshTopology(long delay) {
        if (!isShutdown && !connectionList.isEmpty() && !subscriptionTaskPending.get()) {
            if (!topoRefreshTaskPending.getAndSet(true)) {
                execService.schedule(new TopologyRefreshTask(), delay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private class TopologyRefreshTask implements Runnable {
        @Override
        public void run() {
            try {
                ClientConnection cxn = subscribedConnection;
                if (cxn == null) {
                    cxn = arbitraryConnection();
                }
                topoRefreshTaskPending.set(false);
                callSystemProcedure(cxn, Client2Impl.this::topoStatsCompletion, "@Statistics", "TOPO");
            } catch (UnavailableException ex) {
                // can happen if connections went down after we were queued.
                // next connection-up event will handle recovery.
                topoRefreshTaskPending.set(false);
            } catch (Exception ex) {
                logError("Unexpected exception in topology refresh task: %s", ex.getMessage());
                topoRefreshTaskPending.set(false);
                refreshTopology(topoRefreshFailureDelay);
            }
        }
    }

    /*
     * Refreshes partition key information; this is used only by all-partition
     * procedure calls. The expectation is that such calls will be infrequent
     * and therefore we refresh the partition/key map on demand (with a short
     * caching interval) rather than periodically.
     *
     * The timestamp is in milliseconds since the epoch, rather than in our
     * usual nanoseconds (not epoch-based), so that a value of zero is
     * guaranteed to be in the past.
     */
    private void refreshPartitionKeys(Consumer<Throwable> waiter) {
        if (!isShutdown && !connectionList.isEmpty()) {
            long age = System.currentTimeMillis() - partitionKeysTimestamp.get();
            if (TimeUnit.MILLISECONDS.toNanos(age) > partitionKeysCacheRefresh) {
                synchronized (partitionKeysWaiters) {
                    partitionKeysWaiters.add(waiter);
                }
                if (!partitionKeysUpdateInProgress.getAndSet(true)) {
                    if (tracing) trace("Refreshing partition keys list");
                    execService.schedule(new PartitionKeysTask(), 0, TimeUnit.NANOSECONDS);
                }
            }
            else {
                if (tracing) trace("Using cached partition keys list");
                waiter.accept(null);
            }
        }
        else {
            waiter.accept(new RuntimeException("no connection available"));
        }
    }

    private class PartitionKeysTask implements Runnable {
        @Override
        public void run() {
            try {
                ClientConnection cxn = subscribedConnection;
                if (cxn == null) {
                    cxn = arbitraryConnection();
                }
                callSystemProcedure(cxn, Client2Impl.this::partitionKeysCompletion, "@GetPartitionKeys", "INTEGER");
            } catch (UnavailableException ex) {
                notifyPartitionKeysWaiters(ex);
            } catch (Exception ex) {
                logError("Unexpected exception in partition-keys task: %s", ex.getMessage());
                notifyPartitionKeysWaiters(ex);
            }
        }
    }

    private void partitionKeysCompletion(ClientResponse resp, Throwable th) {
        if (!checkSystemResponse(resp, th, "@GetPartitionKeys INTEGER", 1)) {
            if (th == null) th = new RuntimeException("Partition keys cannot be determined");
            notifyPartitionKeysWaiters(th);
            return;
        }
        if (tracing) {
            trace("Processing partition keys data");
        }
        Map<Integer,Integer> newMap = new HashMap<>();
        VoltTable keyInfo = resp.getResults()[0];
        while (keyInfo.advanceRow()) {
            Integer id = (int)keyInfo.getLong("PARTITION_ID");
            Integer key = (int)keyInfo.getLong("PARTITION_KEY");
            newMap.put(id, key);
        }
        partitionKeysTimestamp.set(System.currentTimeMillis());
        partitionKeys.set(newMap);
        notifyPartitionKeysWaiters(null);
    }

    private void notifyPartitionKeysWaiters(Throwable th) {
        partitionKeysUpdateInProgress.set(false);
        List<Consumer<Throwable>> waiters;
        synchronized (partitionKeysWaiters) {
            waiters = new ArrayList<>(partitionKeysWaiters);
            partitionKeysWaiters.clear();
        }
        for (Consumer<Throwable> waiter : waiters) {
            waiter.accept(th);
        }
    }

    /*
     * Schedules a task to get a connection up when we have no other connections
     * available. We are given a list of possible servers (based on our connect
     * history) and we'll connect to the first available. After that, we're
     * driven by the usual subscription mechanism.
     *
     * If the task is unable to make a connection, it will retry indefinitely.
     */
    private void scheduleFirstConnection(Set<HostAndPort> hosts, long delay) {
        if (autoConnectionMgmt && !isShutdown && !hosts.isEmpty()) {
            if (!connectionTaskPending.getAndSet(true)) {
                execService.schedule(new FirstConnectionTask(hosts), delay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private class FirstConnectionTask implements Runnable {
        private final Set<HostAndPort> hosts;
        FirstConnectionTask(Set<HostAndPort> hosts) {
            this.hosts = hosts;
        }
        @Override
        public void run() {
            boolean retry = true;
            try {
                for (HostAndPort hap : hosts) {
                    try {
                        createConnection(hap.getHost(), hap.getPort());
                        retry = false;
                        break; // one is all we need
                    }
                    catch (Exception ex) {
                        logError("Failed to connect to host at %s: %s", hap, ex.getMessage());
                    }
                }
            }
            catch (Exception ex) {
                logError("Unexpected exception in first connection task: %s", ex.getMessage());
            }
            connectionTaskPending.set(false);
            if (retry) {
                scheduleFirstConnection(hosts, reconnectRetryDelay);
            }
        }
    }

    /*
     * Runs, if necessary, a task to connect to any hosts not currently
     * connected. We might have unconnected hosts as a consequence of
     * topology updates, for example.
     *
     * This needs to be done as a two-stage procedure: first we schedule
     * a task to collect system configuration information from any
     * connected host, and when that completes, we schedule a second
     * task to do the actual connection work. This avoids blocking
     * in the network thread (createConnection is synchronous).
     *
     * Which port to connect to? We apply the heuristic that if all
     * existing connections use their respective admin ports, then
     * we should do likewise. This decision is made once only.
     * In practice, that means if the first server address supplied
     * by the app uses an admin port, we will select the admin port
     * for all automatically-made connections and reconnections.
     */
    private void scheduleConnectionTask(Set<Integer> hostIds, long delay) {
        if (autoConnectionMgmt && !isShutdown && !hostIds.isEmpty()) {
            if (!connectionTaskPending.getAndSet(true)) {
                execService.schedule(new ConnectionInitTask(hostIds), delay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private class ConnectionInitTask implements Runnable {
        private final Set<Integer> hostIds;
        ConnectionInitTask(Set<Integer> hostIds) {
            this.hostIds = hostIds;
        }
        @Override
        public void run() {
            try {
                ClientConnection cxn = arbitraryConnection();
                callSystemProcedure(cxn, this::hostInfoCompletion, "@SystemInformation", "OVERVIEW");
            }
            catch (UnavailableException ex) {
                // can happen if connections went down after we were queued
                connectionTaskPending.set(false);
            }
            catch (Exception ex) {
                logError("Unexpected exception in connection init task: %s", ex.getMessage());
                connectionTaskPending.set(false);
                scheduleConnectionTask(hostIds, reconnectRetryDelay);
            }
        }
        void hostInfoCompletion(ClientResponse resp, Throwable th) {
            if (!checkSystemResponse(resp, th, "@SystemInformation OVERVIEW", 1)) {
                connectionTaskPending.set(false);
                scheduleConnectionTask(hostIds, reconnectRetryDelay);
                return;
            }
            execService.schedule(new ConnectionTask(hostIds, resp.getResults()[0]), 0, TimeUnit.NANOSECONDS);
        }
    }

    private class ConnectionTask implements Runnable {
        private final Set<Integer> hostIds;
        private final VoltTable info;
        ConnectionTask(Set<Integer> hostIds, VoltTable info) {
            this.hostIds = hostIds;
            this.info = info;
        }
        @Override
        public void run() {
            boolean retry = false;
            Map<Integer,HostAndPort> unconnected = null;
            try {
                unconnected = getUnconnectedAddresses(hostIds, info);
                Iterator<Map.Entry<Integer,HostAndPort>> it = unconnected.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer,HostAndPort> ent = it.next();
                    Integer hostId = ent.getKey();
                    HostAndPort hap = ent.getValue();
                    try {
                        createConnection(hap.getHost(), hap.getPort());
                        it.remove();
                    }
                    catch (Exception ex) {
                        logError("Failed to connect to host %d at %s: %s", hostId, hap, ex.getMessage());
                        retry = true;
                    }
                }
            }
            catch (Exception ex) {
                logError("Unexpected exception in connection task: %s", ex.getMessage());
                retry = true;
            }
            connectionTaskPending.set(false);
            if (retry) {
                scheduleConnectionTask(unconnected != null ? unconnected.keySet() : hostIds,
                                       reconnectRetryDelay);
            }
        }
    }

    private Map<Integer,HostAndPort> getUnconnectedAddresses(Set<Integer> hostIds, VoltTable info) {
        if (infoTablePortKey == null) { // once only
            infoTablePortKey = sniffForPortKey(info);
        }
        Map<Integer,String> addrMap = new HashMap<>();
        Map<Integer,Integer> portMap = new HashMap<>();
        while (info.advanceRow()) {
            String key = info.getString("KEY");
            if (key.equals("IPADDRESS")) {
                Integer hostId = (int)info.getLong("HOST_ID");
                String addr = info.getString("VALUE");
                addrMap.put(hostId, addr);
            }
            else if (key.equals(infoTablePortKey)) { // CLIENTPORT or ADMINPORT
                Integer hostId = (int)info.getLong("HOST_ID");
                Integer port = Integer.valueOf(info.getString("VALUE"));
                portMap.put(hostId, port);
            }
        }
        Map<Integer,HostAndPort> hapMap = new HashMap<>();
        for (Integer hostId : hostIds) {
            if (getConnectionForHost(hostId) == null) { // check still not connected
                String addr = addrMap.get(hostId);
                Integer port = portMap.get(hostId);
                if (addr != null && port != null) {
                    hapMap.put(hostId, HostAndPort.fromParts(addr, port));
                }
                else {
                    logError("Cannot connect to host %d, no address/port information found", hostId);
                }
            }
        }
        return hapMap;
    }

    private String sniffForPortKey(VoltTable info) {
        int peons = 0, admins = 0;
        while (info.advanceRow()) {
            if (info.getString("KEY").equals("ADMINPORT")) {
                Integer hostId = (int)info.getLong("HOST_ID");
                Integer adminPort = Integer.valueOf(info.getString("VALUE"));
                ClientConnection cxn = getConnectionForHost(hostId);
                if (cxn != null) {
                    if (cxn.connection.getRemotePort() == adminPort)
                        admins++;
                    else
                        peons++;
                }
            }
        }
        info.resetRowPosition();
        if (tracing && peons != 0 && admins != 0) {
            trace("Client/admin heuristic indeterminate: %d connections using admin port, %d not", admins, peons);
        }
        return peons == 0 && admins != 0 ? "ADMINPORT" : "CLIENTPORT";
    }

    /*
     * Statistics support routines; package access for ClientStatsContext.
     */
    Map<Long, Map<String, ClientStats>> getStatsSnapshot() {
        Map<Long, Map<String,ClientStats>> retval = new TreeMap<>();
        for (ClientConnection conn : connectionList) {
            Map<String,ClientStats> connMap = new TreeMap<>();
            for (Map.Entry<String,ClientStats> ent : conn.stats.entrySet()) {
                connMap.put(ent.getKey(), (ClientStats)ent.getValue().clone());
            }
            retval.put(conn.connectionId(), connMap);
        }
        return retval;
    }

    Map<Long, ClientIOStats> getIOStatsSnapshot() {
        Map<Long,ClientIOStats> retval = new TreeMap<>();
        Map<Long,Pair<String,long[]>> ioStats;
        try {
            ioStats = networkPool.getIOStats(false, Collections.<VoltNetworkPool.IOStatsIntf>emptyList());
        } catch (Exception ex) {
            return null;
        }
        for (ClientConnection conn : connectionList) {
            Pair<String,long[]> perConnStats = ioStats.get(conn.connectionId());
            if (perConnStats != null) {
                long read = perConnStats.getSecond()[0];
                long write = perConnStats.getSecond()[2];
                ClientIOStats cios = new ClientIOStats(conn.connectionId(), read, write);
                retval.put(conn.connectionId(), cios);
            }
        }
        return retval;
    }

    Map<Integer,ClientAffinityStats> getAffinityStatsSnapshot() {
        Map<Integer,ClientAffinityStats> retval = new HashMap<>();
        synchronized (clientAffinityStats) {
            for (Map.Entry<Integer,ClientAffinityStats> ent : clientAffinityStats.entrySet()) {
                retval.put(ent.getKey(), (ClientAffinityStats)ent.getValue().clone());
            }
        }
        return retval;
    }

    /*
     * Adapters used to convert an async API call to a sync API call.
     * The exception wrappers from CompleteableFuture.get() are unwrapped.
     *
     * As per the original client API:
     * - IOExceptions are allowed through
     * - An unsuccessful ClientResponse causes a ProcCallException
     */
    private ClientResponse toSyncProcCall(CompletableFuture<ClientResponse> future)
    throws ProcCallException, IOException {
        ClientResponse resp = null;
        try {
            resp = future.get();
            if (resp.getStatus() == ClientResponse.SUCCESS) {
                return resp;
            }
        }
        catch (Exception ex) {
            throwMappedException(ex);
            return null;
        }
        throw new ProcCallException(resp);
    }

    private ClientResponseWithPartitionKey[] toSyncAllPartCall(CompletableFuture<ClientResponseWithPartitionKey[]> future)
    throws IOException {
        try {
            return future.get();
        }
        catch (Exception ex) {
            throwMappedException(ex);
            return null;
        }
    }

    private <T> T toSyncReturn(CompletableFuture<T> future)
    throws IOException {
        try {
            return future.get();
        }
        catch (Exception ex) {
            throwMappedException(ex);
            return null;
        }
    }

    private void throwMappedException(Exception ex)
    throws IOException {
        ex = unwrapException(ex);
        if (ex instanceof IOException) {
            throw (IOException)ex;
        }
        if (ex instanceof RuntimeException) {
            throw (RuntimeException)ex;
        }
        throw new GeneralException(ex);
    }

    private Exception unwrapException(Exception ex) {
        Throwable cause;
        while ((ex instanceof ExecutionException || ex instanceof CompletionException) &&
               (cause = ex.getCause()) != null && cause instanceof Exception) {
            ex = (Exception)cause;
        }
        return ex;
    }

    /*
     * Write error log messages for cases that cannot be reported
     * via the usual completion mechanisms. No action needed other
     * than logging; application can intercept and do its own
     * logging.
     */
    private void logError(String msg, Object... args) {
        if (args.length > 0) {
            msg = String.format(msg, args);
        }
        errorLog.accept(msg);
    }

    private void printError(String msg) {
        System.err.println("%%% " + msg);
    }

    /*
     * Debug tracing, normally disabled. Not subject to
     * intercept by application handler.
     */
    private void trace(String msg, Object... args) {
        if (tracing) {
            System.err.printf("--- " + msg + '\n', args);
        }
    }
}
