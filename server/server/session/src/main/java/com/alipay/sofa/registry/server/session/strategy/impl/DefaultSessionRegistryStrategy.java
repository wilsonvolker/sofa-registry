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
package com.alipay.sofa.registry.server.session.strategy.impl;

import com.alipay.sofa.registry.server.session.push.FirePushService;
import org.springframework.beans.factory.annotation.Autowired;

import com.alipay.sofa.registry.common.model.store.Publisher;
import com.alipay.sofa.registry.common.model.store.Subscriber;
import com.alipay.sofa.registry.common.model.store.Watcher;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.server.session.bootstrap.SessionServerConfig;
import com.alipay.sofa.registry.server.session.strategy.SessionRegistryStrategy;
import com.alipay.sofa.registry.task.listener.TaskEvent;
import com.alipay.sofa.registry.task.listener.TaskListenerManager;

/**
 * @author kezhu.wukz
 * @author xuanbei
 * @since 2019/2/15
 */
public class DefaultSessionRegistryStrategy implements SessionRegistryStrategy {
    private static final Logger taskLogger = LoggerFactory.getLogger(
                                               DefaultSessionRegistryStrategy.class, "[Task]");

    /**
     * trigger task com.alipay.sofa.registry.server.meta.listener process
     */
    @Autowired
    private TaskListenerManager taskListenerManager;

    @Autowired
    private FirePushService     firePushService;

    @Autowired
    private SessionServerConfig sessionServerConfig;

    @Override
    public void afterPublisherRegister(Publisher publisher) {

    }

    @Override
    public void afterSubscriberRegister(Subscriber subscriber) {
        if (!sessionServerConfig.isStopPushSwitch()) {
            firePushService.fireOnRegister(subscriber);
        }
    }

    @Override
    public void afterWatcherRegister(Watcher watcher) {
        fireWatcherRegisterFetchTask(watcher);
    }

    @Override
    public void afterPublisherUnRegister(Publisher publisher) {

    }

    @Override
    public void afterSubscriberUnRegister(Subscriber subscriber) {

    }

    @Override
    public void afterWatcherUnRegister(Watcher watcher) {

    }

    private void fireWatcherRegisterFetchTask(Watcher watcher) {
        //trigger fetch data for watcher,and push to client node
        TaskEvent taskEvent = new TaskEvent(watcher, TaskEvent.TaskType.WATCHER_REGISTER_FETCH_TASK);
        taskLogger.info("send " + taskEvent.getTaskType() + " taskEvent:{}", taskEvent);
        taskListenerManager.sendTaskEvent(taskEvent);
    }
}
