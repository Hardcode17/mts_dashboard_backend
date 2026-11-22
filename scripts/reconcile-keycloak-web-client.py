#!/usr/bin/env python3
"""Reconcile the persistent Keycloak dashboard-web client after realm import.

Keycloak does not reliably re-import an existing realm from --import-realm when
the realm is stored in a persistent volume. Keep the browser client in sync with
the deployed frontend origin, especially its RP-initiated logout redirect.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


def env_file_values(path: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key] = value
    return values


def request(url: str, method: str = "GET", body: bytes | None = None,
            headers: dict[str, str] | None = None, *, allow_no_content: bool = False) -> Any:
    request_obj = urllib.request.Request(url, data=body, method=method,
                                         headers=headers or {})
    last_error: Exception | None = None
    for attempt in range(10):
        try:
            with urllib.request.urlopen(request_obj, timeout=15) as response:
                if allow_no_content and response.status == 204:
                    return None
                raw = response.read()
                if not raw.strip():
                    raise RuntimeError(f"Keycloak returned an empty response from {url}")
                try:
                    return json.loads(raw)
                except json.JSONDecodeError as error:
                    preview = raw[:300].decode("utf-8", errors="replace")
                    raise RuntimeError(
                        f"Keycloak returned non-JSON from {url}: {preview!r}"
                    ) from error
        except urllib.error.HTTPError as error:
            raw = error.read()
            preview = raw[:300].decode("utf-8", errors="replace")
            if error.code not in {502, 503, 504}:
                raise RuntimeError(
                    f"Keycloak returned HTTP {error.code} from {url}: {preview!r}"
                ) from error
            last_error = error
        except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
            last_error = error
        if attempt < 9:
            time.sleep(2)
    raise RuntimeError(f"Keycloak admin API is unavailable: {last_error}")


def main() -> None:
    values = env_file_values(os.environ["BACKEND_ENV_FILE"])
    base = os.environ.get("KEYCLOAK_ADMIN_URL", "http://127.0.0.1:8084").rstrip("/")
    frontend = os.environ["STAND_FRONTEND_URL"].rstrip("/")
    admin_user = values["KEYCLOAK_ADMIN_USERNAME"]
    admin_password = values["KEYCLOAK_ADMIN_PASSWORD"]

    token_body = urllib.parse.urlencode({
        "grant_type": "password",
        "client_id": "admin-cli",
        "username": admin_user,
        "password": admin_password,
    }).encode()
    token = request(
        f"{base}/realms/master/protocol/openid-connect/token",
        method="POST",
        body=token_body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )["access_token"]
    auth = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    clients = request(
        f"{base}/admin/realms/dashboard/clients?clientId=dashboard-web",
        headers=auth,
    )
    if not isinstance(clients, list) or len(clients) != 1:
        raise RuntimeError(f"Expected exactly one dashboard-web client, got {len(clients)}")

    client = clients[0]
    redirect_uri = f"{frontend}/auth/callback"
    logout_uri = f"{frontend}/"
    redirect_uris = set(client.get("redirectUris") or [])
    redirect_uris.update({redirect_uri, logout_uri})
    client["redirectUris"] = sorted(redirect_uris)

    origins = set(client.get("webOrigins") or [])
    origins.add(frontend)
    client["webOrigins"] = sorted(origins)

    # '+' tells Keycloak to reuse the client's valid redirect URI list for
    # RP-initiated logout. This also updates already-imported persistent realms.
    client.setdefault("attributes", {})["post.logout.redirect.uris"] = "+"

    request(
        f"{base}/admin/realms/dashboard/clients/{client['id']}",
        method="PUT",
        body=json.dumps(client).encode(),
        headers=auth,
        allow_no_content=True,
    )
    print(f"Reconciled dashboard-web Keycloak client for {frontend}")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, OSError, ValueError, RuntimeError, urllib.error.URLError) as error:
        print(f"Keycloak client reconciliation failed: {error}", file=sys.stderr)
        sys.exit(1)
