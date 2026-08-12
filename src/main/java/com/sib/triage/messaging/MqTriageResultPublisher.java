package com.sib.triage.messaging;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.config.AppConfig;
import com.sib.triage.domain.TriageResult;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.JMSProducer;
import jakarta.jms.Queue;

/**
 * Publishes completed triage outcomes to the IBM MQ result queue.
 *
 * <p>The publisher serializes the domain result model to JSON, attaches the correlation ID to the
 * JMS message, and sends it to the configured downstream queue for routing to the next system.
 */
public final class MqTriageResultPublisher implements TriageResultPublisher, AutoCloseable {
    private final JMSContext context;
    private final JMSProducer producer;
    private final Queue resultQueue;
    private final ObjectMapper mapper;

    /**
     * Opens a JMS context for the result queue and prepares a producer for outbound messages.
     *
     * @param config MQ connection and queue metadata
     * @param mapper JSON mapper used to serialize the triage result payload
     */
    public MqTriageResultPublisher(AppConfig.Mq config, ObjectMapper mapper) {
        this.mapper = mapper;
        try {
            var factory = connectionFactory(config);
            context = config.username().isBlank()
                    ? factory.createContext(JMSContext.AUTO_ACKNOWLEDGE)
                    : factory.createContext(config.username(), config.password(), JMSContext.AUTO_ACKNOWLEDGE);
            producer = context.createProducer();
            resultQueue = context.createQueue("queue:///" + config.resultQueueName());
        } catch (RuntimeException | JMSException e) {
            throw new IllegalStateException("Unable to initialize IBM MQ result publisher", e);
        }
    }

    /**
     * Publishes a final triage result onto the result queue with the same correlation ID used for
     * the originating enquiry.
     *
     * @param result completed triage outcome to emit downstream
     * @param correlationId message correlation identifier for tracing
     */
    @Override
    public synchronized void publish(TriageResult result, String correlationId) {
        producer.setJMSCorrelationID(correlationId).send(resultQueue, serialize(mapper, result));
    }

    /**
     * Serializes the domain model to the JSON contract expected by the downstream system.
     *
     * @param mapper Jackson mapper used for serialization
     * @param result triage result to serialize
     * @return JSON payload for the result queue
     */
    static String serialize(ObjectMapper mapper, TriageResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize triage result", e);
        }
    }

    private static MQConnectionFactory connectionFactory(AppConfig.Mq config) throws JMSException {
        var factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setHostName(config.host());
        factory.setPort(config.port());
        factory.setChannel(config.channel());
        factory.setQueueManager(config.queueManager());
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        return factory;
    }

    @Override
    public void close() {
        context.close();
    }
}
