"""
完全に手続き的に合成する試聴用BGM生成スクリプト。
実在の楽曲データ・音源サンプルは一切使用せず、正弦波/ノコギリ波/ノイズと
簡単な物理モデル(Karplus-Strong)だけで音を作る。著作権的にクリーンな
「動作確認用の素材」として、10ジャンル x 3曲 = 30曲を出力する。

使い方:
    python3 -m venv venv && source venv/bin/activate
    pip install numpy
    python3 tools/generate_bundled_music.py
    # ./wav_out/*.wav が生成される。app/src/main/res/raw/ に入れる前に
    # ラウドネスを揃えつつAAC(.m4a)へ変換する:
    for f in wav_out/*.wav; do
        name=$(basename "$f" .wav)
        ffmpeg -y -i "$f" -af "loudnorm=I=-14:TP=-1.5:LRA=11" \
            -c:a aac -b:a 128k -ar 44100 \
            "app/src/main/res/raw/music_${name}.m4a"
    done
    # ジャンルや曲調を変えたい場合はGENRE_SPECS/PROGRESSIONSを編集する。
    # 新しいジャンルを追加した場合はmusic/BundledTracks.ktのgenre(...)呼び出しも追記すること。
"""
import math
import os
import random
import wave
import struct

import numpy as np

SR = 44100
OUT_DIR = os.path.join(os.path.dirname(__file__), "wav_out")
os.makedirs(OUT_DIR, exist_ok=True)


def note_freq(semitone_from_a4: float) -> float:
    return 440.0 * (2.0 ** (semitone_from_a4 / 12.0))


# ---- 基本オシレータ ----

def sine(freq, dur, sr=SR):
    t = np.arange(int(dur * sr)) / sr
    return np.sin(2 * np.pi * freq * t)


def saw(freq, dur, sr=SR, harmonics=12):
    t = np.arange(int(dur * sr)) / sr
    out = np.zeros_like(t)
    for k in range(1, harmonics + 1):
        out += (1.0 / k) * np.sin(2 * np.pi * freq * k * t)
    return out * (2 / np.pi)


def square(freq, dur, sr=SR, harmonics=10):
    t = np.arange(int(dur * sr)) / sr
    out = np.zeros_like(t)
    for k in range(1, harmonics * 2, 2):
        out += (1.0 / k) * np.sin(2 * np.pi * freq * k * t)
    return out * (4 / np.pi)


def noise(dur, sr=SR, rng=None):
    rng = rng or np.random
    return rng.uniform(-1, 1, int(dur * sr))


def lowpass(x, alpha):
    """簡易1極ローパス。alphaが小さいほど強くかかる。"""
    y = np.zeros_like(x)
    prev = 0.0
    for i in range(len(x)):
        prev = prev + alpha * (x[i] - prev)
        y[i] = prev
    return y


def highpass(x, alpha):
    return x - lowpass(x, alpha)


def adsr(n, sr=SR, a=0.01, d=0.1, s=0.6, r=0.2, sustain_level=None):
    sustain_level = s if sustain_level is None else sustain_level
    a_n, d_n, r_n = int(a * sr), int(d * sr), int(r * sr)
    s_n = max(n - a_n - d_n - r_n, 0)
    env = np.concatenate([
        np.linspace(0, 1, max(a_n, 1)),
        np.linspace(1, sustain_level, max(d_n, 1)),
        np.full(s_n, sustain_level),
        np.linspace(sustain_level, 0, max(r_n, 1)),
    ])
    if len(env) < n:
        env = np.pad(env, (0, n - len(env)))
    return env[:n]


def karplus_strong(freq, dur, sr=SR, decay=0.996, rng=None):
    rng = rng or np.random
    n = int(dur * sr)
    period = max(int(sr / freq), 2)
    buf = rng.uniform(-1, 1, period)
    out = np.zeros(n)
    for i in range(n):
        out[i] = buf[i % period]
        avg = decay * 0.5 * (buf[i % period] + buf[(i + 1) % period])
        buf[i % period] = avg
    return out


# ---- ドラム類 ----

def kick(sr=SR, dur=0.28):
    n = int(dur * sr)
    t = np.arange(n) / sr
    freq = 150 * np.exp(-t * 18) + 40
    phase = 2 * np.pi * np.cumsum(freq) / sr
    body = np.sin(phase)
    env = np.exp(-t * 14)
    click = noise(0.004, sr) * np.exp(-np.arange(int(0.004 * sr)) / (0.004 * sr) * 6)
    out = body * env
    out[: len(click)] += click * 0.5
    return out * 0.9


