/* This file is part of VoltDB.
 * Copyright (C) 2021 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.client;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.voltdb.ServerThread;
import org.voltdb.VoltDB;
import org.voltdb.VoltDB.Configuration;
import org.voltdb.compiler.CatalogBuilder;
import org.voltdb.compiler.DeploymentBuilder;

import junit.framework.TestCase;

/**
 * These tests cover explicit async Client2 operations.
 *
 * Sync tests, covered elsewhere, also test basic async operation,
 * since the sync implementation in Client2 merely issues the
 * async call and waits for it to complete. We do not repeat
 * such tests here.
 */
public class TestClient2AsyncCall extends TestCase {

    ServerThread localServer;
    DeploymentBuilder depBuilder;

    @Override
    public void setUp() {
        try {
            System.out.printf("=-=-=-=-=-=-= Starting test %s =-=-=-=-=-=-=\n", getName());

            CatalogBuilder catBuilder = new CatalogBuilder();
            catBuilder.addProcedures(ArbitraryDurationProc.class);
            boolean success = catBuilder.compile(Configuration.getPathToCatalogForTest("timeouts.jar"));
            if (!success) throw new RuntimeException("bad catalog");

            depBuilder = new DeploymentBuilder(1, 1, 0);
            depBuilder.writeXML(Configuration.getPathToCatalogForTest("timeouts.xml"));

            VoltDB.Configuration config = new VoltDB.Configuration();
            config.m_pathToCatalog = Configuration.getPathToCatalogForTest("timeouts.jar");
            config.m_pathToDeployment = Configuration.getPathToCatalogForTest("timeouts.xml");
            localServer = new ServerThread(config);
            localServer.start();
            localServer.waitForInitialization();
        }
        catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }

    @Override
    public void tearDown() throws Exception {
        localServer.shutdown();
        System.out.printf("=-=-=-=-=-=-= End of test %s =-=-=-=-=-=-=\n", getName());
    }

    volatile boolean goodResponse = false;
    volatile boolean threw = false;
    volatile long endTime = 0;
    volatile long roundTrip = 0;
    volatile Throwable lastThrown = null;
    volatile int respStatus = 0;

    private void checkResponse(ClientResponse resp) {
        endTime = System.nanoTime();
        assertNotNull(resp);
        System.out.printf("checkResponse: status %d %s\n", resp.getStatus(), resp.getStatusString());
        assertEquals("Did not get expected success status", ClientResponse.SUCCESS, resp.getStatus());
        roundTrip = resp.getClientRoundtripNanos();
        respStatus = resp.getStatus();
        goodResponse = true;
    }

    private void checkTimedOut(ClientResponse resp) {
        endTime = System.nanoTime();
        assertNotNull(resp);
        byte sts = resp.getStatus();
        System.out.printf("checkTimedOut: status %d %s\n", sts, resp.getStatusString());
        assertTrue("Did not get expected timeout status", sts == ClientResponse.CLIENT_REQUEST_TIMEOUT || sts == ClientResponse.CLIENT_RESPONSE_TIMEOUT);
        roundTrip = resp.getClientRoundtripNanos();
        respStatus = sts;
        goodResponse = true; // 'good' as in 'expected'
    }

    private Void screamAndShout(Throwable th) {
        endTime = System.nanoTime();
        assertNotNull(th);
        System.out.printf("Call completed exceptionally: %s\n", th);
        threw = true;
        lastThrown = th;
        respStatus = -999; // unknowable
        roundTrip = -999; // unknowable
        return null;
    }

