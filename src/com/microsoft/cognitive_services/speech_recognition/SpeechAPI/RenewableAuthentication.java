package com.microsoft.cognitive_services.speech_recognition.SpeechAPI;

import java.time.Duration;
import java.util.Timer;
import java.util.TimerTask;

public class RenewableAuthentication extends Authentication {

  private final Timer timer = new Timer();
  private long period = Duration.ofMinutes(9).toMillis(); // 9 minutes worth of ms.

  public RenewableAuthentication(String subscriptionKey) {
    super(subscriptionKey);
    // schedule a task to renew the token each 9 seconds,
    // starting in 9 seconds from now.
    timer.schedule(new TimerTask() {

      @Override
      public void run() {
        fetchToken();
      }
    }, period, period);
  }

  @Override
  protected synchronized void setToken(String token) {
    super.setToken(token);
  }

  @Override
  public synchronized String getToken() {
    return super.getToken();
  }
}