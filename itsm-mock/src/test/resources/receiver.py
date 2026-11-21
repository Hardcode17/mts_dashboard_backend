from http.server import BaseHTTPRequestHandler, HTTPServer
import json

requests = []


class Receiver(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/requests":
            self.respond(200, requests)
        else:
            self.respond(404, {})

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"])).decode()
        if self.path == "/token" and body == "grant_type=client_credentials&client_id=test-client&client_secret=test-secret":
            self.respond(200, {"access_token": "test-token"})
            return
        if self.path != "/api/integrations/itsm/batches":
            self.respond(404, {})
            return
        if self.headers.get("Authorization") != "Bearer test-token":
            self.respond(401, {})
            return
        requests.append({
            "batchId": self.headers["Idempotency-Key"],
            "sourceId": self.headers["X-Source-Id"],
            "version": self.headers["X-Source-Version"],
            "contentType": self.headers["Content-Type"],
            "body": json.loads(body)
        })
        self.respond(202, {"batchId": self.headers["Idempotency-Key"]})

    def respond(self, status, value):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


HTTPServer(("0.0.0.0", 8080), Receiver).serve_forever()
