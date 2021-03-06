package com.ss.rabbitmq;

import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichSpout;
import com.ss.commons.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class RabbitMQSpout extends BaseRichSpout {
    private Logger logger;

    private ErrorReporter reporter;

    private SpoutConfigurator configurator;

    private transient SpoutOutputCollector collector;

    private Map<String, String> queueMessageMap = new HashMap<String, String>();

    private Map<String, MessageConsumer> messageConsumers = new HashMap<String, MessageConsumer>();

    private BlockingQueue<MessageContext> messages;

    private int prefetchCount = 0;

    private boolean isReQueueOnFail = false;

    private boolean autoAck = true;

    private DestinationChanger destinationChanger;

    public RabbitMQSpout(SpoutConfigurator configurator, ErrorReporter reporter) {
        this(configurator, reporter, LoggerFactory.getLogger(RabbitMQSpout.class));
    }

    public RabbitMQSpout(SpoutConfigurator configurator, ErrorReporter reporter, Logger logger) {
        this.configurator = configurator;
        this.reporter = reporter;
        this.logger = logger;
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
    public void open(Map map, TopologyContext context, final SpoutOutputCollector spoutOutputCollector) {
        collector = spoutOutputCollector;

        destinationChanger = configurator.getDestinationChanger();
        destinationChanger.registerListener(new DestinationChangeListener() {
            @Override
            public void addDestination(String name, DestinationConfiguration destination) {
                MessageConsumer consumer = new MessageConsumer(messages, destination, reporter, logger, prefetchCount, isReQueueOnFail, autoAck);
                consumer.openConnection();
                messageConsumers.put(name, consumer);
            }

            @Override
            public void removeDestination(String name) {
                MessageConsumer consumer = messageConsumers.remove(name);
                if (consumer != null) {
                    consumer.close();
                }
            }

            @Override
            public void addPathToDestination(String name, String path) {

            }

            @Override
            public void removePathToDestination(String name, String path) {

            }
        });
        final int totalTasks = context.getComponentTasks(context.getThisComponentId()).size();
        final int taskIndex = context.getThisTaskIndex();
        destinationChanger.setTask(taskIndex, totalTasks);
        destinationChanger.start();
    }

    @Override
    public void nextTuple() {
        MessageContext message;
        try {
            while ((message = messages.take()) != null) {
                List<Object> tuple = extractTuple(message);
                if (!tuple.isEmpty()) {
                    if (configurator.getStream() == null) {
                        collector.emit(tuple, message.getId());
                    } else {
                        collector.emit(configurator.getStream(), tuple, message.getId());
                    }
                    if (!autoAck) {
                        queueMessageMap.put(message.getId(), message.getOriginDestination());
                    }
                }
            }
        } catch (InterruptedException ignore) {
        }
    }

    @Override
    public void ack(Object msgId) {
        if (msgId instanceof String) {
            if (!autoAck) {
                String name =  queueMessageMap.remove(msgId);
                if (name != null) {
                    MessageConsumer consumer = messageConsumers.get(name);
                    if (consumer != null) {
                        consumer.ackMessage(Long.parseLong(msgId.toString()));
                    }
                }
            }
        }
    }

    @Override
    public void fail(Object msgId) {
        if (msgId instanceof String) {
            if (!autoAck) {
                String name =  queueMessageMap.remove(msgId);
                MessageConsumer consumer = messageConsumers.get(name);
                consumer.failMessage(Long.parseLong(msgId.toString()));
            }
        }
    }

    @Override
    public void close() {
        destinationChanger.stop();
        for (MessageConsumer consumer : messageConsumers.values()) {
            consumer.close();
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
            logger.warn(errorMsg);
            collector.reportError(new Exception(errorMsg));
        } catch (Exception e) {
            logger.warn("Deserialization error for msgId " + deliveryTag, e);
            collector.reportError(e);
        }
        MessageConsumer consumer = messageConsumers.get(delivery.getOriginDestination());
        if (consumer != null) {
            consumer.deadLetter(Long.parseLong(delivery.getId()));
        }
        return Collections.emptyList();
    }
}
