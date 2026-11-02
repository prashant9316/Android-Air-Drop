Test clips: 16 kHz mono signed 16-bit little-endian PCM, synthesised with the Piper
voice `en_GB-alba-medium` (CC BY 4.0, https://huggingface.co/rhasspy/piper-voices).

`hey_jarvis.ref_scores.txt` holds the per-80 ms scores from the reference Python
`openwakeword` package (0.4.0) for `hey_jarvis.pcm`; the Kotlin port must match them.
