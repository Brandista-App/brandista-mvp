"""Windows packaged-owner acceptance: real PowerShell consoles, JAR and TLS MySQL.

Only starts a fresh private native MySQL datadir on loopback; no external DB URL,
production credential, service installation or target-selection option is accepted.
Requires Python cryptography/pywinpty and an extracted MySQL Windows ZIP.
"""
import argparse
import datetime as dt
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import queue
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import zipfile

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from winpty import PtyProcess

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'target'
EVIDENCE = TARGET / 'owner-packaged-evidence'
EVIDENCE.mkdir(parents=True, exist_ok=True)
WORK = TARGET / ('owner packaged ' + secrets.token_hex(6))
WORK.mkdir()
HIDDEN = [secrets.token_hex(24) for _ in range(6)]
DB_PASSWORD, STORE_PASSWORD, OWNER_PASSWORD, WRONG_PASSWORD, SECOND_PASSWORD, UNUSED = HIDDEN
RESULTS = []
ANSI = re.compile(r'\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07\x1b]*(?:\x07|\x1b\\))')


def clean_env():
    return {k: v for k, v in os.environ.items() if not k.startswith(
        ('SPRING_', 'BRANDISTA_', 'MYSQL_', 'JWT_', 'AIVEN_', 'JAVA_TOOL_OPTIONS',
         'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH', 'MAVEN_OPTS'))}


def scrub(text):
    for value in HIDDEN:
        text = text.replace(value, '[HIDDEN-FIXTURE]')
    return ANSI.sub('', text)


def run(args, *, env=None, input=None, check=True, timeout=180):
    result = subprocess.run([str(a) for a in args], cwd=ROOT, env=env or clean_env(),
                            input=input, text=True, capture_output=True, timeout=timeout,
                            creationflags=subprocess.CREATE_NO_WINDOW)
    if check and result.returncode:
        raise AssertionError('Isolated command failed: ' + Path(str(args[0])).name + ': ' + scrub(result.stderr[-1500:]))
    return result


def record(label):
    RESULTS.append({'case': label, 'result': 'PASS'})
    (EVIDENCE / 'results.json').write_text(json.dumps(RESULTS, indent=2))
    print('PASS:', label, flush=True)


def certificate_files():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Disposable owner fixture CA')])
    now = dt.datetime.now(dt.timezone.utc)
    ca = x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key()).serial_number(
        x509.random_serial_number()).not_valid_before(now-dt.timedelta(minutes=5)).not_valid_after(now+dt.timedelta(days=2)).add_extension(
        x509.BasicConstraints(ca=True, path_length=None), True).sign(key, hashes.SHA256())
    leaf_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    leaf = x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'localhost')])).issuer_name(
        subject).public_key(leaf_key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(
        now-dt.timedelta(minutes=5)).not_valid_after(now+dt.timedelta(days=2)).add_extension(
        x509.SubjectAlternativeName([x509.DNSName('localhost')]), False).sign(key, hashes.SHA256())
    (WORK/'ca.pem').write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    (WORK/'server.pem').write_bytes(leaf.public_bytes(serialization.Encoding.PEM))
    (WORK/'server.key').write_bytes(leaf_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()))
    profile = WORK/'profile'; profile.mkdir()
    store = profile/'brandista-aiven-truststore.p12'
    run(['keytool', '-importcert', '-noprompt', '-alias', 'fixture-ca', '-file', WORK/'ca.pem',
         '-keystore', store, '-storetype', 'PKCS12', '-storepass:env', 'OWNER_FIXTURE_STORE_PASSWORD'],
        env=dict(clean_env(), OWNER_FIXTURE_STORE_PASSWORD=STORE_PASSWORD))
    return store, profile


