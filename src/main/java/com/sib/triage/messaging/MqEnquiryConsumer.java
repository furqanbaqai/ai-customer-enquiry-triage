package com.sib.triage.messaging;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import com.sib.triage.config.AppConfig;
import com.sib.triage.service.TriagePipeline;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.JMSProducer;
import jakarta.jms.Queue;
import jakarta.jms.BytesMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageFormatException;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

public final class MqEnquiryConsumer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MqEnquiryConsumer.class);
    private final TriagePipeline pipeline;
    private final ExecutorService processingExecutor;
    private final JMSContext context;
    private final JMSContext backoutContext;
    private final JMSConsumer consumer;
    private final JMSProducer backoutProducer;
    private final Queue backoutQueue;

    public MqEnquiryConsumer(AppConfig.Mq config, TriagePipeline pipeline, ExecutorService processingExecutor) {
        this.pipeline = pipeline;
        this.processingExecutor = processingExecutor;
        try {
            var factory = connectionFactory(config);
            this.context = config.username().isBlank()
                    ? factory.createContext(JMSContext.AUTO_ACKNOWLEDGE)
                    : factory.createContext(config.username(), config.password(), JMSContext.AUTO_ACKNOWLEDGE);
            this.consumer = context.createConsumer(context.createQueue("queue:///" + config.queueName()));
            this.backoutContext = config.username().isBlank()
                    ? factory.createContext(JMSContext.AUTO_ACKNOWLEDGE)
                    : factory.createContext(config.username(), config.password(), JMSContext.AUTO_ACKNOWLEDGE);
            this.backoutProducer = backoutContext.createProducer();
            this.backoutQueue = backoutContext.createQueue("queue:///" + config.backoutQueueName());
        } catch (RuntimeException | JMSException e) {
            throw new IllegalStateException("Unable to initialize IBM MQ consumer", e);
        }
    }

    public void start() {
        consumer.setMessageListener(message -> {
            var correlationId = correlationId(message);
            try {
                // Copy the JMS body before the listener returns. A provider is not
                // required to keep a delivered Message usable by another thread.
                var payload = payload(message);
                processingExecutor.submit(() -> dispatch(payload, correlationId));
            } catch (JMSException e) {
                LOGGER.error("Unable to read MQ message correlationId={}", correlationId, e);
            }
        });
        context.start();
        LOGGER.info("IBM MQ consumer started");
    }

    private void dispatch(String payload, String correlationId) {
        try {
            pipeline.process(payload, correlationId).whenComplete((ignored, error) -> {
                if (isInvalidEnquiry(error)) sendToBackout(payload, correlationId, error);
            });
        } catch (Exception e) {
            LOGGER.error("Unable to dispatch MQ message correlationId={}", correlationId, e);
        }
    }

    private synchronized void sendToBackout(String payload, String correlationId, Throwable cause) {
        try {
            backoutProducer.setJMSCorrelationID(correlationId).send(backoutQueue, payload);
            LOGGER.warn("Invalid enquiry moved to backout queue correlationId={}", correlationId, cause);
        } catch (RuntimeException e) {
            LOGGER.error("Unable to move invalid enquiry to backout queue correlationId={}", correlationId, e);
        }
    }

    private static boolean isInvalidEnquiry(Throwable error) {
        while (error != null) {
            if (error instanceof TriagePipeline.InvalidEnquiryException) return true;
            error = error.getCause();
        }
        return false;
    }

    private static String payload(Message message) throws JMSException {
        return switch (message) {
            case TextMessage text -> text.getText();
            case BytesMessage bytes -> readUtf8(bytes);
            default -> {
                if (message.isBodyAssignableTo(String.class)) yield message.getBody(String.class);
                throw new MessageFormatException("Unsupported JMS message type: " + message.getClass().getName());
            }
        };
    }

    private static String readUtf8(BytesMessage message) throws JMSException {
        message.reset();
        var expectedLength = message.getBodyLength();
        if (expectedLength > Integer.MAX_VALUE) {
            throw new MessageFormatException("BytesMessage is too large to decode as JSON");
        }

        var output = new ByteArrayOutputStream((int) expectedLength);
        var buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = message.readBytes(buffer)) != -1) {
            if (bytesRead > 0) output.write(buffer, 0, bytesRead);
        }
        return output.toString(StandardCharsets.UTF_8);
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
        backoutContext.close();
    }
}
