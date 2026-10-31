#!/usr/bin/env python3
"""Qualify feature-disable behavior and competing Fedora workers on disposable resources only."""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--server-output', type=Path, required=True, help='Owned server-b output')
    parser.add_argument('--classpath', type=Path, required=True)
    parser.add_argument('--java', required=True)
    args = parser.parse_args()
    assert args.database.startswith('sc2150_') and args.database.replace('_', '').isalnum()
    assert args.server_output.name == 'server-b'
    fixture = json.loads((args.evidence / 'fixture-results.json').read_text())[0]
    base_path = '/api/debug/mouse-videos'

    def request(path, port=18191, body=None, raw=None, headers=None):
        headers = dict(headers or {})
        if body is not None or raw is not None:
            headers['X-Mouse-Video-Nonce'] = request('/write-nonce', port)[1]['nonce']
        if body is not None:
            raw = json.dumps(body).encode()
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(f'http://127.0.0.1:{port}{base_path}{path}', data=raw, headers=headers)
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read()
            return response.status, json.loads(data) if 'json' in response.headers.get('Content-Type', '') else data

    def sql(statement):
        return subprocess.check_output(['docker', 'exec', '-i', 'chromascape-postgres', 'psql', '-X', '-qAt',
            '-v', 'ON_ERROR_STOP=1', '-U', 'chromascape', '-d', args.database], input=statement.encode()).decode().strip()

    def stop():
        pid = int((args.server_output / 'server.pid').read_text())
        proc = Path(f'/proc/{pid}/cmdline')
        if not proc.exists() or not proc.read_bytes():
            return
        assert b'-Duser.name=sc2150-' in proc.read_bytes() and b'ChromaScapeApplication' in proc.read_bytes()
        os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic() + 20
        while proc.exists() and proc.read_bytes() and time.monotonic() < deadline:
            time.sleep(.1)
        if proc.exists() and proc.read_bytes():
            assert b'-Duser.name=sc2150-' in proc.read_bytes()
            os.kill(pid, signal.SIGKILL)
            time.sleep(.2)

    def start(disabled):
        stop()
        command = ['python3', 'scripts/sc2150/start_verification.py', '--classpath', str(args.classpath),
            '--java', args.java, '--output', str(args.server_output), '--database', args.database,
            '--port', '18191', '--owner', 'sc2150-fedora-a']
        if disabled:
            command.append('--disable-workers')
        label = 'disabled' if disabled else 'competing'
        with (args.evidence / (label + '-startup.log')).open('w') as out:
            subprocess.run(command, stdout=out, stderr=subprocess.STDOUT, check=True, timeout=240)

    start(True)
    source = '/sources/' + fixture['sourceId']
    analysis = source + '/analyses/' + fixture['analysisId']
    count = sql('select count(*) from mouse_video_jobs;')
    assert request(source)[0] == 200
    assert request(source + '/media/ORIGINAL')[0] == 200
    export = request(source + '/exports')[1]['items'][0]
    assert request('/exports/' + export['id'])[0] == 200
    assert request(source + '/analyses', body={'requestId': str(uuid.uuid4()), 'algorithmId': 'visual/v1'})[0] == 503
    assert request(analysis + '/frame-previews', body={'requestId': str(uuid.uuid4()), 'frameOrdinal': 22})[0] == 503
    assert request('/imports/upload', raw=b'', headers={'Content-Type': 'multipart/form-data; boundary=disabled',
        'X-Mouse-Video-Request-Id': str(uuid.uuid4())})[0] == 503
    assert sql('select count(*) from mouse_video_jobs;') == count
    (args.evidence / 'disable-results.json').write_text(json.dumps({'existingSourceMediaExport': 200,
        'newUploadAnalysisPreview': 503, 'noJobsCreated': True}, indent=2) + '\n')
    print('Feature disabled: existing reads preserved, all three scheduling routes refused', flush=True)
    start(False)
    def schedule(index):
        code, receipt = request(analysis + '/frame-previews', 18190 + index % 2,
            body={'requestId': str(uuid.uuid4()), 'frameOrdinal': 20+index, 'maxWidth': 101+index, 'maxHeight': 79})
        assert code in (200,202), (code, receipt)
        return receipt
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        scheduled = list(pool.map(schedule, range(6)))
    seen_owners = set()
    pending = {row['jobId']: row for row in scheduled}
    deadline = time.monotonic() + 120
    while pending and time.monotonic() < deadline:
        for job_id, row in list(pending.items()):
            code, job = request('/jobs/' + job_id, 18190)
            assert code == 200
            if job.get('leaseOwner'):
                seen_owners.add(job['leaseOwner'])
            if job['status'] not in ('QUEUED','RUNNING'):
                assert job['status'] == 'SUCCEEDED', job
                assert request('/frame-previews/' + row['previewId'],18190)[1]['state'] == 'READY'
                assert sql(f"select count(*) from mouse_video_job_events where job_id='{uuid.UUID(job_id)}' and event_type in ('CLAIMED','RECLAIMED');") == '1'
                pending.pop(job_id)
        time.sleep(.1)
    assert not pending, pending
    result = {'previews': scheduled, 'eachClaimedExactlyOnce': True, 'allReady': True,
        'observedLeaseOwners': sorted(seen_owners)}
    (args.evidence / 'competing-results.json').write_text(json.dumps(result, indent=2)+'\n')
    print('PASS six previews through two enabled JVMs; each claimed once; all READY', flush=True)


if __name__ == '__main__':
    main()