    /**
     * Basics, including timeouts
     */
    public void testSingleAsyncCalls() throws Exception {
        Client2Config config = new Client2Config()
            .procedureCallTimeout(1200, TimeUnit.MILLISECONDS);

        Client2 client = ClientFactory.createClient(config);
        client.connectSync("localhost");

        // proc runs for 0 sec, timeout 1.2 sec, expect no timeout
        goodResponse = threw = false;
        client.callProcedureAsync("ArbitraryDurationProc", 0)
            .thenAccept(this::checkResponse)
            .exceptionally(this::screamAndShout)
            .join();

        assertTrue(goodResponse);
        assertFalse(threw);

        // proc runs for 3.5 sec, timeout after 1.2 sec
        goodResponse = threw = false;
        CompletableFuture<ClientResponse> fut1 = client.callProcedureAsync("ArbitraryDurationProc", 3500);
        long start1 = System.nanoTime();
        fut1.thenAccept(this::checkTimedOut)
            .exceptionally(this::screamAndShout)
            .join();

        assertTrue(goodResponse);
        assertFalse(threw);
        validateTmo(start1, 1200, TimeUnit.MILLISECONDS, 2500, TimeUnit.MILLISECONDS);

        // proc runs for 2.5 sec, timeout after 123 usec
        long shortTmo = 123; // us
        goodResponse = threw = false;
        Client2CallOptions opts = new Client2CallOptions().clientTimeout(shortTmo, TimeUnit.MICROSECONDS);
        CompletableFuture<ClientResponse> fut2 = client.callProcedureAsync(opts, "ArbitraryDurationProc", 2500);
        long start2 = System.nanoTime();
        fut2.thenAccept(this::checkTimedOut)
            .exceptionally(this::screamAndShout)
            .join();

        assertTrue(goodResponse);
        assertFalse(threw);
        validateTmo(start2, shortTmo, TimeUnit.MICROSECONDS, 10, TimeUnit.MILLISECONDS);

        client.close();
    }

    private void validateTmo(long startTime, long expected, TimeUnit expUnit, long limit, TimeUnit limitUnit) {
        long elapsed = endTime > startTime ? endTime - startTime : 0; // racy (may have completed before we noted start time)
        System.out.printf("Expected %,dus timeout took %,dus (client round trip %,dus, status %d)\n",
                          expUnit.toMicros(expected),
                          TimeUnit.NANOSECONDS.toMicros(elapsed),
                          TimeUnit.NANOSECONDS.toMicros(roundTrip),
                          respStatus);
        long limNs = limitUnit.toNanos(limit);
        assertTrue("Timeout took too long to time out", elapsed <= limNs);
    }

    /**
     * Test some overlapped calls
     */
    public void testSimultaneousCalls() throws Exception {
        Client2Config config = new Client2Config()
            .procedureCallTimeout(1200, TimeUnit.MILLISECONDS);

        Client2 client = ClientFactory.createClient(config);
        client.connectSync("localhost");

        // Not timing out
        goodResponse = threw = false;
        final int CALLCOUNT = 20;
        CountDownLatch allDone = new CountDownLatch(CALLCOUNT);
        for (int i=0; i<CALLCOUNT; i++) {
            client.callProcedureAsync("ArbitraryDurationProc", 100)
                .thenAccept(this::checkResponse)
                .exceptionally(this::screamAndShout)
                .whenComplete((v,t) -> allDone.countDown());
        }

        boolean finished = allDone.await(5, TimeUnit.SECONDS);
        assertTrue(finished);
        assertTrue(goodResponse);
        assertFalse(threw);

        // Timing out
        goodResponse = threw = false;
        CountDownLatch allUndone = new CountDownLatch(CALLCOUNT);
        for (int i=0; i<CALLCOUNT; i++) {
            client.callProcedureAsync("ArbitraryDurationProc", 3000)
                .thenAccept(this::checkTimedOut)
                .exceptionally(this::screamAndShout)
                .whenComplete((v,t) -> allUndone.countDown());
        }

        boolean finished2 = allUndone.await(5, TimeUnit.SECONDS);
        assertTrue(finished2);
        assertTrue(goodResponse);
        assertFalse(threw);

        client.close();
    }

    /**
     * This test forces request backpressure based on hitting
     * the max outstanding transactions limit (preventing
     * transmission) and the pending-request warning level
     * (triggering backpressure).
     */

    AtomicBoolean backpressureOn = new AtomicBoolean(false);
    AtomicInteger backpressured = new AtomicInteger(0);
    AtomicInteger unbackpressured = new AtomicInteger(0);

    private void backpressureHandler(boolean st) {
        backpressureOn.set(st);
        if (st) {
            int n = backpressured.incrementAndGet();
            System.out.printf("Setting backpressure on: %d%n", n);
        }
        else {
            int n = unbackpressured.incrementAndGet();
            System.out.printf("Setting backpressure off: %d%n", n);
        }
    }

