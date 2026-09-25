/**
 * WebSocket E2E Test Script for Browser Console
 *
 * Run this in your browser's DevTools Console on http://localhost:8090/debugger
 *
 * Usage:
 * 1. Open http://localhost:8090/debugger in your browser
 * 2. Open DevTools (F12) -> Console tab
 * 3. Copy and paste this entire script into the console
 * 4. Run: runWebSocketTests()
 */

class WebSocketTester {
  constructor() {
    this.results = [];
  }

  log(message, type = 'info') {
    const styles = {
      info: 'color: #3498db',
      success: 'color: #27ae60; font-weight: bold',
      error: 'color: #e74c3c; font-weight: bold',
      warning: 'color: #f39c12'
    };
    console.log(`%c${message}`, styles[type] || styles.info);
  }

  async testBaseConnection() {
    this.log('=== Test 1: Base Connection ===', 'info');
    return new Promise((resolve, reject) => {
      const ws = new WebSocket('ws://localhost:8090/ws/debug/patch-loop');
      const timeout = setTimeout(() => {
        ws.close();
        reject(new Error('Connection timeout'));
      }, 5000);

      let connected = false;
      let receivedConnected = false;

      ws.onopen = () => {
        connected = true;
        this.log('✓ WebSocket connection established', 'success');
      };

      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'CONNECTED') {
            receivedConnected = true;
            this.log('✓ Received CONNECTED message: ' + event.data, 'success');
            clearTimeout(timeout);
            ws.close();
            resolve({ connected, receivedConnected });
          }
        } catch (e) {
          // Ignore parse errors for non-JSON messages
        }
      };

      ws.onerror = (error) => {
        clearTimeout(timeout);
        reject(new Error('WebSocket error: ' + error));
      };

