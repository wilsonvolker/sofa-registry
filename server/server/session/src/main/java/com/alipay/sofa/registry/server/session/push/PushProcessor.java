/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.registry.server.session.push;

import com.alipay.sofa.registry.common.model.dataserver.Datum;
import com.alipay.sofa.registry.common.model.store.Subscriber;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.remoting.CallbackHandler;
import com.alipay.sofa.registry.remoting.Channel;
import com.alipay.sofa.registry.server.session.bootstrap.SessionServerConfig;
import com.alipay.sofa.registry.server.session.node.service.ClientNodeService;
import com.alipay.sofa.registry.server.session.utils.DatumUtils;
import com.alipay.sofa.registry.task.KeyedThreadPoolExecutor;
import com.alipay.sofa.registry.util.ConcurrentUtils;
import com.alipay.sofa.registry.util.WakeupLoopRunnable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PushProcessor {
    private static final Logger                    LOGGER       = LoggerFactory
                                                                    .getLogger(PushProcessor.class);

    private KeyedThreadPoolExecutor                pushExecutor;
    private final Map<TaskKey, PushTask>           pendingTasks = Maps.newConcurrentMap();
    private final Lock                             pendingLock  = new ReentrantLock();

    private final Map<InetSocketAddress, PushTask> pushingTasks = Maps.newConcurrentMap();

    @Autowired
    private SessionServerConfig                    sessionServerConfig;

    @Autowired
    private PushDataGenerator                      pushDataGenerator;

    @Autowired
    private ClientNodeService                      clientNodeService;

    private final WatchDog                         watchDog     = new WatchDog();

    @PostConstruct
    public void init() {
        // TODO config arg
        pushExecutor = new KeyedThreadPoolExecutor("PushExecutor", 6, 4000);
        ConcurrentUtils.createDaemonThread("PushWatchDog", watchDog).start();
    }

    private void firePush(PushTask pushTask) {
        TaskKey key = pushTask.keyOf();
        if (pendingTasks.putIfAbsent(key, pushTask) == null) {
            // fast path
            return;
        }
        boolean conflict = false;
        PushTask prev = null;
        pendingLock.lock();
        try {
            prev = pendingTasks.get(key);
            if (prev == null) {
                pendingTasks.put(key, pushTask);
            } else if (pushTask.afterThan(prev)) {
                // update the expireTimestamp as prev's, avoid the push block by the continues fire
                pushTask.expireTimestamp = prev.expireTimestamp;
                pendingTasks.put(key, pushTask);
            } else {
                conflict = true;
            }
        } finally {
            pendingLock.unlock();
        }
        if (!conflict) {
            if (pushTask.noDelay) {
                watchDog.wakeup();
            }
        } else {
            LOGGER.info("[ConflictMerge] {}, prev {} > {}", key, prev.fetchSeqEnd,
                pushTask.fetchSeqStart);
        }
    }

    protected List<PushTask> createPushTask(boolean noDelay, long pushVersion, String dataCenter,
                                            InetSocketAddress addr,
                                            Map<String, Subscriber> subscriberMap,
                                            Map<String, Datum> datumMap, long fetchStartSeq,
                                            long fetchEndSeq) {
        PushTask pushTask = new PushTask(noDelay, pushVersion, dataCenter, addr, subscriberMap,
            datumMap, fetchStartSeq, fetchEndSeq);
        // TODO config 500ms
        pushTask.expireAfter(500);
        return Collections.singletonList(pushTask);
    }

    void firePush(boolean noDelay, long pushVersion, String dataCenter, InetSocketAddress addr, Map<String, Subscriber> subscriberMap,
                  Map<String, Datum> datumMap, long fetchSeqStart, long fetchSeqEnd) {
        List<PushTask> fires = createPushTask(noDelay, pushVersion, dataCenter, addr, subscriberMap, datumMap, fetchSeqStart, fetchSeqEnd);
        fires.forEach(t -> firePush(t));
    }

    private boolean commitTask(PushTask task) {
        try {
            // keyed by client.addr
            pushExecutor.execute(task.addr, task);
            return true;
        } catch (Throwable e) {
            LOGGER.error("failed to exec push task {}", task.keyOf(), e);
            return false;
        }
    }

    private final class WatchDog extends WakeupLoopRunnable {

        @Override
        public void runUnthrowable() {
            List<PushTask> pending = transferAndMerge();
            if (sessionServerConfig.isStopPushSwitch()) {
                return;
            }
            if (pending.isEmpty()) {
                return;
            }
            LOGGER.info("process push tasks {}", pending.size());
            for (PushTask task : pending) {
                commitTask(task);
            }
        }

        @Override
        public int getWaitingMillis() {
            return 100;
        }
    }

    private List<PushTask> transferAndMerge() {
        List<PushTask> pending = Lists.newArrayList();
        final long now = System.currentTimeMillis();
        pendingLock.lock();
        try {
            final Iterator<Map.Entry<TaskKey, PushTask>> it = pendingTasks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<TaskKey, PushTask> e = it.next();
                PushTask task = e.getValue();
                if (task.noDelay || task.expireTimestamp <= now) {
                    pending.add(task);
                    it.remove();
                }
            }
        } finally {
            pendingLock.unlock();
        }
        return pending;
    }

    private boolean checkPushing(PushTask task) {
        // keyed by address, the subcriber was processed serially
        // check the pushing task
        final PushTask prev = pushingTasks.get(task.addr);
        if (prev == null) {
            // check the subcriber version
            for (Subscriber subscriber : task.subscriberMap.values()) {
                if (!subscriber.checkVersion(task.dataCenter, task.fetchSeqStart)) {
                    LOGGER.warn("conflict push, subscriber={}, {}", subscriber, task);
                    return false;
                }
            }
            return true;
        }
        if (!task.afterThan(prev)) {
            LOGGER.warn("prev push is newly, prev={}, now={}", prev, task);
            return false;
        }
        // task after the  prev, but prev.pushclient not callback, retry
        retry(task, "waiting");
        return false;
    }

    private boolean retry(PushTask task, String reason) {
        // TODO config retrytimes
        if (task.retryCount.incrementAndGet() <= 3) {
            task.expireAfter(500);
            firePush(task);
            LOGGER.info("add retry for {}, {}", reason, task);
            return true;
        }
        LOGGER.info("skip retry for {}, {}", reason, task);
        return false;
    }

    class PushTask implements Runnable {
        final long                    createTimestamp = System.currentTimeMillis();
        long                          expireTimestamp;
        final boolean                 noDelay;
        final long                    fetchSeqStart;
        final long                    fetchSeqEnd;
        final String                  dataCenter;
        final long                    pushVersion;
        final Map<String, Datum>      datumMap;
        final InetSocketAddress       addr;
        final Map<String, Subscriber> subscriberMap;
        final Subscriber              subscriber;
        final AtomicInteger           retryCount      = new AtomicInteger();

        PushTask(boolean noDelay, long pushVersion, String dataCenter, InetSocketAddress addr,
                 Map<String, Subscriber> subscriberMap, Map<String, Datum> datumMap,
                 long fetchSeqStart, long fetchSeqEnd) {
            this.noDelay = noDelay;
            this.dataCenter = dataCenter;
            this.pushVersion = pushVersion;
            this.datumMap = datumMap;
            this.addr = addr;
            this.subscriberMap = subscriberMap;
            this.fetchSeqStart = fetchSeqStart;
            this.fetchSeqEnd = fetchSeqEnd;
            this.subscriber = subscriberMap.values().iterator().next();
        }

        protected Object createPushData() {
            Datum merged = pushDataGenerator.mergeDatum(subscriber, dataCenter, datumMap);
            return pushDataGenerator.createPushData(merged, subscriberMap, pushVersion);
        }

        void expireAfter(long intervalMs) {
            this.expireTimestamp = System.currentTimeMillis() + intervalMs;
        }

        @Override
        public void run() {
            if (sessionServerConfig.isStopPushSwitch()) {
                return;
            }
            try {
                if (!checkPushing(this)) {
                    return;
                }
                Object data = createPushData();
                pushingTasks.put(addr, this);
                clientNodeService.pushWithCallback(data, subscriber.getSourceAddress(),
                    new PushClientCallback(this));
                LOGGER.info("pushing {}, subscribers={}, addr={}", subscriber.getDataInfoId(),
                    subscriberMap.size(), addr);
            } catch (Throwable e) {
                // try to delete self,
                pushingTasks.remove(addr);
                LOGGER.error("failed to create push data, {}", this, e);
            }
        }

        boolean afterThan(PushTask t) {
            return fetchSeqStart > t.fetchSeqEnd;
        }

        TaskKey keyOf() {
            return new TaskKey(dataCenter, addr, subscriberMap.keySet());
        }

        @Override
        public String toString() {
            return "PushTask{" + "createTimestamp=" + createTimestamp + ", fetchSeqStart="
                   + fetchSeqStart + ", fetchSeqEnd=" + fetchSeqEnd + ", dataCenter='" + dataCenter
                   + '\'' + ", pushVersion=" + pushVersion + ", addr=" + addr + ", subscriber="
                   + subscriber + ", retryCount=" + retryCount + '}';
        }
    }

    private final class PushClientCallback implements CallbackHandler {
        final PushTask pushTask;

        PushClientCallback(PushTask pushTask) {
            this.pushTask = pushTask;
        }

        @Override
        public void onCallback(Channel channel, Object message) {
            boolean cleaned = false;
            try {
                final Map<String, Long> versions = DatumUtils.getVesions(pushTask.datumMap);
                for (Subscriber subscriber : pushTask.subscriberMap.values()) {
                    if (!subscriber.checkAndUpdateVersion(pushTask.dataCenter,
                        pushTask.pushVersion, versions, pushTask.fetchSeqStart,
                        pushTask.fetchSeqEnd)) {
                        LOGGER.warn("Push success, but failed to updateVersion, {}", pushTask);
                    }
                }
            } catch (Throwable e) {
                LOGGER.error("error push.onCallback, {}", pushTask, e);
            } finally {
                cleaned = pushingTasks.remove(pushTask.addr, pushTask);
            }
            LOGGER.info("Push success, clean record={}, {}", cleaned, pushTask);
        }

        @Override
        public void onException(Channel channel, Throwable exception) {
            boolean cleaned = false;
            try {
                cleaned = pushingTasks.remove(pushTask.addr, pushTask);
                retry(pushTask, "callbackErr");
            } catch (Throwable e) {
                LOGGER.error("error push.onException, {}", pushTask, e);
            }
            LOGGER.error("Push error, clean record={}, {}", cleaned, pushTask, exception);
        }

        @Override
        public Executor getExecutor() {
            return null;
        }
    }

    private static final class TaskKey {
        final String            dataCenter;
        final InetSocketAddress addr;
        final Set<String>       subscriberIds;

        TaskKey(String dataCenter, InetSocketAddress addr, Set<String> subscriberIds) {
            this.dataCenter = dataCenter;
            this.addr = addr;
            this.subscriberIds = subscriberIds;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            TaskKey taskKey = (TaskKey) o;
            return Objects.equals(addr, taskKey.addr)
                   && Objects.equals(dataCenter, taskKey.dataCenter)
                   && Objects.equals(subscriberIds, taskKey.subscriberIds);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dataCenter, addr, subscriberIds);
        }

        @Override
        public String toString() {
            return "TaskKey{" + "dataCenter='" + dataCenter + '\'' + ", addr=" + addr
                   + ", subscriberIds=" + subscriberIds + '}';
        }
    }

}
