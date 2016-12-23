/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.redhat.hacep.cluster;

import it.redhat.hacep.cache.session.HAKieSerializedSession;
import it.redhat.hacep.cache.session.HAKieSession;
import it.redhat.hacep.model.Fact;
import it.redhat.hacep.model.Key;
import org.infinispan.Cache;
import org.infinispan.manager.DefaultCacheManager;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.kie.api.runtime.Channel;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class ClusterTest extends AbstractClusterTest {

    private final static Logger logger = LoggerFactory.getLogger(ClusterTest.class);


    private static ExecutorService executorService = Executors.newFixedThreadPool(4);

    @Mock
    private Channel replayChannel;
    @Mock
    private Channel additionsChannel;

    private ZonedDateTime now = ZonedDateTime.now();

    @Test
    public void testClusterSize() {
        TestDroolsConfiguration droolsConfiguration = TestDroolsConfiguration.buildV1();
        logger.info("Start test cluster size");
        Cache<Key, HAKieSession> cache1 = startNodes(2, droolsConfiguration).getCache();
        Cache<Key, HAKieSession> cache2 = startNodes(2, droolsConfiguration).getCache();

        assertEquals(2, ((DefaultCacheManager) cache1.getCacheManager()).getClusterSize());
        assertEquals(2, ((DefaultCacheManager) cache2.getCacheManager()).getClusterSize());
        logger.info("End test cluster size");
        droolsConfiguration.dispose();
    }

    @Test
    public void testEmptyHASession() {
        TestDroolsConfiguration droolsConfiguration = TestDroolsConfiguration.buildV1();
        logger.info("Start test empty HASessionID");
        droolsConfiguration.setMaxBufferSize(10);

        Cache<String, Object> cache1 = startNodes(2, droolsConfiguration).getCache();
        Cache<String, Object> cache2 = startNodes(2, droolsConfiguration).getCache();

        reset(replayChannel);

        String key = "1";
        HAKieSession session1 = new HAKieSession(droolsConfiguration, executorService);

        cache1.put(key, session1);
        Object serializedSessionCopy = cache2.get(key);

        Assert.assertNotNull(serializedSessionCopy);
        Assert.assertTrue(HAKieSerializedSession.class.isAssignableFrom(serializedSessionCopy.getClass()));

        reset(replayChannel, additionsChannel);

        HAKieSession session2 = ((HAKieSerializedSession) serializedSessionCopy).rebuild();
        Assert.assertNotNull(session2);
        logger.info("End test empty HASessionID");
        droolsConfiguration.dispose();
    }

    @Test
    public void testNonEmptyHASession() {
        TestDroolsConfiguration droolsConfiguration = TestDroolsConfiguration.buildV1();
        logger.info("Start test non empty HASessionID");
        droolsConfiguration.registerChannel("additions", additionsChannel, replayChannel);
        droolsConfiguration.setMaxBufferSize(10);

        Cache<String, Object> cache1 = startNodes(2, droolsConfiguration).getCache();
        Cache<String, Object> cache2 = startNodes(2, droolsConfiguration).getCache();

        String key = "2";
        HAKieSession session1 = new HAKieSession(droolsConfiguration, executorService);

        cache1.put(key, session1);

        session1.insert(generateFactTenSecondsAfter(1L, 10L));
        cache1.put(key, session1);

        session1.insert(generateFactTenSecondsAfter(1L, 20L));
        cache1.put(key, session1);

        session1.insert(generateFactTenSecondsAfter(1L, 30L));
        cache1.put(key, session1);

        verify(replayChannel, never()).send(any());

        InOrder inOrder = inOrder(additionsChannel);
        inOrder.verify(additionsChannel, times(1)).send(eq(10L));
        inOrder.verify(additionsChannel, times(1)).send(eq(30L));
        inOrder.verify(additionsChannel, times(1)).send(eq(60L));
        inOrder.verifyNoMoreInteractions();
        // Double check on total number of calls to the method send
        verify(additionsChannel, times(3)).send(any());

        Object serializedSessionCopy = cache2.get(key);

        Assert.assertNotNull(serializedSessionCopy);
        Assert.assertTrue(HAKieSerializedSession.class.isAssignableFrom(serializedSessionCopy.getClass()));

        reset(replayChannel, additionsChannel);

        HAKieSession session2 = ((HAKieSerializedSession) serializedSessionCopy).rebuild();

        session2.insert(generateFactTenSecondsAfter(1L, 40L));

        inOrder = inOrder(replayChannel);
        inOrder.verify(replayChannel, times(1)).send(eq(60L));
        inOrder.verifyNoMoreInteractions();
        // Double check on total number of calls to the method send
        verify(replayChannel, times(1)).send(any());

        verify(additionsChannel, atMost(1)).send(any());
        verify(additionsChannel, times(1)).send(eq(100L));
        logger.info("End test non empty HASessionID");
        droolsConfiguration.dispose();
    }

    @Test
    public void testHASessionWithMaxBuffer() {
        TestDroolsConfiguration droolsConfiguration = TestDroolsConfiguration.buildV1();
        logger.info("Start test HASessionID with max buffer 2");
        droolsConfiguration.registerChannel("additions", additionsChannel, replayChannel);
        droolsConfiguration.setMaxBufferSize(2);

        Cache<String, HAKieSession> cache1 = startNodes(2, droolsConfiguration).getCache();
        Cache<String, HAKieSession> cache2 = startNodes(2, droolsConfiguration).getCache();

        reset(replayChannel, additionsChannel);

        String key = "3";
        HAKieSession session1 = new HAKieSession(droolsConfiguration, executorService);

        cache1.put(key, session1);

        session1.insert(generateFactTenSecondsAfter(1L, 10L));
        cache1.put(key, session1);

        session1.insert(generateFactTenSecondsAfter(1L, 20L));
        cache1.put(key, session1);

        session1.insert(generateFactTenSecondsAfter(1L, 30L));
        cache1.put(key, session1);

        InOrder inOrder = inOrder(additionsChannel);
        inOrder.verify(additionsChannel, times(1)).send(eq(10L));
        inOrder.verify(additionsChannel, times(1)).send(eq(30L));
        inOrder.verify(additionsChannel, times(1)).send(eq(60L));
        inOrder.verifyNoMoreInteractions();
        // Double check on total number of calls to the method send
        verify(additionsChannel, times(3)).send(any());

        Object serializedSessionCopy = cache2.get(key);

        Assert.assertNotNull(serializedSessionCopy);
        Assert.assertTrue(HAKieSerializedSession.class.isAssignableFrom(serializedSessionCopy.getClass()));

        reset(replayChannel, additionsChannel);

        HAKieSession session2 = ((HAKieSerializedSession) serializedSessionCopy).rebuild();

        session2.insert(generateFactTenSecondsAfter(1L, 40L));

        verify(additionsChannel, times(1)).send(eq(100L));
        // Double check on total number of calls to the method send
        verify(additionsChannel, times(1)).send(any());
        logger.info("End test HASessionID with max buffer 2");
        droolsConfiguration.dispose();
    }

    @Test
    public void testHASessionAddNode() {
        TestDroolsConfiguration droolsConfiguration = TestDroolsConfiguration.buildV1();
        logger.info("Start test HASessionID add node");
        droolsConfiguration.registerChannel("additions", additionsChannel, replayChannel);
        droolsConfiguration.setMaxBufferSize(10);

        Cache<String, HAKieSession> cache1 = startNodes(2, droolsConfiguration).getCache();

        reset(replayChannel, additionsChannel);

        String key = "3";
        HAKieSession session1 = new HAKieSession(droolsConfiguration, executorService);

        cache1.put(key, session1);

        session1.insert(generateFactTenSecondsAfter(1L, 10L));
        cache1.put(key, session1);

        session1.insert(generateFactTenSecondsAfter(1L, 20L));
        cache1.put(key, session1);

        session1.insert(generateFactTenSecondsAfter(1L, 30L));
        cache1.put(key, session1);

        verify(replayChannel, never()).send(any());

        InOrder inOrder = inOrder(additionsChannel);
        inOrder.verify(additionsChannel, times(1)).send(eq(10L));
        inOrder.verify(additionsChannel, times(1)).send(eq(30L));
        inOrder.verify(additionsChannel, times(1)).send(eq(60L));
        inOrder.verifyNoMoreInteractions();
        // Double check on total number of calls to the method send
        verify(additionsChannel, times(3)).send(any());

        Cache<Key, HAKieSession> cache2 = startNodes(2, droolsConfiguration).getCache();
        Object serializedSessionCopy = cache2.get(key);

        Assert.assertNotNull(serializedSessionCopy);
        Assert.assertTrue(HAKieSession.class.isAssignableFrom(serializedSessionCopy.getClass()));

        reset(replayChannel, additionsChannel);

        HAKieSession session2 = ((HAKieSerializedSession) serializedSessionCopy).rebuild();

        session2.insert(generateFactTenSecondsAfter(1L, 40L));

        verify(replayChannel, never()).send(any());

        verify(additionsChannel, times(1)).send(eq(100L));
        // Double check on total number of calls to the method send
        verify(additionsChannel, times(1)).send(any());

        logger.info("End test HASessionID add node");
        droolsConfiguration.dispose();
    }

    @Override
    protected Channel getReplayChannel() {
        return replayChannel;
    }

    private Fact generateFactTenSecondsAfter(long ppid, long amount) {
        now = now.plusSeconds(10);
        return new TestFact(ppid, amount, new Date(now.toInstant().toEpochMilli()));
    }

}
