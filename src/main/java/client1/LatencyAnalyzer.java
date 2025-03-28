package client1;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class LatencyAnalyzer {
  private static final int totalRequests = 200000;
  public static void analyzeResults(String logFile, int totalRequests) {
    List<Long> latencies = new ArrayList<>();
    long totalLatency = 0;
    long minLatency = Long.MAX_VALUE;
    long maxLatency = Long.MIN_VALUE;
    List<Long> startTimes = new ArrayList<>();
    List<Long> endTimes = new ArrayList<>();

    List<Long> activeRequestsPerTime = new ArrayList<>();

    try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(",");
        if (parts.length < 4) continue;

        long startTime = Long.parseLong(parts[0]);
        long latency = Long.parseLong(parts[2]);
        latencies.add(latency);

        long endTime = latency + startTime;

        startTimes.add(startTime);
        endTimes.add(endTime);

        minLatency = Math.min(minLatency, latency);
        maxLatency = Math.max(maxLatency, latency);
        totalLatency += latency;
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    if (latencies.isEmpty()) {
      System.out.println("No latency data found!");
      return;
    }

    for (long time = Collections.min(startTimes); time <= Collections.max(endTimes); time++) {
      int activeRequests = 0;
      for (int i = 0; i < startTimes.size(); i++) {
        if (startTimes.get(i) <= time && endTimes.get(i) >= time) {
          activeRequests++;
        }
      }
      activeRequestsPerTime.add((long) activeRequests);
    }

    Collections.sort(latencies);
    double meanLatency = totalLatency / (double) totalRequests;
    long medianLatency = latencies.get(latencies.size() / 2);
    long p99Latency = latencies.get((int) (latencies.size() * 0.99));


    double averageActiveRequests = activeRequestsPerTime.stream()
        .mapToLong(Long::longValue)
        .average()
        .orElse(0);

    double averageTimeInSystem = totalLatency / (double) totalRequests;

    System.out.println();
    System.out.println("This is the Part 2 Output: ");
    System.out.println("Mean Response Time: " + meanLatency + " ms");
    System.out.println("Median Response Time: " + medianLatency + " ms");
    System.out.println("99th Percentile Response Time: " + p99Latency + " ms");
    System.out.println("Min Response Time: " + minLatency + " ms");
    System.out.println("Max Response Time: " + maxLatency + " ms");
    System.out.println("Estimated throughput using Little's Law: " + averageActiveRequests / (averageTimeInSystem / 1000) + " requests/sec");
  }
}

