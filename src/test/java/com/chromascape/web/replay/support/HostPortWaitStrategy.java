package com.chromascape.web.replay.support;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;

/**
 * Waits for a mapped PostgreSQL port by completing a bounded wire-level startup probe.
 *
 * <p>The stock Testcontainers PostgreSQL wait strategy executes a port probe inside the container.
 * Docker Desktop's WSL bridge can leave that exec stream open after PostgreSQL is already ready,
 * which makes a test hang until the retry budget expires. A bare TCP connect is not sufficient:
 * Docker can publish the port before PostgreSQL accepts startup packets. The probe below waits for
 * any valid PostgreSQL response (authentication or an SQL error both prove that the server is
 * accepting connections) and has an explicit bounded deadline.
 */
public final class HostPortWaitStrategy extends AbstractWaitStrategy {

  private final int containerPort;

  public HostPortWaitStrategy(int containerPort) {
    this.containerPort = containerPort;
  }

  @Override
  protected void waitUntilReady() {
    String host = waitStrategyTarget.getHost();
    int mappedPort = waitStrategyTarget.getMappedPort(containerPort);
    long deadline = System.nanoTime() + startupTimeout.toNanos();
    IOException lastFailure = null;

    while (System.nanoTime() < deadline) {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress(host, mappedPort), 250);
        socket.setSoTimeout(500);
        if (postgresqlAcceptsStartup(socket)) {
          return;
        }
      } catch (IOException failure) {
        lastFailure = failure;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new ContainerLaunchException(
            "Interrupted while waiting for " + host + ":" + mappedPort, interrupted);
      }
    }

    throw new ContainerLaunchException(
        "Container port " + host + ":" + mappedPort + " did not become ready", lastFailure);
  }

  /** Sends a PostgreSQL protocol-3 startup packet and checks that the server answers it. */
  private static boolean postgresqlAcceptsStartup(Socket socket) throws IOException {
    byte[] user = "test".getBytes(StandardCharsets.UTF_8);
    byte[] database = "postgres".getBytes(StandardCharsets.UTF_8);
    int payloadLength = 4 + 5 + user.length + 1 + 9 + database.length + 1 + 1;
    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
    output.writeInt(payloadLength + 4);
    output.writeInt(196608); // protocol 3.0
    output.writeBytes("user");
    output.writeByte(0);
    output.write(user);
    output.writeByte(0);
    output.writeBytes("database");
    output.writeByte(0);
    output.write(database);
    output.writeByte(0);
    output.writeByte(0);
    output.flush();
    try (DataInputStream input = new DataInputStream(socket.getInputStream())) {
      int responseType = input.readUnsignedByte();
      int messageLength = input.readInt();
      if (messageLength < 4 || messageLength > 1_048_576) {
        throw new IOException("Invalid PostgreSQL startup response length: " + messageLength);
      }
      byte[] payload = input.readNBytes(messageLength - 4);
      if (payload.length != messageLength - 4) {
        throw new IOException("Incomplete PostgreSQL startup response");
      }
      // Authentication is emitted only after PostgreSQL has completed recovery/startup. A
      // database-specific ErrorResponse also proves that the server is accepting connections
      // (the probe deliberately uses the conventional postgres database even when the container
      // was configured with another database). Do not accept the transient 57P03 "starting up"
      // response, which would race the first real JDBC connection.
      if (responseType == 'R') {
        return true;
      }
      if (responseType != 'E') {
        return false;
      }
      String sqlState = errorSqlState(payload);
      return !"57P03".equals(sqlState);
    }
  }

  private static String errorSqlState(byte[] payload) {
    int offset = 0;
    while (offset < payload.length && payload[offset] != 0) {
      int fieldType = payload[offset++] & 0xff;
      int valueStart = offset;
      while (offset < payload.length && payload[offset] != 0) {
        offset++;
      }
      if (offset >= payload.length) {
        return null;
      }
      if (fieldType == 'C') {
        return new String(payload, valueStart, offset - valueStart, StandardCharsets.UTF_8);
      }
      offset++;
    }
    return null;
  }

  /** Convenience factory that documents the bounded default used by replay fixtures. */
  public static HostPortWaitStrategy postgres() {
    HostPortWaitStrategy strategy = new HostPortWaitStrategy(5432);
    strategy.withStartupTimeout(Duration.ofMinutes(2));
    return strategy;
  }
}
