"""Operate only the mentor cases of the deployed ITSM. No application or database migration."""
import argparse
import json
import subprocess as sp
import signal
import time
from pathlib import Path
from urllib.request import Request, urlopen

DELIVERY, CRITICAL = 'INC943741270571', 'INC943741270574'


def docker(*args, data=None):
    return sp.check_output(['docker', *args], input=data, text=True).strip()


def service_id(service):
    ids = docker('ps', '-q', '--filter', 'label=com.docker.compose.project=mts-dashboard-backend',
                 '--filter', 'label=com.docker.compose.service=' + service).split()
    if len(ids) != 1:
        raise RuntimeError(f'Expected one running {service} container')
    return ids[0]


def operate(action):
    mock = service_id('itsm-mock')
    binding = docker('port', mock, '8080/tcp')
    if not binding.startswith('127.0.0.1:') or not binding[10:].isdigit():
        raise RuntimeError('Expected ITSM on a loopback port')
    origin = 'http://' + binding

    def mock_request(path, post=False):
        with urlopen(Request(origin+path, data=b'' if post else None), timeout=30) as reply:
            return json.load(reply)

    rows = {r['id']: r for r in mock_request('/itsm_incident')}
    if DELIVERY not in rows:
        raise RuntimeError('The existing mentor delivery is missing')
    if action != 'check':
        front_image = docker('inspect', '-f', '{{.Image}}', service_id('frontend'))
        # A single runner owns this operation. Stop writers before reading their persisted state.
        if mock_request('/api/mock/status')['pendingVersion'] is not None:
            raise RuntimeError('ITSM is delivering another snapshot; retry in a few seconds')
        if action == 'send':
            # Spring owns an INC per source. Never replace an identically numbered foreign card.
            source = mock_request('/api/mock/status')['sourceId']
            owner = docker('exec','-i',service_id('postgres'),'psql','-XqAt','-v','ON_ERROR_STOP=1',
                           '-U','dashboard','-d','dashboard','-v','inc='+CRITICAL,
                           data="SELECT source_id FROM itsm_incident WHERE id=:'inc';")
            if owner and owner != source:
                raise RuntimeError('The critical number belongs to another source')
        try:
            docker('stop', '-t', '30', mock)
            docker('run', '--rm', '-i', '--network', 'none', '--user', '10001:10001',
                   '--volumes-from', mock, '--entrypoint', 'node', front_image, '--input-type=module',
                   '-', action, '/var/lib/itsm-mock/snapshot.json', '/fixtures/character_problems.json',
                   data=Path(__file__).with_name('snapshot.mjs').read_text())
        finally:
            docker('start', mock)
        for _ in range(45):
            try:
                mock_request('/api/mock/status')
                break
            except OSError:
                time.sleep(1)
        sent = mock_request('/api/mock/emit', post=True)
        db = service_id('postgres')
        for _ in range(60):
            status = docker('exec','-i',db,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','dashboard','-d','dashboard',
                            '-v','source='+sent['sourceId'],'-v','version='+str(sent['deliveredVersion']),
                            data="SELECT status FROM itsm_ingest_batch WHERE source_id=:'source' AND source_version=:'version';")
            if status == 'SUCCEEDED':
                break
            if status == 'FAILED':
                raise RuntimeError('Spring rejected the demo snapshot')
            time.sleep(1)
        else:
            raise RuntimeError('Timed out waiting for Spring to apply the snapshot')

    from coordinator_api import workspace
    api = workspace()
    for _ in range(50):
        desk = api('/api/workspace/snapshot?mode=case')['desk']
        records = desk['records']
        delivery = records.get(DELIVERY, {})
        crit = records.get(CRITICAL, {})
        active = bool(crit and not crit.get('absent'))
        if (delivery.get('priority') == 2 and not delivery.get('absent') and 'coordinators' in delivery.get('groups', [])
                and (action == 'check' or (action == 'reset' and not active)
                     or (action == 'send' and active and 'critical' in crit.get('groups', [])))):
            break
        time.sleep(1)
    else:
        raise RuntimeError('Dashboard has not confirmed the expected delivery and critical state')
    if action == 'reset':
        old = [s['id'] for s in desk.get('signals', []) if s['key'] in (CRITICAL,DELIVERY) and not s.get('acknowledged')]
        for i in range(0,len(old),500):
            api('/api/dashboard/shift/events/ack', {'eventIds':old[i:i+500]})
    print(f'Доставка {DELIVERY}: P2, на координаторах. Крит {CRITICAL}: '+('активен' if active else 'отсутствует в активных'))


if __name__ == '__main__':
    def interrupted(signum, _frame):
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGTERM, interrupted)
    p=argparse.ArgumentParser()
    p.add_argument('action', choices=['check','reset','send'])
    operate(p.parse_args().action)
