"""Explicitly authorized staging-only delivery smoke test. Never run by normal CI.

Creates one synthetic application and sends ONE real staging email, then polls the
backend until the provider reports delivery. Credentials come only from the process
environment. Token/link and provider bodies are never printed.
"""
import argparse
import json
import os
import time
import uuid
import urllib.request
from urllib.parse import urlparse

parser = argparse.ArgumentParser()
parser.add_argument('--api', required=True, help='Staging HTTPS API ending /api/v1')
parser.add_argument('--staging-host', required=True, help='Exact separately verified staging hostname')
parser.add_argument('--recipient', required=True, help='Authorized operator-controlled staging inbox')
parser.add_argument('--allow-send-staging-email', action='store_true')
args = parser.parse_args()
origin = urlparse(args.api)
if (not args.allow_send_staging_email or origin.scheme != 'https' or origin.hostname != args.staging_host
        or 'staging' not in args.staging_host or origin.path != '/api/v1'
        or origin.username or origin.password or origin.query or origin.fragment):
    raise SystemExit('Explicit staging email authorization and an exact staging HTTPS host are required.')


def call(method, path, body=None, token=None):
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(args.api + path,
        data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    # Redirects could disclose credentials to a different host.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *unused):
            return None
    with urllib.request.build_opener(NoRedirect).open(request, timeout=25) as response:
        return json.load(response)


try:
    session = call('POST', '/auth/login', {'email': os.environ['STAGING_SUPER_ADMIN_EMAIL'],
        'password': os.environ['STAGING_SUPER_ADMIN_PASSWORD']})['accessToken']
    app = call('POST', '/brand-applications', {'brandName': 'Staging delivery ' + str(uuid.uuid4()),
        'contactName': 'Authorized staging delivery test', 'contactEmail': args.recipient})
    call('PATCH', '/admin/brand-applications/' + str(app['id']) + '/review', {'decision': 'APPROVED'}, session)
    path = '/super-admin/brand-applications/' + str(app['id']) + '/invitations'
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        invitations = call('GET', path, token=session)['content']
        if len(invitations) != 1:
            raise RuntimeError('Expected exactly one invitation.')
        delivery = invitations[0]['delivery']
        if delivery['state'] == 'DELIVERED':
            print(json.dumps({'applicationId': app['id'], 'invitationId': invitations[0]['id'],
                'delivery': 'DELIVERED', 'attempts': delivery['attempts'],
                'manualInboxAndLinkVerification': 'REQUIRED'}))
            break
        if delivery['state'] in ('FAILED', 'CANCELLED'):
            raise RuntimeError('Delivery failed. Inspect safe status in Super Admin.')
        time.sleep(10)
    else:
        raise RuntimeError('Delivery was not confirmed within ten minutes; do not claim success.')
except Exception:
    raise SystemExit('Staging delivery test did not complete. Inspect Super Admin and provider status; no secret-bearing response is printed.') from None
