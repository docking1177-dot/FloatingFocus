"""Regenerate FF's original C4-style cues. No game recordings or third-party samples.

MIT licensed with the project. Durations match app/src/main/assets/c4_profile.json.
"""
from pathlib import Path
import math
import random
import struct
import wave

OUTPUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw'


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, seconds in [('c4_beep', .08), ('c4_final', .12), ('c4_explosion', .9)]:
        rate = 24000
        rng = random.Random(417)
        samples, low = [], 0.0
        for i in range(round(rate * seconds)):
            t = i / rate
            if name == 'c4_explosion':
                low = .88 * low + .12 * rng.uniform(-1, 1)
                value = (.5 * low + .18 * math.sin(2 * math.pi * (65*t - 18*t*t))) * math.exp(-5*t/seconds) * min(1, t/.004)
            else:
                freq = 1050 if name == 'c4_beep' else 1450
                envelope = min(1, t/.004, (seconds-t)/.012)
                gate = 1 if name == 'c4_beep' or t < .045 or t > .065 else .05
                value = .24 * math.sin(2*math.pi*freq*t) * envelope * gate
            samples.append(struct.pack('<h', int(max(-1, min(1, value))*32767)))
        with wave.open(str(OUTPUT / (name + '.wav')), 'wb') as file:
            file.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
            file.writeframes(b''.join(samples))


if __name__ == '__main__':
    main()
