"""Monitor only this player's output. PCM stays in memory; Lua performs the FFT."""

import base64
import json
import os
import select
import subprocess
import threading
import time

from .playback import stop_process


def _list_audio_objects(state, kind):
    if state['stop'].is_set():
        return []
    process = subprocess.Popen(['pactl', '-f', 'json', 'list', kind],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               start_new_session=True)
    with state['lock']:
        state['probe'] = process
    try:
        if state['stop'].is_set():
            return []
        output, errors = process.communicate(timeout=2)
        if process.returncode:
            raise OSError(errors.decode('utf-8', 'replace').strip() or 'Audio server unavailable.')
        value = json.loads(output)
        if not isinstance(value, list):
            raise ValueError('Invalid audio server response.')
        return value
    finally:
        stop_process(process)
        with state['lock']:
            if state['probe'] is process:
                state['probe'] = None
        process.stdout.close()
        process.stderr.close()


def _capture_audio(player, state, process_id):
    recorder = None
    try:
        deadline = time.monotonic() + 60
        stream = None
        while not state['stop'].is_set() and time.monotonic() < deadline:
            process = player['player']
            if process is None or process.pid != process_id or process.poll() is not None:
                return
            for item in _list_audio_objects(state, 'sink-inputs'):
                properties = item.get('properties', {})
                if str(properties.get('application.process.id')) == str(process_id):
                    stream = item
                    break
            if stream is not None:
                break
            state['stop'].wait(.25)
        if state['stop'].is_set():
            return
        if stream is None:
            raise OSError('Could not find mpv in the PipeWire/PulseAudio streams.')
        monitor = None
        for sink in _list_audio_objects(state, 'sinks'):
            if str(sink.get('index')) == str(stream.get('sink')):
                monitor = sink.get('monitor_source_name') or sink.get('monitor_source')
                if not isinstance(monitor, str):
                    monitor = sink['name'] + '.monitor'
                break
        if not monitor:
            raise OSError('The audio output has no monitor source.')
        recorder = subprocess.Popen(
            ['parec', '--raw', '--format=s16le', f"--rate={state['sample_rate']}",
             '--channels=1', '--latency-msec=20', '--process-time-msec=10',
             f'--monitor-stream={stream["index"]}', f'--device={monitor}',
             '--client-name=Music room FFT'],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        with state['lock']:
            state['process'] = recorder
        samples = bytearray()
        size = state['fft_size'] * 2
        while not state['stop'].is_set():
            if not select.select([recorder.stdout], [], [], .1)[0]:
                continue
            data = os.read(recorder.stdout.fileno(), 8192)
            if not data:
                raise OSError('Audio monitoring stopped. Select the song again to reconnect.')
            samples.extend(data)
            if len(samples) >= size:
                # Keep the newest complete window so old sound never queues up.
                end = len(samples) - len(samples) % 2
                window = bytes(samples[end - size:end])
                del samples[:end - size]
                with state['lock']:
                    state['pcm'] = window
                    state['sequence'] += 1
                    state['updated'] = time.monotonic()
    except FileNotFoundError:
        with state['lock']:
            state['error'] = 'Install libpulse (pactl and parec) for FFT bars.'
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        if not state['stop'].is_set():
            with state['lock']:
                state['error'] = str(error)
    finally:
        stop_process(recorder)
        if recorder is not None:
            recorder.stdout.close()
        with state['lock']:
            state['process'] = None


def start_spectrum(player, fft_size=1024, sample_rate=44100):
    if type(fft_size) is not int or fft_size not in (256, 512, 1024, 2048, 4096):
        raise ValueError('fft_size must be a power of two from 256 to 4096.')
    if type(sample_rate) is not int or sample_rate not in (11025, 22050, 44100, 48000):
        raise ValueError('Unsupported spectrum sample rate.')
    process = player['player']
    if process is None or process.poll() is not None:
        raise ValueError('Start playback before enabling the spectrum.')
    stop_spectrum(player)
    state = {
        'lock': threading.Lock(),
        'stop': threading.Event(),
        'process': None,
        'probe': None,
        'worker': None,
        'pcm': None,
        'sequence': 0,
        'updated': 0,
        'error': None,
        'fft_size': fft_size,
        'sample_rate': sample_rate,
    }
    player['spectrum'] = state
    capture = _capture_audio
    if os.name == 'nt':
        from .windows import capture_audio
        capture = capture_audio
    worker = threading.Thread(target=capture, args=(player, state, process.pid), daemon=True)
    worker.start()
    state['worker'] = worker
    return {'available': False, 'sample_rate': sample_rate, 'fft_size': fft_size}


def get_spectrum(player, sequence=None):
    state = player.get('spectrum')
    if state is None:
        return {'available': False, 'reason': 'Spectrum is disabled.'}
    with state['lock']:
        available = state['pcm'] is not None and time.monotonic() - state['updated'] < .2
        data = {'available': available, 'sample_rate': state['sample_rate'],
                'fft_size': state['fft_size'], 'sequence': state['sequence'],
                'reason': state['error']}
        if available and sequence != state['sequence']:
            data['pcm'] = base64.b64encode(state['pcm']).decode('ascii')
        return data


def stop_spectrum(player):
    state = player.get('spectrum')
    if state is None:
        return
    state['stop'].set()
    with state['lock']:
        recorder, probe = state['process'], state['probe']
    stop_process(recorder)
    stop_process(probe)
    if state['worker'] is not None:
        state['worker'].join()
    player['spectrum'] = None
