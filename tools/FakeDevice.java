import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fake Android device for development: it speaks the device protocol (docs/device-protocol.md) without
 * sending any real SMS. Every message it receives is acknowledged, reported as sent, then as delivered.
 *
 * <pre>
 * java tools/FakeDevice.java ws://localhost:8080/v1/device/ws usgd_... [--fail-every N]
 * </pre>
 *
 * Requires JDK 21 or later, no dependency. Message texts are not printed.
 */
public class FakeDevice {

    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TO = Pattern.compile("\"to\"\\s*:\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java tools/FakeDevice.java <ws url> <device token> [--fail-every N]");
            System.exit(2);
        }
        String url = args[0];
        String token = args[1];
        int failEvery = args.length >= 4 && args[2].equals("--fail-every") ? Integer.parseInt(args[3]) : 0;

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch closed = new CountDownLatch(1);
        int[] received = {0};

        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            handle(webSocket, buffer.toString());
                            buffer.setLength(0);
                        }
                        webSocket.request(1);
                        return CompletableFuture.completedFuture(null);
                    }

                    private void handle(WebSocket webSocket, String frame) {
                        String type = find(TYPE, frame);
                        if ("hello".equals(type)) {
                            System.out.println("Connected: " + frame);
                            return;
                        }
                        if (!"send".equals(type)) {
                            System.out.println("Frame: " + frame);
                            return;
                        }
                        String id = find(ID, frame);
                        int number = ++received[0];
                        System.out.println("Message " + id + " to " + find(TO, frame));
                        send(webSocket, "{\"type\":\"ack\",\"id\":\"" + id + "\"}");
                        if (failEvery > 0 && number % failEvery == 0) {
                            timer.schedule(() -> send(webSocket,
                                    "{\"type\":\"failed\",\"id\":\"" + id + "\",\"reason\":\"RESULT_ERROR_NO_SERVICE\"}"), 1, TimeUnit.SECONDS);
                            return;
                        }
                        timer.schedule(() -> send(webSocket, "{\"type\":\"sent\",\"id\":\"" + id + "\"}"), 1, TimeUnit.SECONDS);
                        timer.schedule(() -> send(webSocket, "{\"type\":\"delivered\",\"id\":\"" + id + "\"}"), 3, TimeUnit.SECONDS);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        System.out.println("Closed: " + statusCode + " " + reason);
                        closed.countDown();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        System.err.println("Error: " + error.getMessage());
                        closed.countDown();
                    }
                }).join();

        timer.scheduleAtFixedRate(() -> send(socket,
                "{\"type\":\"heartbeat\",\"battery\":87,\"charging\":true,\"signal\":4,\"operator\":\"Fake operator\",\"network\":\"WIFI\",\"appVersion\":\"fake-device\"}"),
                0, 30, TimeUnit.SECONDS);
        closed.await();
        timer.shutdownNow();
    }

    private static synchronized void send(WebSocket socket, String frame) {
        socket.sendText(frame, true).join();
    }

    private static String find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
