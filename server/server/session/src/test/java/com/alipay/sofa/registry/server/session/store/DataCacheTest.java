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
package com.alipay.sofa.registry.server.session.store;

import com.alipay.sofa.registry.common.model.ConnectId;
import com.alipay.sofa.registry.common.model.SubscriberUtils;
import com.alipay.sofa.registry.common.model.constants.ValueConstants;
import com.alipay.sofa.registry.common.model.store.*;
import com.alipay.sofa.registry.common.model.store.DataInfo;
import com.alipay.sofa.registry.core.model.AssembleType;
import com.alipay.sofa.registry.core.model.ScopeEnum;
import com.alipay.sofa.registry.net.NetUtil;
import com.alipay.sofa.registry.server.session.bootstrap.CommonConfig;
import com.alipay.sofa.registry.server.session.bootstrap.SessionServerConfig;
import com.alipay.sofa.registry.server.session.bootstrap.SessionServerConfigBean;
import com.alipay.sofa.registry.server.session.cache.CacheGenerator;
import com.alipay.sofa.registry.server.session.cache.CacheService;
import com.alipay.sofa.registry.server.session.cache.SessionCacheService;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Uninterruptibles;
import org.junit.Assert;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * @author shangyu.wh
 * @version $Id: DataCacheTest.java, v 0.1 2017-12-06 19:42 shangyu.wh Exp $
 */

public class DataCacheTest extends BaseTest {

    private AtomicLong version  = new AtomicLong(1);

    private AtomicLong registId = new AtomicLong(1000);

    @Test
    public void testGetSub() {
        SessionInterests sessionInterests = new SessionInterests();
        sessionInterests.setSessionServerConfig(new SessionServerConfigBean(null));

        String dataId = "dataid";
        String connectId = "192.168.1.2:9000_127.0.0.1:34567";

        for (int i = 0; i < 100; i++) {
            sessionInterests.add(getSub(dataId, ScopeEnum.zone, null, null));
        }
        //add other ip
        sessionInterests.add(getSub(dataId, ScopeEnum.zone, null, new URL("192.168.1.9", 8000)));

        Map<InetSocketAddress, Map<String, Subscriber>> map = getCacheSub(
            DataInfo.toDataInfoId(dataId, "instance2", "rpc"), ScopeEnum.zone, sessionInterests);
        Assert.assertTrue(getCacheSub(sessionInterests, connectId));

        sessionInterests.deleteByConnectId(ConnectId.parse(connectId));

        Map<InetSocketAddress, Map<String, Subscriber>> map2 = getCacheSub(
            DataInfo.toDataInfoId(dataId, "instance2", "rpc"), ScopeEnum.zone, sessionInterests);

        Assert.assertFalse(getCacheSub(sessionInterests, connectId));

        //map no change
        Assert.assertEquals(2, map.size());

        //get cache change,just remain 192.168.1.9:8000
        Assert.assertEquals(map2.keySet().size(), 1);
        Assert.assertEquals(NetUtil.toAddressString(map2.keySet().iterator().next()),
            "192.168.1.9:8000");
    }

    @Test
    public void testDeleteSubById() {
        SessionInterests sessionInterests = new SessionInterests();
        sessionInterests.setSessionServerConfig(new SessionServerConfigBean(null));

        String dataId = "dataid";

        for (int i = 0; i < 100; i++) {

            sessionInterests.add(getSub(dataId, ScopeEnum.zone, null, null));
        }
        //add other ip
        sessionInterests.add(getSub(dataId, ScopeEnum.zone, "xxregist123", new URL("192.168.1.9",
            8000)));
        //sessionInterests.add(getSub(dataId,ScopeEnum.zone,"xxregist456",new URL("192.168.1.10", 7000)));

        Map<InetSocketAddress, Map<String, Subscriber>> map = getCacheSub(
            DataInfo.toDataInfoId(dataId, "instance2", "rpc"), ScopeEnum.zone, sessionInterests);
        Assert.assertTrue(getCacheSub(sessionInterests, "192.168.1.9:8000_127.0.0.1:34567"));

        sessionInterests.deleteById("xxregist123",
            DataInfo.toDataInfoId(dataId, "instance2", "rpc"));

        Map<InetSocketAddress, Map<String, Subscriber>> map2 = getCacheSub(
            DataInfo.toDataInfoId(dataId, "instance2", "rpc"), ScopeEnum.zone, sessionInterests);

        Assert.assertFalse(getCacheSub(sessionInterests, "192.168.1.9:8000_127.0.0.1:34567"));
        //map no change
        Assert.assertEquals(2, map.size());

        //remain 100
        Assert.assertEquals(map2.keySet().size(), 1);

        InetSocketAddress address = new InetSocketAddress("192.168.1.2", 9000);
        InetSocketAddress addressDel = new InetSocketAddress("192.168.1.9", 8000);
        Assert.assertFalse(map2.get(address).isEmpty());

        Assert.assertTrue(map2.get(addressDel) == null);

        //Assert.assertEquals(NetUtil.toAddressString(map2.keySet().iterator().next()),"192.168.1.2:9000");
        Assert.assertEquals(map2.get(address).size(), 100);
    }

