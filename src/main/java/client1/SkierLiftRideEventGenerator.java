package client1;

import java.util.Random;

public class SkierLiftRideEventGenerator {

  private static final Random random = new Random();

  public static SkierLiftRideEvent generateEvent() {
    SkierLiftRideEvent event = new SkierLiftRideEvent();

    event.setSkierID(random.nextInt(100000) + 1);
    event.setResortID(random.nextInt(10) + 1);
    event.setLiftID(random.nextInt(40) + 1);
    event.setSeasonID(2025);
    event.setDayID(1);
    event.setTime(random.nextInt(360) + 1);

    return event;
  }
}

