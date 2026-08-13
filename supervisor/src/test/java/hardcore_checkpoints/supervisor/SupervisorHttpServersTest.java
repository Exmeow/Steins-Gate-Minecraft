package hardcore_checkpoints.supervisor;

import com.google.gson.Gson;
import hardcore_checkpoints.supervisor.protocol.SupervisorHealthReport;
import hardcore_checkpoints.supervisor.protocol.SupervisorRollbackRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SupervisorHttpServersTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void exposesReadOnlyStatusAndTokenProtectedLoopbackControl() throws Exception {
		UUID sessionId = UUID.randomUUID();
		SupervisorState state = new SupervisorState(sessionId);
		RunConfiguration configuration = new RunConfiguration(
				temporaryDirectory.resolve("world"),
				InetAddress.getByName("127.0.0.1"),
				0,
				0,
				Duration.ofSeconds(30),
				false,
				0L,
				List.of("java", "-version")
		);
		AtomicReference<SupervisorHealthReport> accepted = new AtomicReference<>();
		AtomicReference<SupervisorRollbackRequest> rollback = new AtomicReference<>();
		try (SupervisorHttpServers servers = new SupervisorHttpServers(
				configuration,
				"secret-token",
				state,
				accepted::set,
				rollback::set,
				restart -> { }
		)) {
			servers.start();
			HttpClient client = HttpClient.newHttpClient();
			HttpResponse<String> status = client.send(
					HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + servers.statusPort() + "/status")).GET().build(),
					HttpResponse.BodyHandlers.ofString()
			);
			assertEquals(200, status.statusCode());
			assertTrue(status.body().contains(sessionId.toString()));

			URI pingUri = URI.create(servers.controlBaseUrl() + "/internal/ping");
			assertEquals(401, client.send(
					HttpRequest.newBuilder(pingUri).POST(HttpRequest.BodyPublishers.noBody()).build(),
					HttpResponse.BodyHandlers.ofString()
			).statusCode());
			assertEquals(204, client.send(
					HttpRequest.newBuilder(pingUri)
							.header("Authorization", "Bearer secret-token")
							.POST(HttpRequest.BodyPublishers.noBody())
							.build(),
					HttpResponse.BodyHandlers.ofString()
			).statusCode());

			SupervisorHealthReport report = new SupervisorHealthReport(
					1,
					sessionId,
					UUID.randomUUID(),
					UUID.randomUUID(),
					UUID.randomUUID(),
					true,
					UUID.randomUUID(),
					true,
					true,
					"ADMISSION_FROZEN"
			);
			String body = new Gson().toJson(report);
			URI healthUri = URI.create(servers.controlBaseUrl() + "/internal/health");
			HttpResponse<String> unauthorized = client.send(
					HttpRequest.newBuilder(healthUri)
							.POST(HttpRequest.BodyPublishers.ofString(body))
							.build(),
					HttpResponse.BodyHandlers.ofString()
			);
			assertEquals(401, unauthorized.statusCode());

			HttpResponse<String> authorized = client.send(
					HttpRequest.newBuilder(healthUri)
							.header("Authorization", "Bearer secret-token")
							.POST(HttpRequest.BodyPublishers.ofString(body))
							.build(),
					HttpResponse.BodyHandlers.ofString()
			);
			assertEquals(202, authorized.statusCode());
			assertEquals(report.sessionId(), accepted.get().sessionId());
		}
	}
}