def snare(sr=SR, dur=0.18):
    n = int(dur * sr)
    t = np.arange(n) / sr
    body = np.sin(2 * np.pi * 190 * t) * np.exp(-t * 30)
    noise_part = highpass(noise(dur, sr), 0.35) * np.exp(-t * 18)
    return body * 0.5 + noise_part * 0.9


def hihat(sr=SR, dur=0.06, open_hat=False):
    n = int(dur * sr)
    t = np.arange(n) / sr
    decay = 6 if open_hat else 28
    return highpass(noise(dur, sr), 0.5) * np.exp(-t * decay) * 0.5


def shaker(sr=SR, dur=0.09):
    n = int(dur * sr)
    t = np.arange(n) / sr
    return highpass(noise(dur, sr), 0.4) * np.exp(-t * 20) * 0.35


def clave(sr=SR, dur=0.08):
    t = np.arange(int(dur * sr)) / sr
    return np.sin(2 * np.pi * 2500 * t) * np.exp(-t * 60) * 0.5


# ---- ミキシング util ----

def mix_at(track, sound, start_sample):
    end = start_sample + len(sound)
    if end > len(track):
        sound = sound[: len(track) - start_sample]
        end = len(track)
    if start_sample < len(track):
        track[start_sample:end] += sound
    return track


def soft_clip(x, drive=1.0):
    return np.tanh(x * drive) / np.tanh(drive)


def normalize(x, peak=0.9):
    m = np.max(np.abs(x)) or 1.0
    return x / m * peak


def to_stereo(mono, width=0.0, rng=None):
    """widthだけ左右をわずかにずらして広がりを出す(0なら完全モノ)。"""
    if width <= 0:
        return np.stack([mono, mono], axis=1)
    shift = int(width * SR / 1000)
    left = mono
    right = np.concatenate([np.zeros(shift), mono])[: len(mono)]
    return np.stack([left, right], axis=1)


