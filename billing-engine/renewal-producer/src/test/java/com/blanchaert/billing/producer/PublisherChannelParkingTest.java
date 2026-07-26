package com.blanchaert.billing.producer;

import com.blanchaert.billing.producer.job.OutboxPublisher;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the spring-rabbit channel-lifecycle mechanism the publish window relies on:
 * with correlated confirms, a channel whose confirms are pending is parked by
 * CachingConnectionFactory (not returned to the cache), so every unconfirmed
 * in-flight send holds exactly one channel — unbounded pipelining under a
 * slow-confirming broker therefore opens a new channel per send, up to the
 * broker's channelMax. Once a channel's confirm arrives it re-enters the cache
 * and later sends reuse it instead of opening more.
 */
class PublisherChannelParkingTest {

    private CachingConnectionFactory ccf;

    @AfterEach
    void tearDown() {
        if (ccf != null) {
            ccf.destroy();
        }
    }

    @Test
    void unconfirmedSendsEachParkAChannelAndAckedChannelsAreReused() throws Exception {
        com.rabbitmq.client.ConnectionFactory clientFactory = mock(com.rabbitmq.client.ConnectionFactory.class);
        com.rabbitmq.client.Connection connection = mock(com.rabbitmq.client.Connection.class);
        when(clientFactory.newConnection(nullable(ExecutorService.class), anyString())).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);

        AtomicInteger channelNumbers = new AtomicInteger();
        Map<Channel, ConfirmListener> listenerByChannel = new ConcurrentHashMap<>();
        Map<Channel, Long> lastPublishedSeqByChannel = new ConcurrentHashMap<>();
        when(connection.createChannel()).thenAnswer(invocation -> {
            Channel channel = mock(Channel.class);
            when(channel.isOpen()).thenReturn(true);
            when(channel.getChannelNumber()).thenReturn(channelNumbers.incrementAndGet());
            AtomicLong seq = new AtomicLong(1);
            when(channel.getNextPublishSeqNo()).thenAnswer(i -> {
                lastPublishedSeqByChannel.put(channel, seq.get());
                return seq.getAndIncrement();
            });
            doAnswer(i -> {
                listenerByChannel.put(channel, i.getArgument(0));
                return null;
            }).when(channel).addConfirmListener(any(ConfirmListener.class));
            return channel;
        });

        ccf = new CachingConnectionFactory(clientFactory);
        ccf.setPublisherConfirmType(ConfirmType.CORRELATED);
        ccf.setPublisherReturns(true);
        ccf.afterPropertiesSet();
        RabbitTemplate template = new RabbitTemplate(ccf);
        template.setMandatory(true);
        OutboxPublisher publisher = new OutboxPublisher(
                template, new SimpleMeterRegistry(), "billing.renewals", "renewal.requested");

        // Phase 1: 20 sends, no confirm delivered -> each send parks its channel,
        // so each send opens a NEW channel.
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(publisher.publish("id-" + i, "{\"probe\":\"parking-" + i + "\"}"));
        }
        verify(connection, times(20)).createChannel();
        for (CompletableFuture<Boolean> future : futures) {
            assertThat(future).isNotDone();
        }

        // Phase 2: deliver every pending ack -> confirm futures complete true and
        // parked channels re-enter the cache (executor-dispatched, hence the
        // bounded in-process poll; default cache size 25 holds all 20).
        for (Map.Entry<Channel, ConfirmListener> entry : listenerByChannel.entrySet()) {
            entry.getValue().handleAck(lastPublishedSeqByChannel.get(entry.getKey()), false);
        }
        for (CompletableFuture<Boolean> future : futures) {
            assertThat(future.get(5, TimeUnit.SECONDS)).isTrue();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int idle = -1;
        while (idle != 20 && System.nanoTime() < deadline) {
            idle = Integer.parseInt(ccf.getCacheProperties().getProperty("idleChannelsNotTx"));
            if (idle != 20) {
                Thread.sleep(10);
            }
        }
        assertThat(idle).isEqualTo(20);

        // Phase 3: the next send reuses a cached channel -> still exactly 20 created.
        publisher.publish("id-20", "{\"probe\":\"parking-reuse\"}");
        verify(connection, times(20)).createChannel();
    }
}