    public void testBackpressure() throws Exception {
        final int PROCTIME = 2000; // execution time for one proc call
        final int TOTALREQ = 20; // total calls to be made
        final int ENOUGH = 15; // 15th one triggers backpressure

        Client2Config config = new Client2Config()
            .procedureCallTimeout(PROCTIME+1000, TimeUnit.MILLISECONDS)
            .outstandingTransactionLimit(5) // high enough to get past initial flurry
            .clientRequestBackpressureLevel(15, 5) // backpressure on at 15, off at 5
            .requestBackpressureHandler(this::backpressureHandler);

        Client2 client = ClientFactory.createClient(config);
        client.connectSync("localhost");
        goodResponse = threw = false;

        // Hacky code to wait until traffic caused by topo code
        // has settled down
        Thread.sleep(1000);
        client.drain();
        assertFalse(backpressureOn.get());

        CountDownLatch allDone = new CountDownLatch(TOTALREQ);

        // The first 14 go ok (note 1-based counting here)
        // All but first will eventually time out, that's ok
        for (int i=1; i<ENOUGH; i++) {
            client.callProcedureAsync("ArbitraryDurationProc", PROCTIME)
                .thenAccept(this::acceptAndIgnore)
                .exceptionally(this::screamAndShout)
                .whenComplete((v,t) -> allDone.countDown());
        }
        System.out.println("Expecting not backpressured yet");
        assertEquals(0, backpressured.get());
        assertEquals(0, unbackpressured.get());

        // The 15th triggers backpressure after it is queued (only one notification)
        for (int i=ENOUGH; i<=TOTALREQ; i++) {
            client.callProcedureAsync("ArbitraryDurationProc", PROCTIME)
                .thenAccept(this::acceptAndIgnore)
                .exceptionally(this::screamAndShout)
                .whenComplete((v,t) -> allDone.countDown());
        }
        System.out.println("Expecting backpressured now");
        assertEquals(1, backpressured.get());
        assertEquals(0, unbackpressured.get());

        // Backpressure will eventually go off (no unracy way to detect that
        // it happens at exactly 5 uncompleted). Wait limit here is 1 sec
        // more than the proc call timeout, which is 1 sec more than we
        // expect procedures to execute for.
        boolean finished = allDone.await(PROCTIME+2000, TimeUnit.MILLISECONDS);
        System.out.println("Expecting not backpressured again");
        assertEquals(1, backpressured.get());
        assertEquals(1, unbackpressured.get());

        // Wait on completion
        client.drain();
        client.close();
    }

    /**
     * Intentionally goes over the hard limit on outstanding requests.
     * We totally ignore backpressure warnings.
     */

    private Void acceptAndIgnore(ClientResponse resp) {
        return null;
    }

    public void testRedline() throws Exception {
        final int PROCTIME = 2000; // execution time for one proc call
        final int REQLIM = 10;

        Client2Config config = new Client2Config()
            .procedureCallTimeout(PROCTIME+1000, TimeUnit.MILLISECONDS)
            .outstandingTransactionLimit(5) // high enough to get past initial flurry
            .clientRequestLimit(REQLIM); // the 11th one is the problem

        Client2 client = ClientFactory.createClient(config);
        client.connectSync("localhost");
        goodResponse = threw = false;

        // Hacky code to wait until traffic caused by topo code
        // has settled down
        Thread.sleep(1000);
        client.drain();

        // These'll all get queued ok
        goodResponse = threw = false;
        System.out.println("Expecting no failures yet");
        for (int i=0; i<REQLIM; i++) {
            client.callProcedureAsync("ArbitraryDurationProc", PROCTIME)
                  .thenAccept(this::acceptAndIgnore)
                  .exceptionally(this::screamAndShout);
        }
        assertFalse(threw); // racy

        // This one goes over the edge
        // Exception is always synchronous
        System.out.println("Expecting next call to be refused");
        client.callProcedureAsync("ArbitraryDurationProc", PROCTIME)
              .thenAccept(this::acceptAndIgnore)
              .exceptionally(this::screamAndShout);
        assertTrue(threw);
        assertNotNull(lastThrown);
        assertTrue(lastThrown instanceof CompletionException);
        assertTrue(lastThrown.getCause() instanceof RequestLimitException);

        // Wait on completion
        client.drain();
        client.close();
    }
}
