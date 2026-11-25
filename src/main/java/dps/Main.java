package dps;

import dps.utility.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Administration Server: boots the Spring context
 * exposing `LineRegisterController`, `AdminClientController`,
 * and connecting `MqttSubscriber` to the broker.
 */
@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        Log.info("AdminServer", "Starting up...");
        SpringApplication.run(Main.class, args);
        Log.info("AdminServer", "Ready - Rest endpoints and MQTT subscriber active");
    }
}