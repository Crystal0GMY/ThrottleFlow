package client1;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;
import java.nio.file.*;

public class MultithreadClient {
//  private static final String SERVER_URL = "http://ServletLoadBalancer-1316451551.us-west-2.elb.amazonaws.com:8080/Assignment3_war";
  private static final String SERVER_URL = "http://52.26.203.203:8080/Assignment3_war";
  private static final String LOG_FILE = "request_logs.csv";
  private static final int TOTAL_REQUESTS = 200000;
  private static final int INITIAL_THREAD_COUNT = 32;
  private static final int REQUESTS_PER_INITIAL_THREAD = 1000;
  private static final int REQUEST_PER_THREAD = 4000;
  private static final AtomicInteger successfulRequests = new AtomicInteger(0);
  private static final AtomicInteger failedRequests = new AtomicInteger(0);


  public static void main(String[] args) throws InterruptedException, ExecutionException {
    Path logFilePath = Paths.get(LOG_FILE);
    try {
      if (Files.exists(logFilePath)) {
        Files.delete(logFilePath);
        System.out.println("Deleted existing 'request_logs.csv'.");
      }
    } catch (IOException e) {
      System.out.println("Error deleting 'request_logs.csv': " + e.getMessage());
    }

    Thread eventGeneratorThread = new Thread(new EventGeneratorThread());
    eventGeneratorThread.start();

    ExecutorService executorService = Executors.newCachedThreadPool();  // Use cached thread pool
    List<Future<Void>> futures = new ArrayList<>();

    CountDownLatch latch = new CountDownLatch(1);

    long startTime = System.currentTimeMillis();

    // Create 32 threads to send 1000 POST requests each
    System.out.println("Starting the first 32 threads...");
    for (int i = 0; i < INITIAL_THREAD_COUNT; i++) {
      HTTPClientThread clientThread = new HTTPClientThread(SERVER_URL, successfulRequests, failedRequests, REQUESTS_PER_INITIAL_THREAD, latch);
      futures.add(executorService.submit(clientThread));
    }

    // Wait for any of the 32 initial threads to finish before creating more threads
    latch.await();
    System.out.println("One of the first 32 threads have completed. Starting additional threads...");

    int remainingRequests = TOTAL_REQUESTS - (INITIAL_THREAD_COUNT * REQUESTS_PER_INITIAL_THREAD);
    int threadsNeeded = remainingRequests  / REQUEST_PER_THREAD;


    for (int i = 0; i < threadsNeeded; i++) {
      HTTPClientThread clientThread = new HTTPClientThread(SERVER_URL, successfulRequests, failedRequests, REQUEST_PER_THREAD, null);
      futures.add(executorService.submit(clientThread));
    }

    for (Future<Void> future : futures) {
      future.get();
    }

    eventGeneratorThread.join();

    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      executorService.shutdownNow();
      Thread.currentThread().interrupt();
    }

    long endTime = System.currentTimeMillis();
    long wallTime = endTime - startTime;

    System.out.println("This is the Part 1 Output: ");
    System.out.println("Number of Threads used: " + (INITIAL_THREAD_COUNT + threadsNeeded));
    System.out.println("Total requests sent: " + TOTAL_REQUESTS);
    System.out.println("Successful requests: " + successfulRequests.get());
    System.out.println("Failed requests: " + failedRequests.get());
    System.out.println("Total wall time: " + wallTime + " ms");
    System.out.println("Throughput: " + (TOTAL_REQUESTS / (wallTime / 1000.0)) + " requests per second");

    // temporarily eliminate latency analyzer (which is used to do Little's Law calculation for Client 2)
    // not relevant to Assignment 2's results
//    LatencyAnalyzer.analyzeResults("request_logs.csv", TOTAL_REQUESTS);
  }

}