    @Test
    public void testGetPub() {
        SessionDataStore sessionDataStore = new SessionDataStore();

        String dataId = "dataid";
        String connectId = "192.168.1.2:9000_127.0.0.1:34567";
        for (int i = 0; i < 10; i++) {

            sessionDataStore.add(getPub(dataId, null, null));
        }

        Assert.assertTrue(getCachePub(sessionDataStore, connectId));
        sessionDataStore.deleteByConnectId(ConnectId.parse(connectId));
        Assert.assertFalse(getCachePub(sessionDataStore, connectId));
    }

    @Test
    public void testGetPubRefresh() {
        SessionDataStore sessionDataStore = new SessionDataStore();

        String dataId = "dataid";
        String connectId = "192.168.1.2:9000";
        int number = 1000;
        List<Publisher> publisherList = Lists.newArrayList();
        for (int i = 0; i < number; i++) {
            String connectIdss = "192.111.0.1:" + (8000 + i);
            Publisher p = getPub(dataId, null, URL.valueOf(connectIdss));
            sessionDataStore.add(p);
            publisherList.add(p);
            Assert.assertTrue(getCachePub(sessionDataStore, connectIdss
                                                            + ValueConstants.CONNECT_ID_SPLIT
                                                            + p.getTargetAddress()
                                                                .buildAddressString()));
        }
        for (Publisher p : publisherList) {
            String c = connectId + ValueConstants.CONNECT_ID_SPLIT
                       + p.getTargetAddress().buildAddressString();
            Assert.assertFalse(getCachePub(sessionDataStore, c));
        }
    }

    @Test
    public void testDelPubById() {
        SessionDataStore sessionDataStore = new SessionDataStore();
        String dataId = "dataid";
        String connectId = "192.168.1.2:9000_127.0.0.1:34567";
        for (int i = 0; i < 10; i++) {

            sessionDataStore.add(getPub(dataId, null, null));
        }

        sessionDataStore.add(getPub(dataId, "XXXX", new URL("192.168.1.9", 8000)));

        Assert.assertTrue(getCachePub(sessionDataStore, connectId));
        Assert.assertTrue(getCachePub(sessionDataStore, "192.168.1.9:8000_127.0.0.1:34567"));
        sessionDataStore.deleteById("XXXX", DataInfo.toDataInfoId(dataId, "instance2", "rpc"));
        Assert.assertTrue(getCachePub(sessionDataStore, connectId));
        Assert.assertFalse(getCachePub(sessionDataStore, "192.168.1.9:8000_127.0.0.1:34567"));
    }

    private boolean getCachePub(SessionDataStore sessionDataStore, String connectId) {
        Map map = sessionDataStore.queryByConnectId(ConnectId.parse(connectId));
        return map != null && !map.isEmpty();
    }

    private boolean getCacheSub(SessionInterests sessionInterests, String connectId) {
        Map map = sessionInterests.queryByConnectId(ConnectId.parse(connectId));
        return map != null && !map.isEmpty();
    }

    private Map<InetSocketAddress, Map<String, Subscriber>> getCacheSub(String dataInfoId,
                                                                        ScopeEnum scopeEnum,
                                                                        SessionInterests sessionInterests) {
        Collection<Subscriber> subscribers = sessionInterests.getDatas(dataInfoId);
        Map<InetSocketAddress, Map<String, Subscriber>> ret = Maps.newHashMap();
        Map<AssembleType, Map<ScopeEnum, List<Subscriber>>> groups = SubscriberUtils
            .groupByAssembleAndScope(subscribers);
        for (Map<ScopeEnum, List<Subscriber>> group : groups.values()) {
            List<Subscriber> list = group.get(scopeEnum);
            ret.putAll(SubscriberUtils.groupBySourceAddress(list));
        }
        return ret;
    }

