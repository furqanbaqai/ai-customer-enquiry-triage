package com.sib.triage.messaging;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import com.sib.triage.config.AppConfig;
import com.sib.triage.service.TriagePipeline;
import com.sib.triage.persistence.SqlServerTriageRepository;
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

/**
 * Consumes inbound customer-enquiry messages from the request queue and hands each payload to the
 * triage pipeline for validation, AI classification, and persistence.
 *
 * <p>Processing is deliberately offloaded from the JMS listener thread onto a virtual-thread
 * executor so slow AI inference does not block MQ delivery for subsequent messages.</p>
 */
public final class MqEnquiryConsumer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MqEnquiryConsumer.class);
    private final TriagePipeline pipeline;
    private final ExecutorService processingExecutor;
    private final JMSContext context;
    private final JMSContext backoutContext;
    private final JMSConsumer consumer;
    private final JMSProducer backoutProducer;
    private final Queue backoutQueue;

    /**
     * Opens the IBM MQ consumer and backout producer for the configured request and failure queues.
     *
     * @param config MQ metadata including request and backout queue names
     * @param pipeline orchestration layer that handles validation, AI invocation, and result publication
     * @param processingExecutor executor used to execute the asynchronous pipeline work
     */
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

    /**
     * Starts the JMS listener and delegates each message to the virtual-thread executor.
     */
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

    /**
     * Submits the retrieved payload to the triage pipeline and routes infrastructure-level failures
     * to the configured backout queue.
     *
     * @param payload raw request body copied from the JMS message
     * @param correlationId request tracing value used across processing stages
     */
    private void dispatch(String payload, String correlationId) {
        try {
            pipeline.process(payload, correlationId).whenComplete((ignored, error) -> {
                if (requiresBackout(error)) sendToBackout(payload, correlationId, error);
            });
        } catch (Exception e) {
            LOGGER.error("Unable to dispatch MQ message correlationId={}", correlationId, e);
        }
    }

    /**
     * Moves a failed payload to the backout queue so the original request can be inspected or replayed.
     *
     * @param payload raw request body copied from the original JMS message
     * @param correlationId trace identifier associated with the failed message
     * @param cause underlying fault that caused the move to backout
     */
    private synchronized void sendToBackout(String payload, String correlationId, Throwable cause) {
        try {
            backoutProducer.setJMSCorrelationID(correlationId).send(backoutQueue, payload);
            LOGGER.warn("Failed enquiry moved to backout queue correlationId={}", correlationId, cause);
        } catch (RuntimeException e) {
            LOGGER.error("Unable to move failed enquiry to backout queue correlationId={}", correlationId, e);
        }
    }

    /**
     * Determines whether a pipeline failure should be preserved on the backout queue.
     *
     * <p>Schema validation and tracker persistence issues are treated as recoverable infrastructure
     * problems that require forensic preservation; AI or routing failures remain in the tracker,
     * allowing the system to record the actual reason without replaying the original payload.</p>
     *
     * @param error nested failure thrown by the asynchronous pipeline
     * @return true when the message should be forwarded to the backout queue
     */
    private static boolean requiresBackout(Throwable error) {
        // Walk CompletionStage wrappers so only validation/persistence failures are
        // copied to backout; ordinary AI failures remain recorded in the tracker.
        while (error != null) {
            if (error instanceof TriagePipeline.InvalidEnquiryException
                    || error instanceof SqlServerTriageRepository.PersistenceException) return true;
            error = error.getCause();
        }
        return false;
    }

    /**
     * Reads the body from the inbound JMS message regardless of whether the source is TextMessage or BytesMessage.
     *
     * @param message received JMS message
     * @return message body decoded as UTF-8 text
     * @throws JMSException when the provider cannot access the body or the message type is unsupported
     */
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

    /**
     * Decodes a bytes-based JMS body into UTF-8 text so the pipeline can process either text or binary MQ payloads.
     *
     * @param message received bytes payload
     * @return ASCII/UTF-8 text representation of the original message body
     * @throws JMSException if the body cannot be read
     */
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

    /**
     * Resolves the trace identifier for a JMS message, preferring the provider correlation ID and
     * falling back to the message ID when not set.
     *
     * @param message JMS message being processed
     * @return correlation ID for this message, or a generated UUID when neither value is available
     */
    private static String correlationId(Message message) {
        try {
            var value = message.getJMSCorrelationID();
            if (value == null || value.isBlank()) value = message.getJMSMessageID();
            return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
        } catch (JMSException e) { return UUID.randomUUID().toString(); }
    }

    /**
     * Creates the MQ client factory using the WMQ client transport configuration.
     *
     * @param config IBM MQ settings for host, port, channel, queue manager, and credentials
     * @return configured MQ JMS connection factory
     * @throws JMSException when the connection factory cannot be configured
     */
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

    /**
     * Closes the JMS resources associated with the consumer and backout producer.
     */
    @Override public void close() {
        consumer.close();
        context.close();
        backoutContext.close();
    }
}
