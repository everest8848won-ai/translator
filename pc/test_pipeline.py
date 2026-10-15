# 자동 점검: 모델 내려받기 → 음성 인식 → 번역이 실제로 되는지 확인 (GitHub 서버에서 실행)
import json
import subprocess
import sys
import wave

import translator_app as app


def has(text, lo, hi):
    return any(lo <= ord(c) <= hi for c in text)


def check_script(lang, text):
    if lang == "한국어":
        return has(text, 0xAC00, 0xD7A3)
    if lang == "일본어":
        return has(text, 0x3040, 0x30FF) or has(text, 0x4E00, 0x9FFF)
    if lang == "중국어":
        return has(text, 0x4E00, 0x9FFF)
    return has(text, ord("a"), ord("z"))


RESULTS = []


def log(line):
    print(line, flush=True)
    RESULTS.append(line)


def main():
    report = lambda m: print(m, flush=True)
    failed = False

    # 1) 번역 점검
    tr = app.NllbTranslator(app.ensure_nllb_model(report))
    samples = {
        "영어": "Hello, today we will learn how this machine works.",
        "한국어": "안녕하세요, 오늘은 이 기계가 어떻게 움직이는지 배우겠습니다.",
        "일본어": "こんにちは、今日はこの機械の仕組みを学びます。",
        "중국어": "你好，今天我们来学习这台机器是怎么工作的。",
    }
    for src, text in samples.items():
        for tgt in app.LANG_NAMES:
            if src == tgt:
                continue
            out = tr.translate(text, app.LANGS[src][0], app.LANGS[tgt][0])
            ok = check_script(tgt, out) and len(out) > 3
            log(f"[{'OK' if ok else 'FAIL'}] {src}->{tgt}: {out}")
            failed |= not ok

    # 2) 음성 인식 점검 (espeak-ng로 만든 영어 음성)
    subprocess.run(["espeak-ng", "-v", "en-us", "-s", "140", "-w", "/tmp/raw.wav",
                    "hello everyone today we will learn about the weather"], check=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", "/tmp/raw.wav", "-ar", "16000",
                    "-ac", "1", "-sample_fmt", "s16", "/tmp/test.wav"], check=True)
    import vosk
    for lang in app.LANG_NAMES:
        model = app.load_vosk(app.ensure_vosk_model(app.LANGS[lang][1], report))
        log(f"[OK] {lang} 음성 모델 불러오기")
        if lang == "영어":
            rec = vosk.KaldiRecognizer(model, 16000)
            with wave.open("/tmp/test.wav") as w:
                while True:
                    data = w.readframes(4000)
                    if not data:
                        break
                    rec.AcceptWaveform(data)
            heard = json.loads(rec.FinalResult()).get("text", "")
            ok = "weather" in heard or "today" in heard
            log(f"[{'OK' if ok else 'FAIL'}] 영어 음성 인식: {heard}")
            failed |= not ok
            if heard:
                log("    → 한국어: " + tr.translate(heard, "eng_Latn", "kor_Hang"))

    # 결과를 GitHub 화면 알림으로도 남김
    print("::notice title=test-results::" + "%0A".join(RESULTS), flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
