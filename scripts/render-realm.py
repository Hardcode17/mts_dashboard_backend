#!/usr/bin/env python3
"""Render the Keycloak realm fixture for the stand deploy.

Mirrors scripts/RenderRealm.java: Keycloak imports the realm as-is and substitutes nothing
inside it, so the file is rendered once, before the container starts. The stand runner has
no JDK, hence this Python twin; keep both marker tables in sync.

Every value comes from the environment and is required, including the published frontend
origin. Usage: render-realm.py <source> <target>.
"""
import json
import os
import sys

MARKERS = {
    "__DASHBOARD_TEST_USERNAME__": "DASHBOARD_TEST_USERNAME",
    "__DASHBOARD_TEST_EMAIL__": "DASHBOARD_TEST_EMAIL",
    "__DASHBOARD_TEST_PASSWORD__": "DASHBOARD_TEST_PASSWORD",
    "__ITSM_SENDER_CLIENT_SECRET__": "ITSM_SENDER_CLIENT_SECRET",
    "__MCP_AGENT_CLIENT_SECRET__": "MCP_AGENT_CLIENT_SECRET",
    "__DASHBOARD_WEB_ORIGIN__": "DASHBOARD_WEB_ORIGIN",
}


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit("usage: render-realm.py <source> <target>")
    with open(sys.argv[1], encoding="utf-8") as source:
        realm = source.read()
    for marker, variable in MARKERS.items():
        value = os.environ.get(variable, "").strip()
        if not value:
            sys.exit(f"render-realm: missing environment variable {variable}")
        realm = realm.replace(marker, json.dumps(value)[1:-1])
    if "__" in realm or "${" in realm:
        sys.exit("render-realm: the realm still contains placeholders")
    with open(sys.argv[2], "w", encoding="utf-8") as target:
        target.write(realm)


main()