class Terminal:
    def __init__(self, args, env):
        self.child = PtyProcess.spawn([str(a) for a in args], cwd=str(ROOT), env=env, dimensions=(60,240))
        self.messages = queue.Queue()
        self.output = ''
        def read():
            try:
                while True: self.messages.put(self.child.read(8192))
            except (EOFError, OSError): pass
        threading.Thread(target=read, daemon=True).start()

    def drain(self):
        while not self.messages.empty(): self.output += self.messages.get()

    def expect(self, text, timeout=120):
        deadline = time.monotonic()+timeout
        while time.monotonic() < deadline:
            self.drain()
            if text in ANSI.sub('', self.output): return
            if not self.child.isalive(): break
            time.sleep(.05)
        raise AssertionError('Console did not reach '+text+': '+scrub(self.output[-2400:]))

    def enter(self, prompt, value, timeout=120):
        self.expect(prompt, timeout)
        self.child.write(value+'\r\n')

    def finish(self, expected, timeout=90):
        deadline = time.monotonic()+timeout
        while self.child.isalive() and time.monotonic()<deadline:
            self.drain(); time.sleep(.05)
        self.drain(); time.sleep(.2); self.drain()
        if self.child.isalive():
            self.child.terminate(force=True)
            raise AssertionError('Console did not terminate: '+scrub(self.output[-2000:]))
        actual = self.child.exitstatus
        output = ANSI.sub('', self.output)
        # Assert masking before any redaction. Never write raw terminal transcripts as artifacts.
        if any(secret in output for secret in HIDDEN) or re.search(r'\$2[aby]\$\d\d\$[./A-Za-z0-9]{53}', output):
            raise AssertionError('A hidden fixture credential or password hash was echoed')
        if actual != expected:
            raise AssertionError(f'Expected console exit {expected}, got {actual}: '+scrub(output[-2500:]))
        self.child.close()
        return output


parser = argparse.ArgumentParser()
parser.add_argument('--mysql-bin', required=True, type=Path)
parser.add_argument('--shell', choices=['powershell', 'pwsh'], required=True)
args = parser.parse_args()
mysql = args.mysql_bin.resolve()/'mysql.exe'
mysqld = args.mysql_bin.resolve()/'mysqld.exe'
if not mysql.is_file() or not mysqld.is_file(): raise SystemExit('Native isolated MySQL binaries are required')
shell = shutil.which(args.shell)
if not shell: raise SystemExit('Required PowerShell executable is unavailable')
jar = TARGET/'brandista-0.0.1-SNAPSHOT.jar'
if not jar.is_file(): raise SystemExit('Build the real executable JAR first')
head = run(['git','rev-parse','HEAD']).stdout.strip()
store, profile = certificate_files()
env = dict(clean_env(), USERPROFILE=str(profile))
with socket.socket() as port_socket:
    port_socket.bind(('127.0.0.1',0)); port = port_socket.getsockname()[1]
datadir = WORK/'mysql-data'
server = None


def sql(statement, password=DB_PASSWORD, check=True):
    return run([mysql,'--protocol=TCP','-hlocalhost','-P',str(port),'-uroot','-N',
                '--ssl-mode=VERIFY_IDENTITY','--ssl-ca='+str(WORK/'ca.pem')],
               input=statement, env=dict(clean_env(),MYSQL_PWD=password),check=check)


def launch(*extra, host='localhost', defaults=False):
    command=[shell,'-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-File',ROOT/'scripts/production-owner.ps1',
             '-ReviewedCommit',head]
    if not defaults: command+=['-DatabaseHost',host,'-DatabasePort',str(port)]
    command+=list(extra)
    term=Terminal(command,env)
    if defaults:
        term.enter('Approved Aiven MySQL DNS hostname',host,timeout=900)
        term.enter('Approved Aiven MySQL port',str(port))
    return term


def credentials(term, *, db_password=DB_PASSWORD, store_password=STORE_PASSWORD):
    term.enter('Truststore password (hidden):',store_password,timeout=900)
    term.enter('Type CONNECT brandista', 'CONNECT brandista')
    term.enter('Aiven database username (hidden):', 'root')
    term.enter('Aiven database password (hidden):',db_password)


