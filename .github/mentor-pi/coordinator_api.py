import json
import os
from pathlib import Path
import subprocess
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


def workspace():
    # Use the dedicated stand coordinator and the same OIDC flow as the browser.
    for line in Path(os.environ["BACKEND_ENV_FILE"]).read_text().splitlines():
        if line.startswith("STAND_COORDINATOR_PASSWORD="):
            password = line.partition("=")[2]
            break
    else:
        raise SystemExit("Stand coordinator configuration is missing")
    port = os.environ.get("FRONTEND_PORT", "8080")
    issuer = os.environ["STAND_KEYCLOAK_URL"].rstrip("/")
    auth = subprocess.run(["bash", "scripts/fetch-human-token.sh"], capture_output=True, text=True, env={
        **os.environ, "DASHBOARD_TEST_USERNAME": "coordinator", "DASHBOARD_TEST_PASSWORD": password,
        "KEYCLOAK_PUBLIC_URL": issuer,
        "KEYCLOAK_LOOPBACK_URL": f"http://localhost:{port}" + urlsplit(issuer).path,
        "DASHBOARD_REDIRECT_URI": os.environ["STAND_FRONTEND_URL"].rstrip("/") + "/auth/callback",
    })
    if auth.returncode:
        # Only report the stage, never a provider response body, URL with a code, or JWT.
        if "registered callback" in auth.stderr:
            raise SystemExit("Keycloak accepted the login request but did not issue a coordinator authorization code")
        if "Token error" in auth.stderr:
            raise SystemExit("Keycloak did not exchange the coordinator code for a token")
        raise SystemExit("Could not complete the coordinator login form via the Pi frontend")
    origin = f"http://127.0.0.1:{port}"
    headers = {"Authorization": "Bearer " + auth.stdout.strip(), "Content-Type": "application/json"}

    def request(path, data=None):
        body = json.dumps(data).encode() if data is not None else None
        with urlopen(Request(origin+path, headers=headers, data=body), timeout=20) as reply:
            return json.load(reply)

    return request
