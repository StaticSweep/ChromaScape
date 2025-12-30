#!/usr/bin/env python3
"""
Simple HTTP server that listens for trigger requests from Docker containers.
When triggered, starts ChromaScape on Windows.
"""

from http.server import BaseHTTPRequestHandler, HTTPServer
import subprocess
import os
import json

CHROMASCAPE_DIR = r"C:\Users\Beheerder\IdeaProjects\ChromaScape"
GRADLEW = os.path.join(CHROMASCAPE_DIR, "gradlew.bat")

# Java 17 is required for ChromaScape (Spring Boot 3.5.3)
# Adjust this path to where Java 17 is installed
JAVA_17_PATH = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"

class TriggerHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/health':
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"status":"ready"}')
            return

        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if self.path == '/start':
            try:
                # Read any payload
                content_length = int(self.headers.get('Content-Length', 0))
                if content_length > 0:
                    payload = self.rfile.read(content_length)
                    data = json.loads(payload.decode('utf-8')) if payload else {}
                else:
                    data = {}

                # Get configuration from payload or use defaults
                server_port = data.get('port', '8090')
                leeuwbot_enabled = data.get('leeuwbot_enabled', 'true')
                bridge_url = data.get('bridge_url', 'http://localhost:8092')
                api_key = data.get('api_key', 'dev-token')

                # Set environment variables and start ChromaScape
                env = os.environ.copy()
                env['SERVER_PORT'] = server_port
                env['LEEUWBOT_ENABLED'] = leeuwbot_enabled
                env['LEEUWBOT_BRIDGE_URL'] = bridge_url
                env['CHROMASCAPE_API_KEY'] = api_key

                # Set JAVA_HOME to Java 17 for ChromaScape
                env['JAVA_HOME'] = JAVA_17_PATH
                env['PATH'] = JAVA_17_PATH + r'\bin;' + env.get('PATH', '')

                # Start ChromaScape using Windows start command (opens new window)
                # This allows ChromaScape to run independently and shows startup logs
                subprocess.Popen(
                    f'start /D "{CHROMASCAPE_DIR}" cmd /k "gradlew.bat bootRun"',
                    shell=True,
                    env=env
                )

                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"status":"starting","port":' + server_port.encode() + b'}')
                print(f"ChromaScape started on port {server_port}")

            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(f'{{"error":"{str(e)}"}}'.encode())
                print(f"Error starting ChromaScape: {e}")
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        print(f"[{self.log_date_time_string()}] {format % args}")

def run_server(port=8091):
    server_address = ('', port)
    httpd = HTTPServer(server_address, TriggerHandler)
    print(f"ChromaScape trigger listener running on port {port}")
    print(f"ChromaScape directory: {CHROMASCAPE_DIR}")
    print("Press Ctrl+C to stop...")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down...")
        httpd.server_close()

if __name__ == '__main__':
    run_server()
