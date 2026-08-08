package com.sib.triage.messaging;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import com.sib.triage.config.AppConfig;
import com.sib.triage.service.TriagePipeline;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

public final class MqEnquiryConsumer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MqEnquiryConsumer.class);
    private final TriagePipeline pipeline;
    private final ExecutorService processingExecutor;
    private final JMSContext context;
    private final JMSConsumer consumer;

    public MqEnquiryConsumer(AppConfig.Mq config, TriagePipeline pipeline, ExecutorService processingExecutor) {
        this.pipeline = pipeline;
        this.processingExecutor = processingExecutor;
        try {
            var factory = connectionFactory(config);
            this.context = config.username().isBlank()
                    ? factory.createContext(JMSContext.AUTO_ACKNOWLEDGE)
                    : factory.createContext(config.username(), config.password(), JMSContext.AUTO_ACKNOWLEDGE);
            this.consumer = context.createConsumer(context.createQueue("queue:///" + config.queueName()));
        } catch (RuntimeException | JMSException e) {
            throw new IllegalStateException("Unable to initialize IBM MQ consumer", e);
        }
    }

    public void start() {
        consumer.setMessageListener(message -> {
            var correlationId = correlationId(message);
            processingExecutor.submit(() -> dispatch(message, correlationId));
        });
        context.start();
        LOGGER.info("IBM MQ consumer started");
    }

    private void dispatch(Message message, String correlationId) {
        try {
            var payload = switch (message) {
                case TextMessage text -> text.getText();
                default -> message.getBody(String.class);
            };
            pipeline.process(payload, correlationId);
        } catch (Exception e) {
            LOGGER.error("Unable to dispatch MQ message correlationId={}", correlationId, e);
        }
    }

    private static String correlationId(Message message) {
        try {
            var value = message.getJMSCorrelationID();
            if (value == null || value.isBlank()) value = message.getJMSMessageID();
            return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
        } catch (JMSException e) { return UUID.randomUUID().toString(); }
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

    @Override public void close() {
        consumer.close();
        context.close();
    }
}