      ws.onclose = () => {
        if (!receivedConnected) {
          clearTimeout(timeout);
          if (connected) {
            reject(new Error('Connection closed without receiving CONNECTED message'));
          } else {
            reject(new Error('Connection failed'));
          }
        }
      };
    });
  }

  async testExecutionScopedConnection(executionId) {
    this.log(`=== Test 2: Execution-Scoped Connection (${executionId}) ===`, 'info');
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(`ws://localhost:8090/ws/debug/patch-loop/${executionId}`);
      const timeout = setTimeout(() => {
        ws.close();
        reject(new Error('Connection timeout'));
      }, 5000);

      let connected = false;
      let receivedConnected = false;

      ws.onopen = () => {
        connected = true;
        this.log(`✓ WebSocket connection established to /ws/debug/patch-loop/${executionId}`, 'success');
      };

      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'CONNECTED') {
            receivedConnected = true;
            this.log(`✓ Received CONNECTED message: ${event.data}`, 'success');
            clearTimeout(timeout);
            ws.close();
            resolve({ connected, receivedConnected, executionId });
          }
        } catch (e) {
          // Ignore parse errors
        }
      };

      ws.onerror = (error) => {
        clearTimeout(timeout);
        reject(new Error('WebSocket error: ' + error));
      };

      ws.onclose = () => {
        if (!receivedConnected) {
          clearTimeout(timeout);
          if (connected) {
            reject(new Error('Connection closed without receiving CONNECTED message'));
          } else {
            reject(new Error('Connection failed'));
          }
        }
      };
    });
  }

  async testMultipleClients() {
    this.log('=== Test 3: Multiple Clients Connection ===', 'info');
    const clientCount = 3;
    const promises = [];

    for (let i = 0; i < clientCount; i++) {
      promises.push(
        new Promise((resolve, reject) => {
          const ws = new WebSocket('ws://localhost:8090/ws/debug/patch-loop');
          const timeout = setTimeout(() => {
            ws.close();
            reject(new Error('Connection timeout'));
          }, 5000);

          ws.onopen = () => {
            clearTimeout(timeout);
            this.log(`✓ Client ${i + 1} connected`, 'success');
            ws.close();
            resolve();
          };

          ws.onerror = (error) => {
            clearTimeout(timeout);
            reject(new Error(`Client ${i + 1} failed: ${error}`));
          };
        })
      );
    }

    try {
      await Promise.all(promises);
      this.log(`✓ All ${clientCount} clients connected successfully`, 'success');
      return { clientCount, allConnected: true };
    } catch (error) {
      throw new Error('Multiple clients test failed: ' + error.message);
    }
  }

  async testMixedConnections() {
    this.log('=== Test 4: Mixed Base and Scoped Connections ===', 'info');

    const baseClient = new Promise((resolve, reject) => {
      const ws = new WebSocket('ws://localhost:8090/ws/debug/patch-loop');
      const timeout = setTimeout(() => {
        ws.close();
        reject(new Error('Base client timeout'));
      }, 5000);

      ws.onopen = () => {
        clearTimeout(timeout);
        this.log('✓ Base client connected', 'success');
        ws.close();
        resolve();
      };

      ws.onerror = () => {
        clearTimeout(timeout);
        reject(new Error('Base client failed'));
      };
    });

    const scopedClientA = new Promise((resolve, reject) => {
      const ws = new WebSocket('ws://localhost:8090/ws/debug/patch-loop/exec-A');
      const timeout = setTimeout(() => {
        ws.close();
        reject(new Error('Scoped client A timeout'));
      }, 5000);

      ws.onopen = () => {
        clearTimeout(timeout);
        this.log('✓ Scoped client A (exec-A) connected', 'success');
        ws.close();
        resolve();
      };

      ws.onerror = () => {
        clearTimeout(timeout);
        reject(new Error('Scoped client A failed'));
      };
    });

    const scopedClientB = new Promise((resolve, reject) => {
      const ws = new WebSocket('ws://localhost:8090/ws/debug/patch-loop/exec-B');
      const timeout = setTimeout(() => {
        ws.close();
        reject(new Error('Scoped client B timeout'));
      }, 5000);

      ws.onopen = () => {
        clearTimeout(timeout);
        this.log('✓ Scoped client B (exec-B) connected', 'success');
        ws.close();
        resolve();
      };

      ws.onerror = () => {
        clearTimeout(timeout);
        reject(new Error('Scoped client B failed'));
      };
    });

    try {
      await Promise.all([baseClient, scopedClientA, scopedClientB]);
      this.log('✓ All connection types (base + 2 scoped) succeeded', 'success');
      return { baseConnected: true, scopedConnected: true };
    } catch (error) {
      throw new Error('Mixed connections test failed: ' + error.message);
    }
  }

  async runAll() {
    this.log('╔════════════════════════════════════════════════╗', 'info');
    this.log('║  WebSocket E2E Test Suite                      ║', 'info');
    this.log('║  ChromaScape Patch Loop WebSocket Routing       ║', 'info');
    this.log('╚════════════════════════════════════════════════╝', 'info');
    this.log('', 'info');

    const results = {
      passed: 0,
      failed: 0,
      tests: []
    };

    // Test 1: Base Connection
    try {
      await this.testBaseConnection();
      results.passed++;
      results.tests.push({ name: 'Base Connection', status: 'PASSED' });
    } catch (error) {
      results.failed++;
      results.tests.push({ name: 'Base Connection', status: 'FAILED', error: error.message });
      this.log(`✗ ${error.message}`, 'error');
    }
    this.log('', 'info');

    // Wait between tests
    await new Promise(r => setTimeout(r, 500));

    // Test 2: Execution-Scoped Connection
    try {
      await this.testExecutionScopedConnection('test-exec-123');
      results.passed++;
      results.tests.push({ name: 'Execution-Scoped Connection', status: 'PASSED' });
    } catch (error) {
      results.failed++;
      results.tests.push({ name: 'Execution-Scoped Connection', status: 'FAILED', error: error.message });
      this.log(`✗ ${error.message}`, 'error');
    }
    this.log('', 'info');

    await new Promise(r => setTimeout(r, 500));

    // Test 3: Complex Execution ID
    try {
      await this.testExecutionScopedConnection('iteration-abc-456-def');
      results.passed++;
      results.tests.push({ name: 'Complex Execution ID', status: 'PASSED' });
    } catch (error) {
      results.failed++;
      results.tests.push({ name: 'Complex Execution ID', status: 'FAILED', error: error.message });
      this.log(`✗ ${error.message}`, 'error');
    }
    this.log('', 'info');

    await new Promise(r => setTimeout(r, 500));

    // Test 4: Multiple Clients
    try {
      await this.testMultipleClients();
      results.passed++;
      results.tests.push({ name: 'Multiple Clients', status: 'PASSED' });
    } catch (error) {
      results.failed++;
      results.tests.push({ name: 'Multiple Clients', status: 'FAILED', error: error.message });
      this.log(`✗ ${error.message}`, 'error');
    }
    this.log('', 'info');

    await new Promise(r => setTimeout(r, 500));

    // Test 5: Mixed Connections
    try {
      await this.testMixedConnections();
      results.passed++;
      results.tests.push({ name: 'Mixed Connections', status: 'PASSED' });
    } catch (error) {
      results.failed++;
      results.tests.push({ name: 'Mixed Connections', status: 'FAILED', error: error.message });
      this.log(`✗ ${error.message}`, 'error');
    }
    this.log('', 'info');

    // Summary
    this.log('╔════════════════════════════════════════════════╗', 'info');
    this.log('║  Test Summary                                   ║', 'info');
    this.log('╚════════════════════════════════════════════════╝', 'info');
    this.log(`Total Tests: ${results.passed + results.failed}`, 'info');
    this.log(`%cPassed: ${results.passed}`, 'success');
    if (results.failed > 0) {
      this.log(`%cFailed: ${results.failed}`, 'error');
    } else {
      this.log(`Failed: ${results.failed}`, 'info');
    }
    this.log('', 'info');

    if (results.failed === 0) {
      this.log('%c🎉 All tests passed!', 'success');
    } else {
      this.log('%c⚠️  Some tests failed. Check the errors above.', 'warning');
    }

    return results;
  }
}

// Global function to run tests
window.runWebSocketTests = async function() {
  const tester = new WebSocketTester();
  return await tester.runAll();
};

// Auto-run if page is loaded
if (document.readyState === 'complete') {
  console.log('%cWebSocket Test Suite Ready!', 'color: #3498db; font-weight: bold; font-size: 14px');
  console.log('%cRun: runWebSocketTests()', 'color: #3498db');
} else {
  document.addEventListener('DOMContentLoaded', () => {
    console.log('%cWebSocket Test Suite Ready!', 'color: #3498db; font-weight: bold; font-size: 14px');
    console.log('%cRun: runWebSocketTests()', 'color: #3498db');
  });
}

// Export for module use
if (typeof module !== 'undefined' && module.exports) {
  module.exports = { WebSocketTester };
}
