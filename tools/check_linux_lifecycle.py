"""Exercise the real Linux GUI under X11 in an isolated user-data directory."""
import argparse
import ctypes as C
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time


class MessageData(C.Union):
    _fields_ = [('bytes', C.c_char * 20), ('shorts', C.c_short * 10), ('longs', C.c_long * 5)]


class ClientMessage(C.Structure):
    _fields_ = [('type', C.c_int), ('serial', C.c_ulong), ('send_event', C.c_int),
                ('display', C.c_void_p), ('window', C.c_ulong), ('message_type', C.c_ulong),
                ('format', C.c_int), ('data', MessageData)]


class Event(C.Union):
    _fields_ = [('client', ClientMessage), ('padding', C.c_long * 24)]


def command(*args):
    return subprocess.run(args, capture_output=True, text=True, timeout=5)


def wait_until(check, seconds=25):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        time.sleep(.15)
    raise AssertionError('Linux window lifecycle deadline expired')


def window_for(process):
    if process.poll() is not None:
        raise AssertionError('Application exited before its window opened')
    matches = command('xdotool', 'search', '--onlyvisible', '--pid', str(process.pid)).stdout.splitlines()
    return next((value for value in matches if command('xdotool', 'getwindowname', value).stdout.strip() == 'DrKozol'), None)


def hidden(window):
    return '_NET_WM_STATE_HIDDEN' in command('xprop', '-id', window, '_NET_WM_STATE').stdout


def close_window(window):
    x = C.CDLL('libX11.so.6')
    x.XOpenDisplay.argtypes = [C.c_char_p]; x.XOpenDisplay.restype = C.c_void_p
    x.XInternAtom.argtypes = [C.c_void_p, C.c_char_p, C.c_int]; x.XInternAtom.restype = C.c_ulong
    x.XSendEvent.argtypes = [C.c_void_p, C.c_ulong, C.c_int, C.c_long, C.POINTER(Event)]
    x.XFlush.argtypes = [C.c_void_p]; x.XCloseDisplay.argtypes = [C.c_void_p]
    display = x.XOpenDisplay(None)
    assert display
    try:
        event = Event()
        event.client.type = 33; event.client.send_event = 1; event.client.display = display
        event.client.window = int(window); event.client.format = 32
        event.client.message_type = x.XInternAtom(display, b'WM_PROTOCOLS', 0)
        event.client.data.longs[0] = x.XInternAtom(display, b'WM_DELETE_WINDOW', 0)
        assert x.XSendEvent(display, int(window), 0, 0, C.byref(event))
        x.XFlush(display)
    finally:
        x.XCloseDisplay(display)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('executable', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    executable = args.executable.resolve()
    owned = []
    checks = []
    manager = subprocess.Popen(['openbox', '--sm-disable'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        with tempfile.TemporaryDirectory(prefix='drkozol-lifecycle-') as directory:
            env = dict(os.environ, XDG_DATA_HOME=directory, V2RAYN_LOCAL_APPLICATION_DATA_V2='1')
            env.pop('DRKOZOL_TEST_URL', None)
            log = open(Path(directory) / 'process.log', 'wb')

            def launch():
                process = subprocess.Popen([str(executable)], env=env, stdout=log, stderr=log)
                owned.append(process)
                return process

            first = launch(); window = wait_until(lambda: window_for(first))
            checks.append('cold_start')
            assert command('xdotool', 'windowminimize', window).returncode == 0
            wait_until(lambda: hidden(window))
            second = launch(); assert second.wait(timeout=10) == 0
            wait_until(lambda: not hidden(window))
            wait_until(lambda: command('xdotool', 'getactivewindow').stdout.strip() == window)
            assert first.poll() is None
            checks.append('second_launch_restores_minimized_existing_window')
            close_window(window); assert first.wait(timeout=25) == 0
            checks.append('close_exits_without_task_manager')
            third = launch(); window = wait_until(lambda: window_for(third))
            checks.append('reopen_after_close')
            third.kill(); third.wait(timeout=10)
            fourth = launch(); window = wait_until(lambda: window_for(fourth))
            checks.append('abandoned_lease_recovers_after_forced_process_death')
            close_window(window); assert fourth.wait(timeout=25) == 0
            checks.append('second_clean_close')
            log.close()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({'success': True, 'checks': checks}, indent=2))
        print(json.dumps({'success': True, 'checks': checks}), flush=True)
    finally:
        for process in owned:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        manager.terminate(); manager.wait(timeout=5)


if __name__ == '__main__':
    main()
