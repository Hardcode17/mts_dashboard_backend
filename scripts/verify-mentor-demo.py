"""Read the deployed workspace through the normal coordinator API on the Pi."""
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

mentor = Path(".stand-sources/data/docs/mentor-demo/pi")
if not mentor.is_dir():
    raise SystemExit(0)

fixture = json.loads((mentor / "incidents.json").read_text())
example = json.loads((mentor / "investigation.json").read_text())
delivery_id = "INC943741270571"
coordinators = "КЦ ИТ Координаторы Массовых Инцидентов"
members = {row["id"] for row in fixture if row["id"] != delivery_id}
services = {row["service"] for row in fixture if row["id"] in members}
anchor = example["incidentId"]
if len(members) != 100 or len(services) != 11 or anchor not in members:
    raise SystemExit("Incorrect mentor scenario membership in the data repository")

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
headers = {"Authorization": "Bearer " + auth.stdout.strip()}
origin = "http://127.0.0.1:" + os.environ.get("FRONTEND_PORT", "8080")


def get(path):
    with urlopen(Request(origin + path, headers=headers), timeout=20) as response:
        return json.load(response)


for _ in range(90):
    desk = get("/api/workspace/snapshot?mode=case")["desk"]
    records = desk["records"]
    delivery = records.get(delivery_id, {})
    if (delivery.get("priority") == 2 and delivery.get("team") == coordinators
            and "coordinators" in delivery.get("groups", []) and not delivery.get("absent")):
        group = next((g for g in desk.get("outageGroups", []) if set(g["members"]) == members), None)
        report = example["report"]
        reports = desk["work"].get(anchor, {}).get("reports", [])
        if (group and {records[key].get("service") for key in members} == services
                and all(records[key]["priority"] == 2 and not records[key].get("absent") for key in members)
                and report in reports):
            print(f"Verified {delivery_id} in the coordinator queue (P2).")
            print(f"Verified {group['id']}: 100 active P2 incidents, 11 services.")
            print(f"Verified {anchor}: saved MCP investigation {report['id']}, team and log evidence.")
            break
    time.sleep(2)
else:
    raise SystemExit("Mentor delivery, outage group or saved investigation is missing from the live workspace")
