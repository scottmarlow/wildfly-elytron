/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.security.audit;

import static org.wildfly.common.Assert.checkNotNullParam;
import static org.wildfly.security._private.ElytronMessages.audit;

import java.io.IOException;
import java.net.InetAddress;
import java.util.logging.Level;

import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.handlers.SyslogHandler;
import org.jboss.logmanager.handlers.SyslogHandler.Facility;
import org.jboss.logmanager.handlers.SyslogHandler.Protocol;

/**
 * An {@link AuditEndpoint} that logs to syslog.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class SyslogAuditEndpoint implements AuditEndpoint {

    private volatile boolean accepting = true;

    private final SyslogHandler syslogHandler;

    /**
     *
     */
    SyslogAuditEndpoint(Builder builder) throws IOException {
        syslogHandler = new SyslogHandler(checkNotNullParam("serverAddress", builder.serverAddress), builder.port, Facility.SECURITY,
                null, builder.tcp ? Protocol.TCP : Protocol.UDP, checkNotNullParam("hostName", builder.hostName));

    }

    @Override
    public void accept(EventPriority t, String u) throws IOException {
        if (!accepting) return;

        synchronized(this) {
            if (!accepting) return;

            syslogHandler.doPublish(new ExtLogRecord(toLevel(t), u, SyslogAuditEndpoint.class.getName()));
        }
    }

    private static Level toLevel(EventPriority eventPriority) {
        switch (eventPriority) {
            case ALERT:
            case EMERGENCY:
            case CRITICAL:
            case ERROR:
                return Level.SEVERE;
            case WARNING:
                return Level.WARNING;
            case INFORMATIONAL:
                return Level.INFO;
            case OFF:
                throw audit.invalidEventPriority(eventPriority);
            default:
                return Level.FINEST;
        }
    }

    @Override
    public void close() throws IOException {
        accepting = false;

        synchronized(this) {
            syslogHandler.close();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private InetAddress serverAddress;
        private int port;
        private boolean tcp = true;
        private String hostName;

        Builder() {
        }

        /**
         * Set the server address syslog messages should be sent to.
         *
         * @param serverAddress the server address syslog messages should be sent to.
         * @return this builder.
         */
        public Builder setServerAddress(InetAddress serverAddress) {
            this.serverAddress = checkNotNullParam("serverAddress", serverAddress);

            return this;
        }

        /**
         * Set the port the syslog server is listening on.
         *
         * @param port the port the syslog server is listening on.
         * @return this builder.
         */
        public Builder setPort(int port) {
            this.port = port;

            return this;
        }

        /**
         * Set if the communication should be using TCP.
         *
         * @param tcp if the communication should be using TCP.
         * @return this builder.
         */
        public Builder setTcp(boolean tcp) {
            this.tcp = tcp;

            return this;
        }

        /**
         * Set the host name that should be sent within the syslog messages.
         *
         * @param hostName the host name that should be sent within the syslog messages.
         * @return this builder.
         */
        public Builder setHostName(String hostName) {
            this.hostName = checkNotNullParam("hostName", hostName);

            return this;
        }

        /**
         * Build a new {@link AuditEndpoint} configured to pass all messages using Syslog.
         *
         * @return a new {@link AuditEndpoint} configured to pass all messages using Syslog.
         * @throws IOException if an error occurs initialising the endpoint.
         */
        public AuditEndpoint build() throws IOException {
            return new SyslogAuditEndpoint(this);
        }

    }

}
