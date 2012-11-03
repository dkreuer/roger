package com.shopwiki.messaging.rpc;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import org.codehaus.jackson.type.TypeReference;

import com.google.common.util.concurrent.AbstractFuture;
import com.rabbitmq.client.*;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.shopwiki.messaging.*;

/**
 * @owner rstewart
 */
public class RpcClient {

    private final Channel channel;
    private final Route requestRoute;
    private final boolean exceptionsAsJson;

    private final MessageConsumer<MapMessage> responseConsumer;

    private final Map<String,RpcFuture> idToFuture = new ConcurrentHashMap<String,RpcFuture>();

    public RpcClient(Channel channel, Route requestRoute, boolean exceptionsAsJson) throws IOException {
        this.channel = channel;
        this.requestRoute = requestRoute;
        this.exceptionsAsJson = exceptionsAsJson;

        ResponseHandler handler = new ResponseHandler();
        responseConsumer = new MessageConsumer<MapMessage>(handler, channel, null);
        responseConsumer.start();
    }

    private class ResponseHandler implements MessageHandler<MapMessage> {

        @Override
        public TypeReference<MapMessage> getMessageType() {
            return MapMessage.TYPE_REF; // TODO: Make generic ???
        }

        @Override
        public void handleMessage(MapMessage body, BasicProperties props) {
            RpcFuture future = idToFuture.remove(props.getCorrelationId());
            if (future == null) {
                System.err.println("### Received a response not meant for me! ###");
                System.err.println("### " + MessagingUtil.prettyPrint(props));
                System.err.println("### " + MessagingUtil.prettyPrintMessage(body));
                return;
            }

            RpcResponse response = new RpcResponse(props, body);

            if (exceptionsAsJson) {
                future.complete(response); // return JSON regardless of the status
            } else {
                future.completeExcept(response);
            }
        }
    }

    public Future<RpcResponse> sendRequest(Object request) throws IOException {
        String id = java.util.UUID.randomUUID().toString();
        String replyQueue = responseConsumer.getQueueName();
        MessagingUtil.sendRequest(channel, requestRoute, request, replyQueue, id);

        RpcFuture future = new RpcFuture();
        idToFuture.put(id, future);
        return future;
    }

    private static class RpcFuture extends AbstractFuture<RpcResponse> {
        private void complete(RpcResponse response) {
            set(response);
        }

        private void completeExcept(RpcResponse response) {
            ResponseStatus status = response.getStatus();
            if (status == ResponseStatus.OK) {
                set(response);
            } else {
                MapMessage body = response.getBody();
                String exceptionName = (String)body.get("exceptionName");
                String exceptionMsg  = (String)body.get("exceptionMsg");
                Exception e = new Exception(status + ": " + exceptionName + "\n" + exceptionMsg);
                setException(e);
            }
        }
    }
}