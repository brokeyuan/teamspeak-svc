import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * N0 environment probe (no project dependencies): replicates AvatarPipeline's
 * static JDK HttpClient initialization and one loopback request, and reports
 * exactly where it fails. Purpose: classify whether the two historically
 * failing harness suites (session-consumption, upload-confirmation) hit a
 * product defect or this machine's JDK HttpClient initialization problem.
 *
 * Exit 0 = HttpClient works on this host; exit 2 = environment problem
 * reproduced; exit 3 = unexpected error.
 */
public final class HttpClientSmoke {
    public static void main(String[] args) throws Exception {
        HttpClient client;
        try {
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            System.out.println("STEP1 HttpClient.newBuilder().build() OK");
        } catch (Throwable t) {
            System.out.println("STEP1 HttpClient build FAILED: " + t);
            t.printStackTrace(System.out);
            System.exit(2);
            return;
        }
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            int port = server.getLocalPort();
            Thread acceptor = new Thread(() -> {
                try (Socket s = server.accept()) {
                    s.getInputStream().read(new byte[4096]);
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes("UTF-8"));
                } catch (Exception ignored) {
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("STEP2 loopback request OK: HTTP " + resp.statusCode());
        } catch (Throwable t) {
            System.out.println("STEP2 loopback request FAILED: " + t);
            t.printStackTrace(System.out);
            System.exit(2);
            return;
        }
        System.out.println("RESULT: JDK HttpClient functional on this host");
        System.exit(0);
    }
}
