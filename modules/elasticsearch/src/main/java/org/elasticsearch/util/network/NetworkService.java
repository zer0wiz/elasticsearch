/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.util.network;

import org.elasticsearch.monitor.os.JmxOsProbe;
import org.elasticsearch.monitor.os.OsService;
import org.elasticsearch.util.MapBuilder;
import org.elasticsearch.util.collect.ImmutableMap;
import org.elasticsearch.util.component.AbstractComponent;
import org.elasticsearch.util.inject.Inject;
import org.elasticsearch.util.settings.Settings;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Enumeration;

/**
 * @author kimchy (shay.banon)
 */
public class NetworkService extends AbstractComponent {

    public static final String LOCAL = "#local#";

    private static final String GLOBAL_NETWORK_HOST_SETTING = "network.host";
    private static final String GLOBAL_NETWORK_BINDHOST_SETTING = "network.bind_host";
    private static final String GLOBAL_NETWORK_PUBLISHHOST_SETTING = "network.publish_host";

    public static final class TcpSettings {
        public static final String TCP_NO_DELAY = "network.tcp.no_delay";
        public static final String TCP_KEEP_ALIVE = "network.tcp.keep_alive";
        public static final String TCP_REUSE_ADDRESS = "network.tcp.reuse_address";
        public static final String TCP_SEND_BUFFER_SIZE = "network.tcp.send_buffer_size";
        public static final String TCP_RECEIVE_BUFFER_SIZE = "network.tcp.receive_buffer_size";
    }

    public static interface CustomNameResolver {
        InetAddress resolve();
    }

    private volatile ImmutableMap<String, CustomNameResolver> customNameResolvers = ImmutableMap.of();

    public NetworkService(Settings settings) {
        this(settings, new OsService(settings, new JmxOsProbe(settings)));
    }

    @Inject public NetworkService(Settings settings, OsService service) {
        super(settings);

        if (logger.isDebugEnabled()) {
            StringBuilder netDebug = new StringBuilder("net_info");
            try {
                Enumeration<NetworkInterface> enum_ = NetworkInterface.getNetworkInterfaces();
                String hostName = InetAddress.getLocalHost().getHostName();
                netDebug.append("\nhost [").append(hostName).append("]\n");
                while (enum_.hasMoreElements()) {
                    NetworkInterface net = enum_.nextElement();

                    netDebug.append(net.getName()).append('\t').append("display_name [").append(net.getDisplayName()).append("]\n");
                    Enumeration<InetAddress> addresses = net.getInetAddresses();
                    netDebug.append("\t\taddress ");
                    while (addresses.hasMoreElements()) {
                        netDebug.append("[").append(addresses.nextElement()).append("] ");
                    }
                    netDebug.append('\n');
                    netDebug.append("\t\tmtu [").append(net.getMTU()).append("] multicast [").append(net.supportsMulticast()).append("] ptp [").append(net.isPointToPoint())
                            .append("] loopback [").append(net.isLoopback()).append("] up [").append(net.isUp()).append("] virtual [").append(net.isVirtual()).append("]")
                            .append('\n');
                }
            } catch (Exception ex) {
                netDebug.append("Failed to get Network Interface Info [" + ex.getMessage() + "]");
            }
            logger.debug(netDebug.toString());
        }

        if (logger.isTraceEnabled()) {
            logger.trace("ifconfig\n\n" + service.ifconfig());
        }
    }

    public void addCustomNameResolver(String name, CustomNameResolver customNameResolver) {
        if (!(name.startsWith("#") && name.endsWith("#"))) {
            name = "#" + name + "#";
        }
        customNameResolvers = MapBuilder.<String, CustomNameResolver>newMapBuilder().putAll(customNameResolvers).put(name, customNameResolver).immutableMap();
    }


    public InetAddress resolveBindHostAddress(String bindHost) throws IOException {
        return resolveBindHostAddress(bindHost, null);
    }

    public InetAddress resolveBindHostAddress(String bindHost, String defaultValue2) throws IOException {
        return resolveInetAddress(bindHost, settings.get(settings.get(GLOBAL_NETWORK_BINDHOST_SETTING), settings.get(GLOBAL_NETWORK_HOST_SETTING)), defaultValue2);
    }

    public InetAddress resolvePublishHostAddress(String publishHost) throws IOException {
        InetAddress address = resolvePublishHostAddress(publishHost, null);
        // verify that its not a local address
        if (address == null || address.isAnyLocalAddress()) {
            address = NetworkUtils.getLocalAddress();
        }
        return address;
    }

    public InetAddress resolvePublishHostAddress(String publishHost, String defaultValue2) throws IOException {
        return resolveInetAddress(publishHost, settings.get(settings.get(GLOBAL_NETWORK_PUBLISHHOST_SETTING), settings.get(GLOBAL_NETWORK_HOST_SETTING)), defaultValue2);
    }

    public InetAddress resolveInetAddress(String host, String defaultValue1, String defaultValue2) throws UnknownHostException, IOException {
        if (host == null) {
            host = defaultValue1;
        }
        if (host == null) {
            host = defaultValue2;
        }
        if (host == null) {
            return null;
        }
        if (host.startsWith("#") && host.endsWith("#")) {
            host = host.substring(1, host.length() - 1);

            CustomNameResolver customNameResolver = customNameResolvers.get(host);
            if (customNameResolver != null) {
                return customNameResolver.resolve();
            }

            if (host.equals("local")) {
                return NetworkUtils.getLocalAddress();
            } else {
                Collection<NetworkInterface> allInterfs = NetworkUtils.getAllAvailableInterfaces();
                for (NetworkInterface ni : allInterfs) {
                    if (!ni.isUp() || ni.isLoopback()) {
                        continue;
                    }
                    if (host.equals(ni.getName()) || host.equals(ni.getDisplayName())) {
                        return NetworkUtils.getFirstNonLoopbackAddress(ni, NetworkUtils.getIpStackType());
                    }
                }
            }
            throw new IOException("Failed to find network interface for [" + host + "]");
        }
        return InetAddress.getByName(host);
    }
}
