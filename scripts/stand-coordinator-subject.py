#!/usr/bin/env python3
"""Resolve demo coordinator identities through Keycloak's local admin API."""
import json
import os
import sys
from urllib.parse import quote, urlencode
from urllib.request import ProxyHandler, Request, build_opener


def main() -> None:
    with open(sys.argv[1], encoding="utf-8") as runtime:
        credentials = dict(
            line.rstrip("\r\n").split("=", 1)
            for line in runtime
            if line.startswith(("KEYCLOAK_ADMIN_USERNAME=", "KEYCLOAK_ADMIN_PASSWORD=",
                                "STAND_HANDOVER_COORDINATOR_PASSWORD="))
        )

    base = os.environ.get("KEYCLOAK_ADMIN_URL", "http://127.0.0.1:8084").rstrip("/")
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

    users_url = base + "/admin/realms/dashboard/users"
    auth = {"Authorization": f"Bearer {token}"}

    def find_user(login):
        query = urlencode({"username": login, "exact": "true"})
        with local.open(Request(f"{users_url}?{query}", headers=auth), timeout=30) as response:
            users = json.load(response)
        if len(users) > 1:
            sys.exit(f"Expected at most one Keycloak user named {login}")
        return users[0] if users else None

    coordinator = find_user("coordinator")
    if coordinator is None:
        sys.exit("Expected the coordinator user in the imported realm")
    if sys.argv[2:] != ["--seed-handover"]:
        print(coordinator["id"])
        return

    password = credentials["STAND_HANDOVER_COORDINATOR_PASSWORD"]
    roster = [{"subject": coordinator["id"], "login": "coordinator", "display_name": "Координатор"}]
    for number in range(1, 6):
        login = f"handover-coordinator-{number}"
        name = f"Координатор смены {number}"
        user = find_user(login)
        if user is None:
            account = {
                "username": login,
                "firstName": "Координатор",
                "lastName": f"смены {number}",
                "email": f"{login}@stand.local",
                "emailVerified": True,
                "enabled": True,
                "groups": ["/dashboard-users"],
                "credentials": [{"type": "password", "value": password, "temporary": False}],
            }
            create = Request(users_url, data=json.dumps(account).encode(),
                             headers={**auth, "Content-Type": "application/json"})
            with local.open(create, timeout=30):
                pass
            user = find_user(login)
        if user is None or not user["enabled"]:
            sys.exit(f"Expected an active demo coordinator named {login}")
        memberships = Request(f"{users_url}/{quote(user['id'], safe='')}/groups", headers=auth)
        with local.open(memberships, timeout=30) as response:
            if not any(group["path"] == "/dashboard-users" for group in json.load(response)):
                sys.exit(f"Demo coordinator {login} is missing the dashboard-users group")
        roster.append({"subject": user["id"], "login": login, "display_name": name})
    print(json.dumps(roster, ensure_ascii=False))


if __name__ == "__main__":
    main()
