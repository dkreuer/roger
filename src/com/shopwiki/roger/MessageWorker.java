/*
 * Copyright [2012] [ShopWiki]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.shopwiki.roger;

import java.io.IOException;
import java.util.Map;

import com.rabbitmq.client.*;
import com.shopwiki.roger.RabbitReconnector.*;

/**
 * A glue class that packages together a {@link MessageHandler} and the RabbitMQ plumbing needed to use it.
 * The user is expected to instantiate one of these and call the {@link #start()} method to begin handling messages.
 *
 * @author rstewart
 */
public class MessageWorker<T> {

    private final RabbitConnector connector;
    private final MessageHandler<T> handler;
    private final Map<String,Object> queueArgs;
    private final Route route;
    public final RabbitReconnector reconnector;

    public MessageWorker(RabbitConnector connector, MessageHandler<T> handler, Map<String,Object> queueArgs, Route route, ReconnectLogger reconnectLogger) {
        this.connector = connector;
        this.handler = handler;
        this.queueArgs = queueArgs;
        this.route = route;

        ReconnectHandler reconnectHandler = new ReconnectHandler() {
            @Override
            public boolean reconnect() throws Exception {
                start();
                return true;
            }
        };

        reconnector = new RabbitReconnector(reconnectHandler, reconnectLogger, 10);
    }

    private volatile Channel channel;

    /**
     * Call this to start consuming & handling messages.
     */
    public void start() throws IOException {
        Connection conn = connector.getDaemonConnection(1);
        conn.addShutdownListener(reconnector);
        channel = conn.createChannel();

        MessageConsumer<T> consumer = new MessageConsumer<T>(handler, channel, queueArgs, route);
        consumer.start();
    }

    public void sendMessage(T message) throws IOException {
        MessagingUtil.sendMessage(channel, route, message);
    }
}
