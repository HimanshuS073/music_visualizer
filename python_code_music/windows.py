# SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
# SPDX-License-Identifier: GPL-3.0-or-later

"""Windows mpv pipes and WASAPI capture."""
import array
import audioop
import ctypes
from ctypes import wintypes
import json
import os
import time


def pipe_command(path, command, timeout=2):
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateFileW.restype = wintypes.HANDLE
    kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                  ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    kernel.PeekNamedPipe.argtypes = [wintypes.HANDLE, ctypes.c_void_p, wintypes.DWORD,
                                    ctypes.c_void_p, ctypes.POINTER(wintypes.DWORD), ctypes.c_void_p]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    deadline = time.monotonic() + timeout
    handle = None
    while time.monotonic() < deadline:
        handle = kernel.CreateFileW(path, 0xC0000000, 0, None, 3, 0, None)
        if handle != wintypes.HANDLE(-1).value:
            break
        time.sleep(.03)
    else:
        raise OSError('Player is still loading. Try again.')
    import msvcrt
    descriptor = msvcrt.open_osfhandle(handle, os.O_RDWR | os.O_BINARY)
    with os.fdopen(descriptor, 'r+b', buffering=0) as pipe:
        pipe.write((json.dumps({'command': list(command), 'request_id': 1}) + '\n').encode())
        data = b''
        available = wintypes.DWORD()
        while time.monotonic() < deadline:
            if not kernel.PeekNamedPipe(handle, None, 0, None, ctypes.byref(available), None):
                raise OSError('Player disconnected.')
            if not available.value:
                time.sleep(.005)
                continue
            data += pipe.read(min(available.value, 65536))
            while b'\n' in data:
                line, data = data.split(b'\n', 1)
                reply = json.loads(line)
                if reply.get('request_id') == 1:
                    if reply.get('error') != 'success':
                        raise OSError('Control unavailable while loading: ' + str(reply.get('error')))
                    return reply.get('data')
    raise OSError('Player did not acknowledge the command.')


def capture_audio(player, state, process_id):
    """Capture the output mix, including sound from other apps."""
    audio = stream = None
    try:
        import pyaudiowpatch as pyaudio
        audio = pyaudio.PyAudio()
        device = audio.get_default_wasapi_loopback()
        channels = int(device['maxInputChannels'])
        rate = int(device['defaultSampleRate'])
        samples = bytearray()
        conversion = None
        size = state['fft_size'] * 2
        def receive(data, frame_count, clock, flags):
            nonlocal conversion
            if state['stop'].is_set():
                return (None, pyaudio.paComplete)
            if channels == 2:
                data = audioop.tomono(data, 2, .5, .5)
            elif channels > 2:
                values = array.array('h', data)
                data = array.array('h', (int(sum(values[i:i+channels]) / channels)
                                        for i in range(0, len(values), channels))).tobytes()
            if rate != state['sample_rate']:
                data, conversion = audioop.ratecv(data, 2, 1, rate, state['sample_rate'], conversion)
            samples.extend(data)
            if len(samples) >= size:
                with state['lock']:
                    state['pcm'] = bytes(samples[-size:])
                    state['sequence'] += 1
                    state['updated'] = time.monotonic()
                del samples[:-size]
            return (None, pyaudio.paContinue)
        stream = audio.open(format=pyaudio.paInt16, channels=channels, rate=rate,
                            input=True, input_device_index=device['index'], frames_per_buffer=512,
                            stream_callback=receive)
        while not state['stop'].wait(.02):
            process = player['player']
            if process is None or process.pid != process_id or process.poll() is not None:
                return
            if not stream.is_active():
                raise OSError('The audio capture device stopped.')
    except (ImportError, OSError, ValueError) as error:
        if not state['stop'].is_set():
            with state['lock']:
                state['error'] = 'Windows audio monitoring unavailable: ' + str(error)
    finally:
        if stream is not None:
            stream.stop_stream()
            stream.close()
        if audio is not None:
            audio.terminate()
