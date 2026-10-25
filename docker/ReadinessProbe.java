package io.setu.container;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Small dependency-free health check for the container's loopback operations listener. */
public final class ReadinessProbe {
    private ReadinessProbe() { }

    public static void main(String[] args) {
        String endpoint = args.length == 0
                ? System.getenv().getOrDefault("SETU_HEALTH_URL", "http://127.0.0.1:9093/ready") : args[0];
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) throw new java.io.IOException("Broker is not ready");
            try (var input = connection.getInputStream()) {
                if (!new String(input.readNBytes(32), StandardCharsets.UTF_8).equals("ready\n"))
                    throw new java.io.IOException("Unexpected readiness response");
            }
        } catch (Exception failure) {
            System.err.println("Setu readiness probe failed");
            System.exit(1);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
