#!/usr/bin/env python3
"""Sends a secret key to Free My TV on the TV, so it never goes into the APK (any app on the TV can read an installed
APK) or into config.properties.

    python3 tools/send_key.py 192.168.1.50 immich_api_key     # asks for the key (not shown)
    python3 tools/send_key.py immich_api_key                  # the TV adb is already connected to
    python3 tools/send_key.py --remove immich_api_key         # takes it off the TV

The key is asked for here (or read from standard input when it isn't a terminal) and goes over adb straight into the
app's private files (`content write` to its Secrets provider, which only adb may use). Keys:
  immich_api_key   Immich > Account Settings > API Keys (asset.read, asset.view, asset.download and album.read).
                   Checked against immich_server from config.properties first, when it's set.
"""
import getpass
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

URI = 'content://io.freemytv.secrets/'
NAMES = ('immich_api_key',)
SERIAL = []   # -s <ip>:5555 once connected, so a phone or another TV on adb doesn't get in the way
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def adb(*args, **kw):
    return subprocess.run(['adb', *SERIAL, *args], capture_output=True, **kw)


def prop(key):
    """A value from config.properties (the last one, as build.sh reads it), or ''."""
    value = ''
    try:
        with open(os.path.join(ROOT, 'config.properties'), encoding='utf-8') as f:
            for line in f:
                k, sep, v = line.partition('=')
                if sep and k.strip() == key:
                    value = v.strip().strip('"')
    except OSError:
        pass
    return value


def check_immich(key):
    """None if the server takes the key (the same call the TV makes), else why not; skipped without immich_server."""
    server = prop('immich_server').rstrip('/')
    if not server:
        return None
    req = urllib.request.Request(server + '/api/search/random', data=json.dumps({'size': 1}).encode(),
                                 headers={'x-api-key': key, 'Content-Type': 'application/json'})
    try:
        urllib.request.urlopen(req, timeout=8).close()
        return None
    except urllib.error.HTTPError as e:
        if e.code == 401:
            return 'Immich refused this key.'
        if e.code == 403:
            return 'Immich took the key but not for photos: give it asset.read, asset.view, asset.download and album.read.'
        return f'Immich answered HTTP {e.code}.'
    except Exception as e:
        return f'Could not reach {server} from this computer ({e}). Send it anyway with --no-check.'


CHECKS = {'immich_api_key': check_immich}


def main():
    args = sys.argv[1:]
    if not args or args[0] in ('-h', '--help'):
        print(__doc__.strip())
        return 0 if args else 2
    remove = '--remove' in args
    check = '--no-check' not in args
    args = [a for a in args if a not in ('--remove', '--no-check')]
    if not shutil.which('adb'):
        print('adb not found: install the Android platform tools (see the README).')
        return 1
    if args and args[0][0].isdigit():
        ip = args.pop(0)
        target = ip if ':' in ip else ip + ':5555'
        r = adb('connect', target, text=True)
        if 'connected' not in r.stdout:
            print(f'Could not connect to {target}: {(r.stdout + r.stderr).strip()}')
            return 1
        SERIAL[:] = ['-s', target]
    if len(args) != 1 or args[0] not in NAMES:
        print('Which key? One of: ' + ', '.join(NAMES))
        return 2
    name = args[0]
    listed = adb('shell', 'pm', 'list', 'packages', 'io.freemytv', text=True)
    if listed.returncode != 0:
        print('No TV connected: give its IP address first (adb devices lists the connected ones).')
        return 1
    if 'package:io.freemytv' not in listed.stdout.split():
        print('Free My TV is not installed on this TV.')
        return 1
    # exec-in brings back nothing the TV prints: check once that its Free My TV has the way in.
    probe = adb('shell', 'content', 'query', '--uri', URI, text=True)
    if 'Could not find provider' in probe.stdout + probe.stderr:
        print('The Free My TV on this TV is too old to take keys: install the new FreeMyTV.apk (see Install), then '
              'send it again.')
        return 1

    if remove:
        adb('shell', 'content', 'delete', '--uri', URI + name)
        print(f'{name} is off the TV.')
        return 0
    key = (getpass.getpass(f'{name} (not shown): ') if sys.stdin.isatty() else sys.stdin.readline()).strip()
    if not key:
        print('No key given.')
        return 1
    if check and name in CHECKS:
        why = CHECKS[name](key)
        if why:
            print(why)
            return 1
    r = adb('exec-in', 'content', 'write', '--uri', URI + name, input=key.encode() + b'\n')
    if r.returncode != 0:
        print('The TV refused it: ' + (r.stdout + r.stderr).decode(errors='replace').strip())
        return 1
    print(f'Sent {name}. Free My TV uses it from the next time the home screen or its settings open.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
