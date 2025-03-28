package Producer;

import com.rabbitmq.client.*;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

public class MessageQueueProducer {
  private static final String QUEUE_NAME = "skiers_queue";
  private static final int POOL_SIZE = 100;
  private Connection connection;
  private BlockingQueue<Channel> channelPool;

  public MessageQueueProducer(String host, String username, String password) throws Exception {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(host);
    factory.setUsername(username);
    factory.setPassword(password);
    connection = factory.newConnection();

    // Initialize a pool of channels
    channelPool = new ArrayBlockingQueue<>(POOL_SIZE);
    for (int i = 0; i < POOL_SIZE; i++) {
      Channel channel = connection.createChannel();
      channel.queueDeclare(QUEUE_NAME, true, false, false, null);
      channelPool.offer(channel);
    }

    Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
  }

  public void sendMessage(JsonObject message) throws IOException, InterruptedException {
    Channel channel = channelPool.take();
//    String correlationId = UUID.randomUUID().toString();
//
//    String replyQueueName = channel.queueDeclare().getQueue();
//
//    AMQP.BasicProperties props = new AMQP.BasicProperties
//        .Builder()
//        .correlationId(correlationId)
//        .replyTo(replyQueueName)
//        .build();

    channel.basicPublish("", QUEUE_NAME, null, message.toString().getBytes(StandardCharsets.UTF_8));

//    final BlockingQueue<String> responseQueue = new ArrayBlockingQueue<>(1);
//    DeliverCallback deliverCallback = (consumerTag, delivery) -> {
//      if (delivery.getProperties().getCorrelationId().equals(correlationId)) {
//        responseQueue.offer(new String(delivery.getBody(), StandardCharsets.UTF_8));
//      }
//    };
//
//    channel.basicConsume(replyQueueName, true, deliverCallback, consumerTag -> {});
//
//    String ackResponse = responseQueue.poll(5, java.util.concurrent.TimeUnit.SECONDS);

    // Return the channel to the pool
    channelPool.offer(channel);

//    return ackResponse;
  }

  // Gracefully shutdown the producer, close all channels and the connection
  public void shutdown() {
    System.out.println("Shutting down RabbitMQ Producer...");
    try {
      // Close all channels in the pool
      for (Channel channel : channelPool) {
        if (channel != null && channel.isOpen()) {
          channel.close();
        }
      }

      // Close the connection
      if (connection != null && connection.isOpen()) {
        connection.close();
      }
    } catch (IOException | TimeoutException e) {
      e.printStackTrace();
    }
  }
}