    private Subscriber getSub(String dataId, ScopeEnum scopeEnum, String registerId, URL url) {

        String processid = "4466";

        Subscriber subscriberRegister = new Subscriber();
        subscriberRegister.setAppName("app");
        subscriberRegister.setCell("My zone");
        subscriberRegister.setClientId("clientid" + version.get());
        subscriberRegister.setDataId(dataId);
        subscriberRegister.setGroup("rpc");
        subscriberRegister.setInstanceId("instance2");
        subscriberRegister.setRegisterId(registerId == null ? String.valueOf(registId
            .incrementAndGet()) : registerId);
        subscriberRegister.setProcessId(processid);
        subscriberRegister.setVersion(version.get());
        subscriberRegister.setRegisterTimestamp(System.currentTimeMillis());
        subscriberRegister.setScope(scopeEnum);
        subscriberRegister.setAssembleType(AssembleType.sub_app_and_interface);
        subscriberRegister.setDataInfoId(DataInfo.toDataInfoId(dataId, "instance2", "rpc"));

        subscriberRegister.setSourceAddress(url == null ? new URL("192.168.1.2", 9000) : url);
        subscriberRegister.setTargetAddress(new URL("127.0.0.1", 34567));

        return subscriberRegister;
    }

    private Publisher getPub(String dataId, String registerId, URL url) {

        String processid = "4466";

        Publisher publisher = new Publisher();
        publisher.setAppName("app");
        publisher.setCell("My zone");
        publisher.setClientId("clientid" + version.get());
        publisher.setDataId(dataId);
        publisher.setGroup("rpc");
        publisher.setInstanceId("instance2");
        publisher.setRegisterId(registerId == null ? String.valueOf(registId.incrementAndGet())
            : registerId);
        publisher.setProcessId(processid);
        publisher.setVersion(version.get());
        publisher.setRegisterTimestamp(System.currentTimeMillis());
        publisher.setDataInfoId(DataInfo.toDataInfoId(dataId, "instance2", "rpc"));

        publisher.setSourceAddress(url == null ? new URL("192.168.1.2", 9000) : url);
        publisher.setTargetAddress(new URL("127.0.0.1", 34567));

        return publisher;
    }

    @Test
    public void testOverwriteSameConnectIdPublisher() {

        SessionDataStore sessionDataStore = new SessionDataStore();

        Publisher publisher1 = new Publisher();
        publisher1.setDataInfoId("dataInfoId1");
        publisher1.setDataId("dataId1");
        publisher1.setRegisterId("RegisterId1");
        publisher1.setSourceAddress(new URL("192.168.1.1", 12345));
        publisher1.setTargetAddress(new URL("192.168.1.2", 9600));
        publisher1.setVersion(1L);
        publisher1.setRegisterTimestamp(System.currentTimeMillis());

        Publisher publisher2 = new Publisher();
        publisher2.setDataInfoId("dataInfoId2");
        publisher2.setDataId("dataId2");
        publisher2.setRegisterId("RegisterId2");
        publisher2.setSourceAddress(new URL("192.168.1.1", 12345));
        publisher2.setTargetAddress(new URL("192.168.1.2", 9600));
        publisher2.setVersion(2L);
        publisher2.setRegisterTimestamp(System.currentTimeMillis());
        sessionDataStore.add(publisher1);
        sessionDataStore.add(publisher2);

        Assert.assertEquals(
            sessionDataStore
                .queryByConnectId(ConnectId.parse("192.168.1.1:12345_192.168.1.2:9600")).size(), 2);
        sessionDataStore.add(publisher2);

        Assert.assertEquals(
            sessionDataStore
                .queryByConnectId(ConnectId.parse("192.168.1.1:12345_192.168.1.2:9600")).size(), 2);

        Publisher publisher3 = new Publisher();
        publisher3.setDataInfoId(publisher1.getDataInfoId());
        publisher3.setDataId(publisher1.getDataId());
        publisher3.setRegisterId(publisher1.getRegisterId());
        publisher3.setSourceAddress(new URL("192.168.1.1", 12346));
        publisher3.setTargetAddress(new URL("192.168.1.2", 9600));
        publisher3.setVersion(2L);
        publisher3.setRegisterTimestamp(System.currentTimeMillis());

        Publisher publisher4 = new Publisher();
        publisher4.setDataInfoId(publisher2.getDataInfoId());
        publisher4.setDataId(publisher2.getDataId());
        publisher4.setRegisterId(publisher2.getRegisterId());
        publisher4.setSourceAddress(new URL("192.168.1.1", 12346));
        publisher4.setTargetAddress(new URL("192.168.1.2", 9600));
        publisher4.setVersion(2L);
        publisher4.setRegisterTimestamp(System.currentTimeMillis());

        sessionDataStore.add(publisher3);
        sessionDataStore.add(publisher4);

        Assert.assertEquals(
            sessionDataStore
                .queryByConnectId(ConnectId.parse("192.168.1.1:12345_192.168.1.2:9600")).size(), 0);
        Assert.assertEquals(
            sessionDataStore
                .queryByConnectId(ConnectId.parse("192.168.1.1:12346_192.168.1.2:9600")).size(), 2);

    }

