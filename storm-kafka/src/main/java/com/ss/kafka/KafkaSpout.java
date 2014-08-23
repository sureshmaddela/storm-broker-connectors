package com.ss.kafka;

import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichSpout;
import com.ss.commons.*;
import com.ss.kafka.consumer.ConsumerConfig;
import com.ss.kafka.consumer.KConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class KafkaSpout extends BaseRichSpout {
    private Logger LOG = LoggerFactory.getLogger(KafkaSpout.class);

    private SpoutConfigurator configurator;

    private transient SpoutOutputCollector collector;

    private Map<String, String> queueMessageMap = new HashMap<String, String>();

    private Map<String, KConsumer> messageConsumers = new HashMap<String, KConsumer>();

    private BlockingQueue<MessageContext> messages;

    private int prefetchCount = 0;

    private boolean isReQueueOnFail = false;

    private boolean autoAck = true;

    private DestinationChanger destinationChanger;

    public KafkaSpout(SpoutConfigurator configurator) {
        this.configurator = configurator;
        this.messages = new ArrayBlockingQueue<MessageContext>(configurator.queueSize());

        String prefetchCountString = configurator.getProperties().get("prefectCount");
        if (prefetchCountString != null) {
            prefetchCount = Integer.parseInt(prefetchCountString);
        }

        String isReQueueOnFailString = configurator.getProperties().get("reQueue");
        if (isReQueueOnFailString != null) {
            isReQueueOnFail = Boolean.parseBoolean(isReQueueOnFailString);
        }

        String ackModeStringValue = configurator.getProperties().get("ackMode");
        if (ackModeStringValue != null && ackModeStringValue.equals("manual")) {
            autoAck = false;
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        configurator.declareOutputFields(outputFieldsDeclarer);
    }

    @Override
    public void open(Map map, TopologyContext topologyContext, final SpoutOutputCollector spoutOutputCollector) {
        collector = spoutOutputCollector;
        final ConsumerConfig consumerConfig = new ConsumerConfig(null, null, null, null);
        destinationChanger = configurator.getDestinationChanger();
        destinationChanger.registerListener(new DestinationChangeListener() {
            @Override
            public void addDestination(String name, DestinationConfiguration destination) {
                KConsumer consumer = new KConsumer(null, messages, consumerConfig);
                consumer.start();
                messageConsumers.put(name, consumer);
            }

            @Override
            public void removeDestination(String name) {
                KConsumer consumer = messageConsumers.remove(name);
                if (consumer != null) {
                    consumer.stop();
                }
            }
        });

        destinationChanger.start();
    }

    @Override
    public void nextTuple() {
        MessageContext message;
        while ((message = messages.poll()) != null) {
            List<Object> tuple = extractTuple(message);
            if (!tuple.isEmpty()) {
                collector.emit(tuple, message.getId());
                if (!autoAck) {
                    queueMessageMap.put(message.getId(), message.getOriginDestination());
                }
            }
        }
    }

    @Override
    public void close() {
        destinationChanger.stop();
        for (KConsumer consumer : messageConsumers.values()) {
            consumer.stop();
        }
        super.close();
    }

    public List<Object> extractTuple(MessageContext delivery) {
        String deliveryTag = delivery.getId();
        try {
            List<Object> tuple = configurator.getMessageBuilder().deSerialize(delivery);
            if (tuple != null && !tuple.isEmpty()) {
                return tuple;
            }
            String errorMsg = "Deserialization error for msgId " + deliveryTag;
            LOG.warn(errorMsg);
            collector.reportError(new Exception(errorMsg));
        } catch (Exception e) {
            LOG.warn("Deserialization error for msgId " + deliveryTag, e);
            collector.reportError(e);
        }
        return Collections.emptyList();
    }
}