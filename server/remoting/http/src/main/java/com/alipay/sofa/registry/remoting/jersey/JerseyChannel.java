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
package com.alipay.sofa.registry.remoting.jersey;

import java.net.InetSocketAddress;
import java.net.URI;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;

import com.alipay.sofa.registry.net.NetUtil;
import com.alipay.sofa.registry.remoting.Channel;

/**
 *
 * @author shangyu.wh
 * @version $Id: JerseyChannel.java, v 0.1 2018-02-01 11:35 shangyu.wh Exp $
 */
public class JerseyChannel implements Channel {

    private WebTarget webTarget;

    private Client    client;

    @Override
    public InetSocketAddress getRemoteAddress() {
        if (webTarget != null) {
            URI uri = webTarget.getUri();
            return new InetSocketAddress(uri.getHost(), uri.getPort());
        }
        return null;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return NetUtil.getLocalSocketAddress();
    }

    @Override
    public boolean isConnected() {
        if (client instanceof org.glassfish.jersey.client.JerseyClient) {
            return !((org.glassfish.jersey.client.JerseyClient) client).isClosed();
        }
        return false;
    }

    @Override
    public Object getAttribute(String key) {
        return null;
    }

    @Override
    public void setAttribute(String key, Object value) {

    }

    /**
     * Getter method for property <tt>webTarget</tt>.
     *
     * @return property value of webTarget
     */
    @Override
    public WebTarget getWebTarget() {
        return webTarget;
    }

    @Override
    public void close() {
        client.close();
    }

    /**
     * Setter method for property <tt>webTarget</tt>.
     *
     * @param webTarget  value to be assigned to property webTarget
     */
    public void setWebTarget(WebTarget webTarget) {
        this.webTarget = webTarget;
    }

    /**
     * Setter method for property <tt>client</tt>.
     *
     * @param client  value to be assigned to property client
     */
    public void setClient(Client client) {
        this.client = client;
    }
}