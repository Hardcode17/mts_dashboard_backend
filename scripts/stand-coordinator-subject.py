#!/usr/bin/env python3
"""Look up the stand coordinator through Keycloak's local admin API."""
import json
import sys
from urllib.parse import urlencode
from urllib.request import ProxyHandler, Request, build_opener


def main() -> None:
    with open(sys.argv[1], encoding="utf-8") as runtime:
        credentials = dict(
            line.rstrip("\r\n").split("=", 1)
            for line in runtime
            if line.startswith(("KEYCLOAK_ADMIN_USERNAME=", "KEYCLOAK_ADMIN_PASSWORD="))
        )

    base = "http://127.0.0.1:8084"
    local = build_opener(ProxyHandler({}))
    data = urlencode({
        "client_id": "admin-cli",
        "username": credentials["KEYCLOAK_ADMIN_USERNAME"],
        "password": credentials["KEYCLOAK_ADMIN_PASSWORD"],
        "grant_type": "password",
    }).encode()
    request = Request(base + "/realms/master/protocol/openid-connect/token", data=data)
    with local.open(request, timeout=30) as response:
        token = json.load(response)["access_token"]

    request = Request(
        base + "/admin/realms/dashboard/users?username=coordinator&exact=true",
        headers={"Authorization": f"Bearer {token}"},
    )
    with local.open(request, timeout=30) as response:
        users = json.load(response)
    if len(users) != 1:
        sys.exit("Expected exactly one coordinator user in the imported realm")
    print(users[0]["id"])


if __name__ == "__main__":
    main()