    @Test
    public void testOverwriteSameConnectIdSubscriber() {

        SessionInterests sessionInterests = new SessionInterests();
        SessionServerConfig config = new SessionServerConfigBean(new CommonConfig());
        config.setStopPushSwitch(false);
        sessionInterests.setSessionServerConfig(config);

        Subscriber subscriber1 = new Subscriber();
        subscriber1.setScope(ScopeEnum.dataCenter);
        subscriber1.setAssembleType(AssembleType.sub_app_and_interface);
        subscriber1.setDataInfoId("dataInfoId1");
        subscriber1.setDataId("dataId1");
        subscriber1.setRegisterId("RegisterId1");
        subscriber1.setSourceAddress(new URL("192.168.1.1", 12345));
        subscriber1.setTargetAddress(new URL("192.168.1.2", 9600));

        Subscriber subscriber2 = new Subscriber();
        subscriber2.setScope(ScopeEnum.dataCenter);
        subscriber2.setAssembleType(AssembleType.sub_app_and_interface);
        subscriber2.setDataInfoId("dataInfoId2");
        subscriber2.setDataId("dataId2");
        subscriber2.setRegisterId("RegisterId2");
        subscriber2.setSourceAddress(new URL("192.168.1.1", 12345));
        subscriber2.setTargetAddress(new URL("192.168.1.2", 9600));

        sessionInterests.add(subscriber1);
        sessionInterests.add(subscriber2);

        Assert.assertEquals(
            sessionInterests
                .queryByConnectId(ConnectId.parse("192.168.1.1:12345_192.168.1.2:9600")).size(), 2);
        sessionInterests.add(subscriber2);

        Assert.assertEquals(
            sessionInterests
                .queryByConnectId(ConnectId.parse("192.168.1.1:12345_192.168.1.2:9600")).size(), 2);

        Subscriber subscriber3 = new Subscriber();
        subscriber3.setScope(ScopeEnum.dataCenter);
        subscriber3.setAssembleType(AssembleType.sub_app_and_interface);
        subscriber3.setDataInfoId(subscriber1.getDataInfoId());
        subscriber3.setDataId(subscriber1.getDataId());
        subscriber3.setRegisterId(subscriber1.getRegisterId());
        subscriber3.setSourceAddress(new URL("192.168.1.1", 12346));
        subscriber3.setTargetAddress(new URL("192.168.1.2", 9600));

        Subscriber subscriber4 = new Subscriber();
        subscriber4.setScope(ScopeEnum.dataCenter);
        subscriber4.setAssembleType(AssembleType.sub_app_and_interface);
        subscriber4.setDataInfoId(subscriber2.getDataInfoId());
        subscriber4.setDataId(subscriber2.getDataId());
        subscriber4.setRegisterId(subscriber2.getRegisterId());
        subscriber4.setSourceAddress(new URL("192.168.1.1", 12346));
        subscriber4.setTargetAddress(new URL("192.168.1.2", 9600));

        sessionInterests.add(subscriber3);
        sessionInterests.add(subscriber4);

        Assert.assertEquals(
            sessionInterests
                .queryByConnectId(ConnectId.parse("192.168.1.1:12345_192.168.1.2:9600")).size(), 0);
        Assert.assertEquals(
            sessionInterests
                .queryByConnectId(ConnectId.parse("192.168.1.1:12346_192.168.1.2:9600")).size(), 2);
    }

