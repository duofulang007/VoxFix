# Synthetic pause regression fixtures

These four 16 kHz mono little-endian int16 PCM files are generated noise, not microphone recordings.
They reproduce unspoken `Yeah` insertions on the 2026-10-26 build
`d645dcf5f23e4c8cff933af9fef4a74ca2e21ed090af2c824f33f3b0d1659783`.

Generation: NumPy `default_rng(261026)`, for each duration 0.4, 0.65, 1.0, 2.0 seconds
draw standard normal samples, take real FFT, divide by `sqrt(max(frequency,40))` for pink
or `max(frequency,80)` for brown, inverse FFT, normalize by absolute peak, multiply by
0.001 and 32767, cast to little-endian int16. Only the two shorter durations are bundled.

| File | SHA-256 |
| --- | --- |
| pause-pink-6400-1.pcm | 126ecfdcc11f0c58fc8ade59d3e3353ba09b80414c90b52ff70a92f962ba4e52 |
| pause-pink-10400-1.pcm | 78785597fc56919f7cf6b7142cdecdbc4ae62ff6b4e7b7295daec1a6360d821d |
| pause-brown-6400-1.pcm | d63deacca8f312c5a59622e1374c2d225677765ca979f37296291985a75b7be2 |
| pause-brown-10400-1.pcm | 87b77de4d2d7472b858c7b349fdca9b71130da1414e020864de3d36304884466 |

`modelCompare=desktop-pause` must return no text for all four clips. Legitimately spoken
English `yeah` remains covered by the existing ASCEND short/baseline replay; no word blacklist is used.

`pause-hum-32000-1.pcm` is the existing two-second synthetic modulated hum from
`.android/voice-yeah-20261026/` (SHA-256
`5b5f7606b20d3876853c661fa66c2dd72aca9f627283f2351f9e71e3dfcbf011`).
It produced `嗯。` after the earlier pause fix. `modelCompare=desktop-hum` checks
this exact quantized fixture plus 81 generated harmonic hums at 40–240 Hz,
0.4/3/12 seconds and three levels, requiring no text callbacks or final result.