def write_wav(path, stereo, sr=SR):
    stereo = np.clip(stereo, -1.0, 1.0)
    ints = (stereo * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(ints.tobytes())


# ---- 音楽理論的な材料 ----

MAJOR_TRIAD = [0, 4, 7]
MINOR_TRIAD = [0, 3, 7]
DOM7 = [0, 4, 7, 10]
MIN7 = [0, 3, 7, 10]
MAJ7 = [0, 4, 7, 11]

# key root (Aからの半音差), 各進行は (rootオフセット, コード種) のリスト
PROGRESSIONS = {
    "pop": [
        (0, MAJOR_TRIAD), (7, MAJOR_TRIAD), (-3, MINOR_TRIAD), (5, MAJOR_TRIAD),
    ],
    "pop2": [
        (0, MAJOR_TRIAD), (-5, MAJOR_TRIAD), (-3, MINOR_TRIAD), (5, MAJOR_TRIAD),
    ],
    "rock": [
        (0, MINOR_TRIAD), (5, MAJOR_TRIAD), (3, MAJOR_TRIAD), (-2, MAJOR_TRIAD),
    ],
    "jazz": [
        (2, MIN7), (7, DOM7), (0, MAJ7), (0, MAJ7),
    ],
    "jazz2": [
        (-2, MIN7), (3, DOM7), (-4, MAJ7), (2, MIN7),
    ],
    "classical": [
        (0, MAJOR_TRIAD), (5, MAJOR_TRIAD), (7, MAJOR_TRIAD), (0, MAJOR_TRIAD),
    ],
    "edm": [
        (0, MINOR_TRIAD), (-3, MAJOR_TRIAD), (-5, MAJOR_TRIAD), (-8, MAJOR_TRIAD),
    ],
    "lofi": [
        (0, MIN7), (-5, MAJ7), (-8, MAJ7), (-3, DOM7),
    ],
    "ambient": [
        (0, MAJOR_TRIAD), (-4, MINOR_TRIAD), (-9, MAJOR_TRIAD), (-5, MAJOR_TRIAD),
    ],
    "hiphop": [
        (0, MIN7), (-3, MAJ7), (-8, MAJ7), (-5, DOM7),
    ],
    "folk": [
        (0, MAJOR_TRIAD), (-5, MAJOR_TRIAD), (-9, MINOR_TRIAD), (7, MAJOR_TRIAD),
    ],
    "bossa": [
        (0, MAJ7), (-9, MIN7), (-2, MIN7), (7, DOM7),
    ],
}


def chord_freqs(root_offset, chord_type, base_semitone=-9):
    return [note_freq(base_semitone + root_offset + iv) for iv in chord_type]


# ---- ジャンルごとのレンダラ ----

def render_chords_voice(progression, bar_dur, bars_total, voice="pluck", base_semitone=-9, rng=None):
    n_total = int(bar_dur * bars_total * SR)
    track = np.zeros(n_total)
    for bar in range(bars_total):
        root_offset, chord_type = progression[bar % len(progression)]
        freqs = chord_freqs(root_offset, chord_type, base_semitone)
        start = int(bar * bar_dur * SR)
        for f in freqs:
            if voice == "pluck":
                tone = karplus_strong(f, bar_dur * 0.95, decay=0.994, rng=rng)
                env = adsr(len(tone), a=0.005, d=0.3, s=0.4, r=0.3)
                tone = tone * env * 0.35
            elif voice == "pad":
                tone = (sine(f, bar_dur, harmonics=1) if False else sine(f, bar_dur))
                tone += 0.5 * sine(f * 1.005, bar_dur)
                env = adsr(len(tone), a=bar_dur * 0.3, d=0.1, s=0.8, r=bar_dur * 0.3)
                tone = tone * env * 0.18
            elif voice == "rhodes":
                tone = sine(f, bar_dur) + 0.3 * sine(f * 2.01, bar_dur)
                env = adsr(len(tone), a=0.01, d=0.5, s=0.3, r=0.4)
                tone = tone * env * 0.25
            elif voice == "power":
                tone = square(f, bar_dur, harmonics=4)
                env = adsr(len(tone), a=0.005, d=0.15, s=0.7, r=0.1)
                tone = soft_clip(tone * env, drive=2.2) * 0.22
            else:
                tone = sine(f, bar_dur)
            track = mix_at(track, tone, start)
    return track


def render_bass(progression, bar_dur, bars_total, style="sine", base_semitone=-21, rng=None):
    n_total = int(bar_dur * bars_total * SR)
    track = np.zeros(n_total)
    for bar in range(bars_total):
        root_offset, _ = progression[bar % len(progression)]
        f = note_freq(base_semitone + root_offset)
        start = int(bar * bar_dur * SR)
        note_dur = bar_dur * 0.9
        if style == "pluck":
            tone = karplus_strong(f, note_dur, decay=0.995, rng=rng)
            env = adsr(len(tone), a=0.005, d=0.2, s=0.5, r=0.2)
        else:
            tone = sine(f, note_dur) + 0.3 * sine(f * 2, note_dur)
            env = adsr(len(tone), a=0.01, d=0.15, s=0.6, r=0.15)
        track = mix_at(track, tone * env * 0.5, start)
    return track


def render_drum_pattern(pattern_fn, beat_dur, beats_total):
    n_total = int(beat_dur * beats_total * SR) + SR
    track = np.zeros(n_total)
    pattern_fn(track, beat_dur)
    return track


def genre_four_on_floor(track, beat_dur):
    beats = int(len(track) / SR / beat_dur)
    for b in range(beats):
        start = int(b * beat_dur * SR)
        track = mix_at(track, kick() * 0.8, start)
        if b % 2 == 1:
            track = mix_at(track, hihat(open_hat=True) * 0.5, start + int(beat_dur * SR * 0.5))
        else:
            track = mix_at(track, hihat() * 0.6, start + int(beat_dur * SR * 0.5))


def genre_rock_beat(track, beat_dur):
    beats = int(len(track) / SR / beat_dur)
    for b in range(beats):
        start = int(b * beat_dur * SR)
        if b % 2 == 0:
            track = mix_at(track, kick(), start)
        else:
            track = mix_at(track, snare(), start)
        track = mix_at(track, hihat(), start + int(beat_dur * SR * 0.5))


def genre_swing_beat(track, beat_dur):
    beats = int(len(track) / SR / beat_dur)
    for b in range(beats):
        start = int(b * beat_dur * SR)
        track = mix_at(track, hihat() * 0.4, start)
        swing_off = int(beat_dur * SR * 0.66)
        track = mix_at(track, hihat() * 0.3, start + swing_off)
        if b % 4 == 2:
            track = mix_at(track, snare() * 0.5, start)


def genre_boom_bap(track, beat_dur):
    beats = int(len(track) / SR / beat_dur)
    for b in range(beats):
        start = int(b * beat_dur * SR)
        if b % 4 in (0,):
            track = mix_at(track, kick(), start)
        if b % 4 == 2:
            track = mix_at(track, snare() * 0.9, start)
        if b % 2 == 1:
            track = mix_at(track, kick() * 0.5, start + int(beat_dur * SR * 0.5))
        track = mix_at(track, hihat() * 0.35, start)


def genre_bossa_beat(track, beat_dur):
    beats = int(len(track) / SR / beat_dur)
    pattern = [1, 0, 1, 0, 1, 0, 0, 1]  # クラーベ風
    for b in range(beats):
        start = int(b * beat_dur * SR)
        if pattern[b % len(pattern)]:
            track = mix_at(track, clave(), start)
        track = mix_at(track, shaker() * 0.4, start)


def genre_lofi_beat(track, beat_dur):
    beats = int(len(track) / SR / beat_dur)
    for b in range(beats):
        start = int(b * beat_dur * SR)
        if b % 4 in (0, 2):
            track = mix_at(track, kick() * 0.6, start)
        if b % 4 == 2:
            track = mix_at(track, snare() * 0.5, start)
        track = mix_at(track, hihat() * 0.25, start + int(beat_dur * SR * 0.5))


GENRE_SPECS = {
    # key: (progression keys候補, voice, bass style, drum fn or None, bpm候補, base_semitone候補)
    "pop": (["pop", "pop2"], "pluck", "sine", genre_four_on_floor, [112, 118, 124], -9),
    "rock": (["rock"], "power", "pluck", genre_rock_beat, [130, 140, 120], -9),
    "jazz": (["jazz", "jazz2"], "rhodes", "pluck", genre_swing_beat, [96, 104, 90], -9),
    "classical": (["classical"], "pad", "sine", None, [70, 76, 64], -9),
    "edm": (["edm"], "power", "sine", genre_four_on_floor, [126, 128, 130], -9),
    "lofi": (["lofi"], "rhodes", "sine", genre_lofi_beat, [78, 82, 74], -9),
    "ambient": (["ambient"], "pad", "sine", None, [60, 55, 65], -9),
    "hiphop": (["hiphop"], "rhodes", "sine", genre_boom_bap, [86, 90, 82], -9),
    "folk": (["folk"], "pluck", "pluck", None, [100, 96, 108], -9),
    "bossa": (["bossa"], "pluck", "pluck", genre_bossa_beat, [118, 112, 122], -9),
}

GENRE_LABELS = {
    "pop": "ポップ風",
    "rock": "ロック風",
    "jazz": "ジャズ風",
    "classical": "クラシック風",
    "edm": "EDM風",
    "lofi": "Lo-fi風",
    "ambient": "アンビエント風",
    "hiphop": "ヒップホップ風",
    "folk": "アコースティック風",
    "bossa": "ボサノバ風",
}

TOTAL_DUR_SEC = 28


def render_track(genre, variant_index, seed):
    rng = np.random.RandomState(seed)
    prog_keys, voice, bass_style, drum_fn, bpm_options, base_semitone = GENRE_SPECS[genre]
    prog_key = prog_keys[variant_index % len(prog_keys)]
    progression = PROGRESSIONS[prog_key]
    bpm = bpm_options[variant_index % len(bpm_options)]
    beat_dur = 60.0 / bpm
    bar_dur = beat_dur * 4
    bars_total = max(int(round(TOTAL_DUR_SEC / bar_dur)), 4)
    total_dur = bars_total * bar_dur

    chords = render_chords_voice(progression, bar_dur, bars_total, voice=voice, base_semitone=base_semitone, rng=rng)
    bass = render_bass(progression, bar_dur, bars_total, style=bass_style, base_semitone=base_semitone - 12, rng=rng)

    n_total = max(len(chords), len(bass))
    mix = np.zeros(n_total)
    mix = mix_at(mix, chords, 0)
    mix = mix_at(mix, bass, 0)

    if drum_fn is not None:
        beats_total = int(total_dur / beat_dur) + 4
        drums = render_drum_pattern(drum_fn, beat_dur, beats_total)
        mix_len = max(len(mix), len(drums))
        mix2 = np.zeros(mix_len)
        mix2 = mix_at(mix2, mix, 0)
        mix2 = mix_at(mix2, drums, 0)
        mix = mix2

    # フェードアウトで自然に終わらせる
    fade_len = int(1.2 * SR)
    if len(mix) > fade_len:
        mix[-fade_len:] *= np.linspace(1, 0, fade_len)

    mix = soft_clip(mix, drive=1.4)
    mix = normalize(mix, peak=0.85)
    stereo = to_stereo(mix, width=6 if voice in ("pad", "rhodes") else 0)
    return stereo


def main():
    random.seed(42)
    manifest = []
    for genre in GENRE_SPECS:
        for variant in range(3):
            seed = hash((genre, variant)) % (2 ** 31)
            stereo = render_track(genre, variant, seed)
            filename = f"{genre}_{variant + 1}.wav"
            path = os.path.join(OUT_DIR, filename)
            write_wav(path, stereo)
            manifest.append((genre, GENRE_LABELS[genre], variant + 1, filename))
            print("generated", filename, f"{len(stereo) / SR:.1f}s")
    with open(os.path.join(OUT_DIR, "manifest.tsv"), "w") as f:
        for row in manifest:
            f.write("\t".join(str(x) for x in row) + "\n")


if __name__ == "__main__":
    main()