    @Test
    public void testOverwriteSameConnectIdWatcher() {

        SessionWatchers sessionWatchers = new SessionWatchers();

        Watcher watcher1 = new Watcher();
        watcher1.setDataInfoId("dataInfoId1");
        watcher1.setDataId("dataId1");
        watcher1.setRegisterId("RegisterId1");
        watcher1.setSourceAddress(new URL("192.168.1.1", 12345));
        watcher1.setTargetAddress(new URL("192.168.1.2", 9600));

        Watcher watcher2 = new Watcher();
        watcher2.setDataInfoId("dataInfoId2");
        watcher2.setDataId("dataId2");
        watcher2.setRegisterId("RegisterId2");
        watcher2.setSourceAddress(new URL("192.168.1.1", 12345));
        watcher2.setTargetAddress(new URL("192.168.1.2", 9600));

        sessionWatchers.add(watcher1);
        sessionWatchers.add(watcher2);

        Assert.assertEquals(
            sessionWatchers.queryByConnectId(ConnectId.parse("192.168.1.1:12345_192.168.1.2:9600"))
                .size(), 2);
        sessionWatchers.add(watcher2);

        Assert.assertEquals(
            sessionWatchers.queryByConnectId(ConnectId.parse("192.168.1.1:12345_192.168.1.2:9600"))
                .size(), 2);

        Watcher watcher3 = new Watcher();
        watcher3.setDataInfoId(watcher1.getDataInfoId());
        watcher3.setDataId(watcher1.getDataId());
        watcher3.setRegisterId(watcher1.getRegisterId());
        watcher3.setSourceAddress(new URL("192.168.1.1", 12346));
        watcher3.setTargetAddress(new URL("192.168.1.2", 9600));

        Watcher watcher4 = new Watcher();
        watcher4.setDataInfoId(watcher2.getDataInfoId());
        watcher4.setDataId(watcher2.getDataId());
        watcher4.setRegisterId(watcher2.getRegisterId());
        watcher4.setSourceAddress(new URL("192.168.1.1", 12346));
        watcher4.setTargetAddress(new URL("192.168.1.2", 9600));

        sessionWatchers.add(watcher3);
        sessionWatchers.add(watcher4);

        Assert.assertEquals(
            sessionWatchers.queryByConnectId(ConnectId.parse("192.168.1.1:12345_192.168.1.2:9600"))
                .size(), 0);
        Assert.assertEquals(
            sessionWatchers.queryByConnectId(ConnectId.parse("192.168.1.1:12346_192.168.1.2:9600"))
                .size(), 2);
    }

    @Test
    public void testSubAndClientOffUnorder() {
        SessionInterests sessionInterests = new SessionInterests();
        SessionServerConfig config = new SessionServerConfigBean(null);
        sessionInterests.setSessionServerConfig(config);

        Subscriber subscriber1 = new Subscriber();
        subscriber1.setScope(ScopeEnum.dataCenter);
        subscriber1.setAssembleType(AssembleType.sub_app_and_interface);
        subscriber1.setDataInfoId("dataInfoId1");
        subscriber1.setDataId("dataId1");
        subscriber1.setRegisterId("RegisterId1");
        subscriber1.setSourceAddress(new URL("192.168.1.1", 12345));
        subscriber1.setTargetAddress(new URL("192.168.1.2", 9600));
        sessionInterests.add(subscriber1);

        Subscriber subscriber2 = new Subscriber();
        subscriber2.setScope(subscriber1.getScope());
        subscriber2.setAssembleType(subscriber1.getAssembleType());
        subscriber2.setDataInfoId(subscriber1.getDataInfoId());
        subscriber2.setDataId(subscriber1.getDataId());
        subscriber2.setRegisterId(subscriber1.getRegisterId());
        subscriber2.setSourceAddress(new URL("192.168.1.1", 12346));
        subscriber2.setTargetAddress(new URL("192.168.1.2", 9600));

        sessionInterests.add(subscriber2);

        sessionInterests.deleteByConnectId(ConnectId.parse(subscriber1.getSourceAddress()
            .getAddressString() + "_" + subscriber1.getTargetAddress().getAddressString()));

        Assert.assertEquals(
            sessionInterests
                .queryByConnectId(ConnectId.parse("192.168.1.1:12345_192.168.1.2:9600")).isEmpty(),
            true);
        Assert.assertEquals(
            sessionInterests
                .queryByConnectId(ConnectId.parse("192.168.1.1:12346_192.168.1.2:9600")).size(), 1);

        Map<InetSocketAddress, Map<String, Subscriber>> addressMap = getCacheSub(
            subscriber1.getDataInfoId(), subscriber1.getScope(), sessionInterests);
        Assert.assertEquals(addressMap.get(new InetSocketAddress("192.168.1.1", 12345)), null);
        Assert.assertEquals(addressMap.get(new InetSocketAddress("192.168.1.1", 12346)).size(), 1);
        Assert.assertEquals(sessionInterests.getDatas(subscriber1.getDataInfoId()).size(), 1);
        Assert.assertTrue(sessionInterests.getDatas(subscriber1.getDataInfoId()).contains(
            subscriber2));
    }
}