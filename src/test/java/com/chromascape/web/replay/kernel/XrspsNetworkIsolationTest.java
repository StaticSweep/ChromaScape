package com.chromascape.web.replay.kernel;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.replay.config.ReplayProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XrspsNetworkIsolationTest {
  @TempDir Path directory;

  @Test
  void launchedWorkerCannotReachLoopbackLanMetadataOrDnsAndHasNoCredentials() throws Exception {
    Path entrypoint = directory.resolve("probe.js");
    Path hostSecret = Files.createTempFile("sc802-host-secret-", ".txt");
    Files.writeString(hostSecret, "must not enter the worker");
    Files.writeString(
        entrypoint,
        """
        const net = require('net');
        if (require('fs').existsSync('HOST_SECRET')) process.exit(4);
        if (require('fs').existsSync('/home/ikiddoi')) process.exit(5);
        if (Object.keys(process.env).some(k => /TOKEN|CREDENTIAL|ENDPOINT_REF|PASSWORD|AUTH/i.test(k))) process.exit(2);
        const targets = ['127.0.0.1', '192.168.1.6', '169.254.169.254', '8.8.8.8'];
        const checks = targets.map(host => new Promise((resolve, reject) => {
          const socket = net.connect({host, port: 80});
          socket.setTimeout(500, () => { socket.destroy(); resolve(); });
          socket.on('connect', () => { socket.destroy(); reject(new Error('network escape')); });
          socket.on('error', () => resolve());
        }));
        checks.push(new Promise((resolve, reject) => {
          const socket = require('dgram').createSocket('udp4');
          socket.send(Buffer.from('blocked'), 53, '8.8.8.8', error => {
            socket.close(); if (error) resolve(); else reject(new Error('DNS egress'));
          });
        }));
        Promise.all(checks).then(() => console.log('ISOLATED')).catch(() => process.exit(3));
        """
            .replace("HOST_SECRET", hostSecret.toString()));
    var config = new ReplayProperties.Xrsps();
    config.setEntrypoint(entrypoint.toString());
    config.setWorkingDirectory(directory.toString());
    Process process = XrspsProcessBuilderFactory.create(config, "--stdio").start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue(), new String(process.getErrorStream().readAllBytes()));
      assertEquals("ISOLATED", new String(process.getInputStream().readAllBytes()).trim());
    } finally {
      process.destroyForcibly();
      Files.deleteIfExists(hostSecret);
    }
  }
}