try:
    run([mysqld,'--no-defaults','--initialize-insecure','--basedir='+str(args.mysql_bin.resolve().parent),
         '--datadir='+str(datadir)], timeout=180)
    log = open(WORK/'server.log','w')
    server=subprocess.Popen([str(mysqld),'--no-defaults','--basedir='+str(args.mysql_bin.resolve().parent),
        '--datadir='+str(datadir),'--bind-address=127.0.0.1','--port='+str(port),'--mysqlx=OFF',
        '--ssl-ca='+str(WORK/'ca.pem'),'--ssl-cert='+str(WORK/'server.pem'),'--ssl-key='+str(WORK/'server.key'),
        '--require-secure-transport=ON'], stdout=log, stderr=log, creationflags=subprocess.CREATE_NO_WINDOW)
    for _ in range(90):
        if server.poll() is not None: raise AssertionError('Isolated MySQL exited during initialization')
        if sql('SELECT 1;',password='',check=False).returncode == 0: break
        time.sleep(1)
    else: raise AssertionError('Isolated loopback MySQL did not become ready')
    sql("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+DB_PASSWORD+"'; CREATE DATABASE brandista;",password='')
    libs=WORK/'libs'; libs.mkdir()
    with zipfile.ZipFile(jar) as package:
        for entry in package.namelist():
            if entry.startswith('BOOT-INF/lib/') and entry.endswith('.jar'):
                (libs/Path(entry).name).write_bytes(package.read(entry))
    fixture_env=dict(clean_env(), OWNER_FIXTURE_PORT=str(port),OWNER_FIXTURE_STORE=str(store),
                     OWNER_FIXTURE_PASSWORD=DB_PASSWORD,OWNER_FIXTURE_STORE_PASSWORD=STORE_PASSWORD)
    classpath=os.pathsep.join([str(TARGET/'test-classes'),str(TARGET/'classes'),str(libs/'*')])
    run(['java','-cp',classpath,'com.brandista.operator.OwnerPackagedFixtureMain'],env=fixture_env)
    record(args.shell+': real TLS MySQL, verified PKCS12 CA and Flyway V1-V14 fixture')

    direct=['java','-Dloader.main=com.brandista.operator.OwnerProvisioningMain','-cp',str(jar),
            'org.springframework.boot.loader.launch.PropertiesLauncher','--host','localhost','--port',str(port),'--truststore',str(store)]
    no_console=run(direct,check=False)
    assert no_console.returncode==20 and 'CONSOLE_UNAVAILABLE' in no_console.stderr
    assert 'password (hidden)' not in no_console.stdout
    record(args.shell+': packaged JAR rejects missing console before secrets or DB access')

    term=launch('-VerifyOnly',defaults=True); credentials(term)
    absent=term.finish(1)
    assert 'ACCOUNT_ABSENT' in absent and 'DATABASE_OK' in absent and 'New Super Admin password' not in absent
    assert sql('SELECT COUNT(*) FROM brandista.users;').stdout.strip()=='0'
    record(args.shell+': one-command launcher/default truststore, masked credentials and read-only absence')

    term=launch(); credentials(term)
    term.enter('To explicitly authorize creation', 'CANCEL')
    assert 'CANCELLED' in term.finish(1)
    assert sql('SELECT COUNT(*) FROM brandista.users;').stdout.strip()=='0'
    record(args.shell+': cancelled creation performs no user mutation')

    term=launch(); credentials(term)
    term.enter('To explicitly authorize creation','CREATE uzair@brandistaapp.com IN brandista')
    term.enter('New Super Admin password (hidden;',OWNER_PASSWORD)
    term.enter('Confirm new Super Admin password (hidden):',OWNER_PASSWORD)
    created=term.finish(0)
    assert 'SUCCESS: uzair@brandistaapp.com was created and verified in brandista' in created
    state=sql("USE brandista; SELECT id,email,role,status,SHA2(password_hash,256) FROM users; SELECT COUNT(*) FROM security_audit_events;").stdout
    assert 'uzair@brandistaapp.com\tCUSTOMER\tENABLED' in state
    record(args.shell+': real packaged BCrypt creation and audited committed SUPER_ADMIN verification')

    for _ in range(2):
        term=launch(); credentials(term)
        existing=term.finish(0)
        assert 'ACCOUNT_EXISTS' in existing and 'No account changes were made' in existing
        assert 'New Super Admin password' not in existing
        assert sql("USE brandista; SELECT id,email,role,status,SHA2(password_hash,256) FROM users; SELECT COUNT(*) FROM security_audit_events;").stdout==state
    record(args.shell+': repeated existing-account setup preserves password, grants and audit state')

    term=launch(); credentials(term,db_password=WRONG_PASSWORD)
    wrong=term.finish(1)
    assert 'DATABASE_CREDENTIALS' in wrong and 'NO_ACCOUNT_CHANGES' in wrong
    record(args.shell+': incorrect credentials have a safe distinct diagnostic and nonzero exit')

    term=launch(); term.enter('Truststore password (hidden):',WRONG_PASSWORD,timeout=900)
    wrong_store=term.finish(1)
    assert 'TRUSTSTORE_INVALID' in wrong_store and 'Aiven database username' not in wrong_store
    record(args.shell+': wrong PKCS12 password fails before a DB connection')

    term=launch(host='127.0.0.1'); credentials(term)
    tls=term.finish(1)
    assert 'TLS_FAILED' in tls and 'NO_ACCOUNT_CHANGES' in tls
    record(args.shell+': hostname mismatch fails with TLS verification retained')

    sql("UPDATE brandista.flyway_schema_history SET success=0 WHERE version='14';")
    term=launch(); credentials(term)
    history=term.finish(1)
    assert 'MIGRATIONS_MISSING' in history
    sql("UPDATE brandista.flyway_schema_history SET success=1 WHERE version='14';")
    record(args.shell+': missing successful migration is distinguished without auto migration')

    sql("UPDATE brandista.users SET status='DISABLED';")
    term=launch(); credentials(term)
    disabled=term.finish(1)
    assert 'ACCOUNT_NOT_READY' in disabled and 'New Super Admin password' not in disabled
    sql("UPDATE brandista.users SET status='ENABLED';")
    record(args.shell+': existing disabled account is never silently enabled or changed')

    with socket.socket() as closed_socket:
        closed_socket.bind(('127.0.0.1',0)); closed_port=closed_socket.getsockname()[1]
    term=Terminal([shell,'-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-File',ROOT/'scripts/production-owner.ps1',
                   '-ReviewedCommit',head,'-DatabaseHost','localhost','-DatabasePort',str(closed_port)],env)
    credentials(term)
    offline=term.finish(1)
    assert 'CONNECTION_FAILED' in offline
    record(args.shell+': refused loopback connection has a safe distinct nonzero diagnostic')

    result=run([shell,'-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-File',ROOT/'scripts/production-owner.ps1',
                '-ReviewedCommit',head,'-DatabaseHost','localhost','-DatabasePort',str(port)],env=env,check=False)
    assert result.returncode != 0 and 'CONSOLE_UNAVAILABLE' in result.stdout+result.stderr
    record(args.shell+': real launcher preserves noninteractive Java failure')
finally:
    if server is not None:
        if server.poll() is None:
            admin=args.mysql_bin.resolve()/'mysqladmin.exe'
            run([admin,'--protocol=TCP','-hlocalhost','-P',str(port),'-uroot','--ssl-mode=VERIFY_IDENTITY',
                 '--ssl-ca='+str(WORK/'ca.pem'),'shutdown'],env=dict(clean_env(),MYSQL_PWD=DB_PASSWORD),check=False)
            try: server.wait(timeout=20)
            except subprocess.TimeoutExpired: server.terminate(); server.wait(timeout=20)
        log.close()
    # Only this newly created nonce directory; no existing service/datadir is ever stopped or removed.
    if WORK.resolve().parent != TARGET.resolve() or not WORK.name.startswith('owner packaged '):
        raise RuntimeError('Refusing cleanup outside the isolated fixture directory')
    shutil.rmtree(WORK)
