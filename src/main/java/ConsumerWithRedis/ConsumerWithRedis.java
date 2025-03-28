package ConsumerWithRedis;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

public class ConsumerWithRedis {
  private static final String QUEUE_NAME = "skiers_queue";
  private static final String RABBITMQ_HOST = "54.148.199.48";
  private static final int NUM_OF_CONSUMERS = 5;
  private static final ConnectionFactory factory = new ConnectionFactory();
  private static Connection connection;
  private static final ExecutorService consumerExecutor = Executors.newCachedThreadPool();
  private static final ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor();
  private static final String REDIS_HOST = "35.92.33.82";
  private static final int REDIS_PORT = 6379;
  private static final JedisPool jedisPool = new JedisPool(new JedisPoolConfig(), REDIS_HOST, REDIS_PORT);

  public static void main(String[] args) {
    try {
      factory.setHost(RABBITMQ_HOST);
      factory.setUsername("myuser");
      factory.setPassword("mypassword");
      factory.setAutomaticRecoveryEnabled(true);
      factory.setRequestedHeartbeat(30);

      connection = factory.newConnection();

      // Start with the initial consumer count
      for (int i = 0; i < NUM_OF_CONSUMERS; i++) {
        consumerExecutor.submit(new ConsumerWorker());
      }

      // Graceful shutdown hook
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        System.out.println("Shutting down consumers...");
        try {
          monitorExecutor.shutdown();
          consumerExecutor.shutdown();
          if (!consumerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            consumerExecutor.shutdownNow();
          }
          if (!monitorExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            monitorExecutor.shutdownNow();
          }
          jedisPool.close();
          connection.close();
        } catch (Exception ignored) {}
      }));

    } catch (Exception e) {
      System.err.println("Error initializing consumer: " + e.getMessage());
    }
  }

  static class ConsumerWorker implements Runnable {
    @Override
    public void run() {
      try (Channel channel = connection.createChannel()) {
        channel.queueDeclare(QUEUE_NAME, true, false, false, null);
        channel.basicQos(10); // Prevents one consumer from taking too many messages

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
          String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
          boolean success = processMessage(message);
          if (success) {
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
//            sendAckResponse(channel, delivery);
          } else {
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
          }
        };

        System.out.println(Thread.currentThread().getName() + " waiting for messages...");
        channel.basicConsume(QUEUE_NAME, false, deliverCallback, consumerTag -> {});

        synchronized (this) {
          wait(); // Wait for an interrupt or shutdown signal to stop consuming
        }
      } catch (IOException e) {
        System.err.println("IOException encountered while setting up the consumer or consuming messages: " + e.getMessage());
        e.printStackTrace();  // Stack trace to help pinpoint the issue in more detail
      } catch (TimeoutException e) {
        System.err.println("TimeoutException encountered while setting up the consumer: " + e.getMessage());
        e.printStackTrace();  // Stack trace for more context
      } catch (InterruptedException e) {
        System.err.println("InterruptedException encountered while waiting or handling thread interruption: " + e.getMessage());
        Thread.currentThread().interrupt();  // Restore the interrupt flag
      } catch (Exception e) {
        System.err.println("Unexpected error in consumer thread: " + e.getMessage());
        e.printStackTrace();  // General exception handling for any unexpected errors
      }
    }

private boolean processMessage(String message) {
  try (Jedis jedis = jedisPool.getResource()) {
    JsonObject jsonObject = null;
    try {
      jsonObject = JsonParser.parseString(message).getAsJsonObject();
    } catch (JsonSyntaxException e) {
      System.err.println("Error parsing JSON message: " + message);
      e.printStackTrace();
      return false;
    }

    // Validate and extract fields with error handling for missing or incorrect data types
    Integer resortID = null, seasonID = null, dayID = null, skierID = null, time = null, liftID = null;

    try {
      resortID = jsonObject.get("resortID").getAsInt();
    } catch (Exception e) {
      System.err.println("Error parsing 'resortID' field: " + message);
      return false;
    }

    try {
      seasonID = jsonObject.get("seasonID").isJsonPrimitive() && jsonObject.get("seasonID").getAsJsonPrimitive().isNumber()
          ? jsonObject.get("seasonID").getAsInt()
          : Integer.parseInt(jsonObject.get("seasonID").getAsString());
    } catch (Exception e) {
      System.err.println("Error parsing 'seasonID' field: " + message);
      return false;
    }

    try {
      dayID = jsonObject.get("dayID").isJsonPrimitive() && jsonObject.get("dayID").getAsJsonPrimitive().isNumber()
          ? jsonObject.get("dayID").getAsInt()
          : Integer.parseInt(jsonObject.get("dayID").getAsString());
    } catch (Exception e) {
      System.err.println("Error parsing 'dayID' field: " + message);
      return false;
    }

    try {
      skierID = jsonObject.get("skierID").getAsInt();
    } catch (Exception e) {
      System.err.println("Error parsing 'skierID' field: " + message);
      return false;
    }

    try {
      time = jsonObject.get("time").getAsInt();
    } catch (Exception e) {
      System.err.println("Error parsing 'time' field: " + message);
      return false;
    }

    try {
      liftID = jsonObject.get("liftID").getAsInt();
    } catch (Exception e) {
      System.err.println("Error parsing 'liftID' field: " + message);
      return false;
    }

    // Create Redis key and store data
    String redisKey = String.format("skier:%d:rides", skierID);

    Map<String, String> rideData = new HashMap<>();
    rideData.put("resortID", String.valueOf(resortID));
    rideData.put("seasonID", String.valueOf(seasonID));
    rideData.put("dayID", String.valueOf(dayID));
    rideData.put("time", String.valueOf(time));
    rideData.put("liftID", String.valueOf(liftID));

    try {
      jedis.hmset(redisKey, rideData);
      System.out.println("Stored in Redis: " + redisKey);
    } catch (JedisException e) {
      System.err.println("Error writing to Redis for key " + redisKey + ": " + e.getMessage());
      return false;
    }

    return true;
  } catch (Exception e) {
    System.err.println("General error processing message: " + message);
    e.printStackTrace();
    return false;
  }
}

//    private void sendAckResponse(Channel channel, Delivery delivery) {
//      try {
//        String correlationId = delivery.getProperties().getCorrelationId();
//        String responseMessage = "{\"status\": \"acknowledged\"}";
//        AMQP.BasicProperties responseProps = new AMQP.BasicProperties
//            .Builder()
//            .correlationId(correlationId) // Ensure the correlation ID matches
//            .build();
//
//        String replyQueue = delivery.getProperties().getReplyTo();
//        if (replyQueue == null) {
//          System.out.println(delivery.getProperties().getCorrelationId());
//          System.err.println("Missing reply queue for message: " + new String(delivery.getBody(), StandardCharsets.UTF_8));
//          return;
//        }
//        channel.basicPublish("", replyQueue, responseProps, responseMessage.getBytes(StandardCharsets.UTF_8));
//        System.out.println("Sent ack response: " + responseMessage);
//      } catch (IOException e) {
//        System.err.println("Error sending ack response: " + e.getMessage());
//      }
//    }

  }
}
