package client1;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;


public class HTTPClientThread implements Callable {
  private static final HttpClient client = HttpClient.newHttpClient();
  private final String baseUrl;
  private final AtomicInteger successfulRequests;
  private final AtomicInteger failedRequests;
  private final Integer requestPerThread;
  private final CountDownLatch latch; // For initial 32 threads
  private final String LOG_FILE = "request_logs.csv";
  private static final int CIRCUIT_BREAKER_TIMEOUT = 100;
  private static volatile boolean circuitBreakerOpen = false;

  public HTTPClientThread(String baseUrl, AtomicInteger successfulRequests, AtomicInteger failedRequests, Integer requestPerThread, CountDownLatch latch) {
    this.baseUrl = baseUrl;
    this.successfulRequests = successfulRequests;
    this.failedRequests = failedRequests;
    this.requestPerThread = requestPerThread;
    this.latch = latch;
  }

  @Override
  public Void call() {
    try (PrintWriter writer = new PrintWriter(new FileWriter(LOG_FILE, true))) {
      for (int i = 0; i < requestPerThread; i++) {
        while (circuitBreakerOpen || checkPublishRate()) {
          circuitBreakerOpen = true;
          try {
            Thread.sleep(CIRCUIT_BREAKER_TIMEOUT);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          circuitBreakerOpen = false;
        }
        SkierLiftRideEvent event = EventGeneratorThread.getEvent();

        String eventUrl = String.format(
            "%s/skiers/%d/seasons/%d/days/%d/skiers/%d",
            baseUrl, event.getResortID(), event.getSeasonID(), event.getDayID(), event.getSkierID()
        );

        Gson gson = new Gson();
        String json = gson.toJson(event);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(eventUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        int retries = 0;
        while (retries < 5) {
          long startTime = System.currentTimeMillis();
          try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long endTime = System.currentTimeMillis();
            long latency = endTime - startTime;
            double throughput = 1000.0 / latency;

            synchronized (HTTPClientThread.class) {
              writer.printf("%d,POST,%d,%d,%.2f%n", startTime, latency, response.statusCode(), throughput);
              writer.flush();
            }

            if (response.statusCode() == 201) {
              successfulRequests.incrementAndGet();
              break;
            } else {
              System.out.println(response.body());
              retries++;
            }
          } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            long latency = endTime - startTime;
            double throughput = 1000.0 / latency;

            synchronized (HTTPClientThread.class) {
              writer.printf("%d,POST,%d,500,%.2f%n", startTime, latency, throughput);
              writer.flush();
            }

            retries++;
            if (retries >= 5) {
              failedRequests.incrementAndGet();
              break;
            }
            TimeUnit.SECONDS.sleep(1);
          }
        }
      }
    }  catch (InterruptedException | IOException e) {
      throw new RuntimeException(e);
    } finally {
      if (latch != null) {
        latch.countDown(); // Decrement latch count after the initial 32 threads complete
      }
    }
    return null;
  }


  private static boolean checkPublishRate() {
    double publishRate = getRabbitMQMessageRate();
    return publishRate > 1000;
  }

  private static double getRabbitMQMessageRate() {
    String queueName = "skiers_queue";
    String rabbitMQHost = "54.148.199.48";
    String apiUrl = "http://" + rabbitMQHost + ":15672/api/queues/%2F/" + queueName;
    String username = "myuser";
    String password = "mypassword";

    try {
      URL url = new URL(apiUrl);
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("GET");
      String auth = username + ":" + password;
      String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
      connection.setRequestProperty("Authorization", "Basic " + encodedAuth);

      int responseCode = connection.getResponseCode();
      if (responseCode == 200) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          response.append(line);
        }
        reader.close();

        JSONObject jsonResponse = new JSONObject(response.toString());
        if (jsonResponse.has("message_stats") && jsonResponse.getJSONObject("message_stats").has("publish_details")) {
          return jsonResponse.getJSONObject("message_stats").getJSONObject("publish_details").getDouble("rate");
        } else {
          System.out.println("Warning: 'publish_details' not found in API response.");
        }
        System.out.println("Failed to fetch queue size. HTTP response code: " + responseCode);
      }
    } catch (Exception e) {
      System.out.println("Error fetching RabbitMQ queue size: " + e.getMessage());
    }
    return -1; // Return -1 in case of failure
  }
}
