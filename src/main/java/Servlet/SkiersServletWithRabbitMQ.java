package Servlet;

import Producer.MessageQueueProducer;
import com.google.gson.JsonObject;
import java.io.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import com.google.gson.Gson;

@WebServlet(name = "SkiersServletWithRabbitMQ", urlPatterns = "/skiers/*")
public class SkiersServletWithRabbitMQ extends HttpServlet {
  private final Gson gson = new Gson();
  private MessageQueueProducer messageQueueProducer;
  private static final String RABBITMQ_HOST = "54.148.199.48";
  private static final String USERNAME = "myuser";
  private static final String PASSWORD = "mypassword";

  @Override
  public void init() throws ServletException {
    super.init();
    try {
      messageQueueProducer = new MessageQueueProducer(RABBITMQ_HOST, USERNAME, PASSWORD);

    } catch (Exception e) {
      throw new ServletException("Failed to initialize RabbitMQ connection or channel pool", e);
    }
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setContentType("text/html");
    response.setStatus(HttpServletResponse.SC_OK);
    PrintWriter out = response.getWriter();
    out.println("<h1>It works! :)</h1>");
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setContentType("application/json");
    String[] pathParts = request.getPathInfo() != null ? request.getPathInfo().split("/") : new String[0];

    if (pathParts.length != 8) {
      sendErrorResponse(response, "Invalid URL format. Expected: /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}");
      return;
    }
    try {
      int resortID = Integer.parseInt(pathParts[1]);
      String seasonID = pathParts[3];
      String dayID = pathParts[5];
      int skierID = Integer.parseInt(pathParts[7]);
      if (!dayID.matches("^([1-9]|[1-9][0-9]|[12][0-9][0-9]|3[0-5][0-9]|36[0-6])$")) {
        sendErrorResponse(response, "Invalid dayID. Must be a string between \"1\" and \"366\".");
        return;
      }

      StringBuilder jsonBody = new StringBuilder();
      try (BufferedReader reader = request.getReader()) {
        String line;
        while ((line = reader.readLine()) != null) {
          jsonBody.append(line);
        }
      }
      JsonObject jsonObject = gson.fromJson(jsonBody.toString(), JsonObject.class);
      if (!jsonObject.has("time") || !jsonObject.has("liftID") || jsonObject.get("time").isJsonNull() || jsonObject.get("liftID").isJsonNull()) {
        sendErrorResponse(response, "Missing required fields in request body: time, liftID");
        return;
      }

      int time = jsonObject.get("time").getAsInt();
      int liftID = jsonObject.get("liftID").getAsInt();

      if (time <= 0 || liftID <= 0) {
        sendErrorResponse(response, "Invalid values: time and liftID must be positive integers.");
        return;
      }

      JsonObject message = new JsonObject();
      message.addProperty("resortID", resortID);
      message.addProperty("seasonID", seasonID);
      message.addProperty("dayID", dayID);
      message.addProperty("skierID", skierID);
      message.addProperty("time", time);
      message.addProperty("liftID", liftID);


//      String ackResponse = messageQueueProducer.sendMessage(message);
      messageQueueProducer.sendMessage(message);

      try {
//        JsonObject responseJson = JsonParser.parseString(ackResponse).getAsJsonObject();
//        String status = responseJson.get("status").getAsString();
//        if ("acknowledged".equals(status)) {
//          response.setStatus(HttpServletResponse.SC_CREATED);
//          response.getWriter().write(gson.toJson(new SuccessResponse("Skier ride recorded")));
//        } else {
//          response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
//          response.getWriter().write(gson.toJson(new ErrorResponse(ackResponse)));
//        }
        response.setStatus(HttpServletResponse.SC_CREATED);
        response.getWriter().write(gson.toJson(new SuccessResponse("Skier ride recorded")));
      } catch (Exception e) {
        // Log the exception and send a 500 error
        e.printStackTrace();
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.getWriter().write(gson.toJson(new ErrorResponse("Internal Server Error")));
      }
    } catch (NumberFormatException e){
      sendErrorResponse(response, "Invalid numeric parameter(s). resortID and skierID must be integers.");
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private void sendErrorResponse(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.getWriter().write(gson.toJson(new ErrorResponse(message)));
  }
  static class ErrorResponse {
    String message;
    ErrorResponse(String message) { this.message = message; }
  }

  static class SuccessResponse {
    String status = "success";
    String message;
    SuccessResponse(String message) { this.message = message; }
  }
}

