package com.sib.triage.messaging;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import com.sib.triage.config.AppConfig;
import com.sib.triage.domain.TriageResult;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.JMSProducer;
import jakarta.jms.Queue;

public final class MqTriageResultPublisher implements TriageResultPublisher, AutoCloseable {
    private final JMSContext context;
    private final JMSProducer producer;
    private final Queue resultQueue;

    public MqTriageResultPublisher(AppConfig.Mq config) {
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

    @Override
    public synchronized void publish(TriageResult result, String correlationId) {
        producer.setJMSCorrelationID(correlationId).send(resultQueue, result.content());
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
