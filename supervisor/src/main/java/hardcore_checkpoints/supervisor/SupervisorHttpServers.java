package hardcore_checkpoints.supervisor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hardcore_checkpoints.supervisor.protocol.SupervisorHealthReport;
import hardcore_checkpoints.supervisor.protocol.SupervisorRollbackRequest;
import hardcore_checkpoints.supervisor.protocol.SupervisorRestartRequest;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SupervisorHttpServers implements AutoCloseable {
	private static final int MAX_BODY_BYTES = 64 * 1024;

	private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
	private final String controlToken;
	private final SupervisorState state;
	private final HealthAcceptor healthAcceptor;
	private final RollbackAcceptor rollbackAcceptor;
	private final RestartAcceptor restartAcceptor;
	private final ExecutorService executor;
	private final HttpServer statusServer;
	private final HttpServer controlServer;

	SupervisorHttpServers(
			RunConfiguration configuration,
			String controlToken,
			SupervisorState state,
			HealthAcceptor healthAcceptor,
			RollbackAcceptor rollbackAcceptor,
			RestartAcceptor restartAcceptor
	) throws IOException {
		this.controlToken = controlToken;
		this.state = state;
		this.healthAcceptor = healthAcceptor;
		this.rollbackAcceptor = rollbackAcceptor;
		this.restartAcceptor = restartAcceptor;
		this.executor = Executors.newCachedThreadPool(Thread.ofPlatform()
				.name("hardcore-checkpoints-http-", 0)
				.daemon(true)
				.factory());
		this.statusServer = HttpServer.create(
				new InetSocketAddress(configuration.statusBindAddress(), configuration.statusPort()),
				16
		);
		this.controlServer = HttpServer.create(
				new InetSocketAddress(InetAddress.getByName("127.0.0.1"), configuration.controlPort()),
				16
		);
		statusServer.setExecutor(executor);
		controlServer.setExecutor(executor);
		statusServer.createContext("/status", this::handleStatus);
		controlServer.createContext("/internal/ping", this::handleAuthorizedPing);
		controlServer.createContext("/internal/health", exchange -> handleAuthorizedPost(
				exchange,
				SupervisorHealthReport.class,
				healthAcceptor::accept
		));
		controlServer.createContext("/internal/rollback", exchange -> handleAuthorizedPost(
				exchange,
				SupervisorRollbackRequest.class,
				rollbackAcceptor::accept
		));
		controlServer.createContext("/internal/restart", exchange -> handleAuthorizedPost(
				exchange,
				SupervisorRestartRequest.class,
				restartAcceptor::accept
		));
	}

	void start() {
		statusServer.start();
		controlServer.start();
	}

	int statusPort() {
		return statusServer.getAddress().getPort();
	}

	String controlBaseUrl() {
		return "http://127.0.0.1:" + controlServer.getAddress().getPort();
	}

	private void handleStatus(HttpExchange exchange) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			send(exchange, 405, Map.of("error", "method_not_allowed"));
			return;
		}
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		send(exchange, 200, state.snapshot());
	}

	private void handleAuthorizedPing(HttpExchange exchange) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			send(exchange, 405, Map.of("error", "method_not_allowed"));
			return;
		}
		if (!authorized(exchange)) {
			send(exchange, 401, Map.of("error", "unauthorized"));
			return;
		}
		exchange.sendResponseHeaders(204, -1);
		exchange.close();
	}

	private <T> void handleAuthorizedPost(
			HttpExchange exchange,
			Class<T> bodyType,
			ThrowingConsumer<T> consumer
	) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			send(exchange, 405, Map.of("error", "method_not_allowed"));
			return;
		}
		if (!authorized(exchange)) {
			send(exchange, 401, Map.of("error", "unauthorized"));
			return;
		}
		try {
			byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
			if (body.length > MAX_BODY_BYTES) {
				send(exchange, 413, Map.of("error", "body_too_large"));
				return;
			}
			T value = gson.fromJson(new String(body, StandardCharsets.UTF_8), bodyType);
			if (value == null) {
				throw new IllegalArgumentException("Request body is empty");
			}
			consumer.accept(value);
			send(exchange, 202, Map.of("accepted", true));
		} catch (IllegalArgumentException exception) {
			send(exchange, 400, Map.of("error", "invalid_request", "message", safeMessage(exception)));
		} catch (Exception exception) {
			send(exchange, 409, Map.of("error", "request_rejected", "message", safeMessage(exception)));
		}
	}

	private boolean authorized(HttpExchange exchange) {
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		if (authorization == null || !authorization.startsWith("Bearer ")) {
			return false;
		}
		byte[] expected = controlToken.getBytes(StandardCharsets.UTF_8);
		byte[] actual = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expected, actual);
	}

	private void send(HttpExchange exchange, int status, Object response) throws IOException {
		byte[] bytes = gson.toJson(response).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (var output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private static String safeMessage(Exception exception) {
		return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
	}

	@Override
	public void close() {
		controlServer.stop(0);
		statusServer.stop(0);
		executor.shutdownNow();
	}

	@FunctionalInterface
	interface HealthAcceptor {
		void accept(SupervisorHealthReport report) throws Exception;
	}

	@FunctionalInterface
	interface RollbackAcceptor {
		void accept(SupervisorRollbackRequest request) throws Exception;
	}

	@FunctionalInterface
	interface RestartAcceptor {
		void accept(SupervisorRestartRequest request) throws Exception;
	}

	@FunctionalInterface
	private interface ThrowingConsumer<T> {
		void accept(T value) throws Exception;
	}
}
