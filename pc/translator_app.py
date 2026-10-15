# 한중일영 실시간 번역기 (윈도우용)
# 듣기: Vosk (무료, 인터넷 없이 동작)  /  번역: NLLB-200 600M (무료, 인터넷 없이 동작)
# 모델 파일은 처음 한 번만 내려받아 이 프로그램 옆 "models" 폴더에 저장합니다.

import json
import os
import queue
import sys
import threading
import urllib.request
import zipfile

# ---------------- 기본 설정 ----------------

LANGS = {
    # 화면 이름: (NLLB 코드, Vosk 모델 이름, 띄어쓰기 제거 여부)
    "한국어": ("kor_Hang", "vosk-model-small-ko-0.22", False),
    "중국어": ("zho_Hans", "vosk-model-small-cn-0.22", True),
    "일본어": ("jpn_Jpan", "vosk-model-small-ja-0.22", True),
    "영어": ("eng_Latn", "vosk-model-small-en-us-0.15", False),
}
LANG_NAMES = list(LANGS.keys())

VOSK_URL = "https://alphacephei.com/vosk/models/{}.zip"
NLLB_REPO = "https://huggingface.co/JustFrederik/nllb-200-distilled-600M-ct2-int8/resolve/main/"
NLLB_FILES = ["config.json", "shared_vocabulary.txt", "sentencepiece.bpe.model", "model.bin"]
SAMPLE_RATE = 16000


def base_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


MODELS_DIR = os.path.join(base_dir(), "models")


# ---------------- 내려받기 ----------------

def download(url, target, label, report):
    tmp = target + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "translator-app"})
    with urllib.request.urlopen(req, timeout=60) as resp, open(tmp, "wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        got = 0
        last = -1
        while True:
            chunk = resp.read(1024 * 256)
            if not chunk:
                break
            out.write(chunk)
            got += len(chunk)
            mb = got // (1024 * 1024)
            if mb != last:
                last = mb
                tot = f" / {total // (1024 * 1024)}MB" if total else "MB"
                report(f"{label} 내려받는 중… {mb}{tot} (처음 한 번만)")
    os.replace(tmp, target)


def ensure_vosk_model(name, report):
    folder = os.path.join(MODELS_DIR, "vosk")
    model_dir = os.path.join(folder, name)
    mark = os.path.join(model_dir, ".ok")
    if os.path.exists(mark):
        return model_dir
    os.makedirs(folder, exist_ok=True)
    zip_path = os.path.join(folder, name + ".zip")
    download(VOSK_URL.format(name), zip_path, "음성 모델", report)
    report("음성 모델 압축 푸는 중…")
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(folder)
    os.remove(zip_path)
    if not os.path.isdir(model_dir):
        raise RuntimeError("음성 모델 폴더가 없습니다: " + name)
    with open(mark, "w") as f:
        f.write("ok")
    return model_dir


def ensure_nllb_model(report):
    folder = os.path.join(MODELS_DIR, "nllb")
    mark = os.path.join(folder, ".ok")
    if os.path.exists(mark):
        return folder
    os.makedirs(folder, exist_ok=True)
    for name in NLLB_FILES:
        target = os.path.join(folder, name)
        if not os.path.exists(target):
            download(NLLB_REPO + name, target, f"번역 모델({name})", report)
    with open(mark, "w") as f:
        f.write("ok")
    return folder


# ---------------- 번역 (NLLB) ----------------

class NllbTranslator:
    def __init__(self, folder):
        import ctranslate2
        import sentencepiece as spm
        threads = max(1, (os.cpu_count() or 2) // 2)
        self.tr = ctranslate2.Translator(folder, device="cpu", compute_type="int8", intra_threads=threads)
        self.sp = spm.SentencePieceProcessor(model_file=os.path.join(folder, "sentencepiece.bpe.model"))

    def translate(self, text, src_code, tgt_code):
        tokens = self.sp.encode(text, out_type=str)
        source = [src_code] + tokens + ["</s>"]
        result = self.tr.translate_batch(
            [source], target_prefix=[[tgt_code]], beam_size=2, max_decoding_length=256
        )
        out = result[0].hypotheses[0]
        if out and out[0] == tgt_code:
            out = out[1:]
        return self.sp.decode(out).strip()


# ---------------- 듣기 (Vosk) ----------------

def load_vosk(model_dir):
    import vosk
    vosk.SetLogLevel(-1)
    return vosk.Model(model_dir)


def clean_text(text, lang_name):
    text = text.strip()
    if LANGS[lang_name][2]:
        text = text.replace(" ", "")
    return text


class Engine:
    """모델 준비, 마이크 듣기, 번역을 맡는 부분 (화면과 분리)."""

    def __init__(self, ui_queue):
        self.ui = ui_queue
        self.translator = None
        self.vosk_models = {}
        self.stop_event = threading.Event()
        self.text_queue = queue.Queue()
        self.thread = None

    def report(self, msg):
        self.ui.put(("status", msg))

    def start(self, src, tgt, lecture_mode):
        self.stop_event.clear()
        self.thread = threading.Thread(target=self._run, args=(src, tgt, lecture_mode), daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()

    def _run(self, src, tgt, lecture_mode):
        try:
            nllb_dir = ensure_nllb_model(self.report)
            if self.translator is None:
                self.report("번역 모델 불러오는 중…")
                self.translator = NllbTranslator(nllb_dir)
            vname = LANGS[src][1]
            vdir = ensure_vosk_model(vname, self.report)
            if vname not in self.vosk_models:
                self.report("음성 모델 불러오는 중…")
                self.vosk_models[vname] = load_vosk(vdir)
            model = self.vosk_models[vname]

            worker = threading.Thread(target=self._translate_worker, args=(src, tgt), daemon=True)
            worker.start()
            self._listen(model, src, lecture_mode)
        except Exception as e:  # 오류는 화면에 그대로 보여 줌
            self.report(f"오류: {e}")
        finally:
            self.text_queue.put(None)
            self.ui.put(("stopped", None))

    def _listen(self, model, src, lecture_mode):
        import sounddevice as sd
        import vosk

        audio = queue.Queue()

        def callback(indata, frames, time_info, status):
            audio.put(bytes(indata))

        rec = vosk.KaldiRecognizer(model, SAMPLE_RATE)
        with sd.RawInputStream(samplerate=SAMPLE_RATE, blocksize=8000, dtype="int16",
                               channels=1, callback=callback):
            if lecture_mode:
                self.report("듣는 중… 정지를 누를 때까지 계속 번역합니다.")
            else:
                self.report("말씀하세요… (한 문장)")
            while not self.stop_event.is_set():
                try:
                    data = audio.get(timeout=0.3)
                except queue.Empty:
                    continue
                if rec.AcceptWaveform(data):
                    text = clean_text(json.loads(rec.Result()).get("text", ""), src)
                    self.ui.put(("partial", ""))
                    if text:
                        self.text_queue.put(text)
                        if not lecture_mode:
                            self.report("완료. 다음 문장은 시작을 다시 누르세요.")
                            return
                else:
                    partial = clean_text(json.loads(rec.PartialResult()).get("partial", ""), src)
                    self.ui.put(("partial", partial))
            text = clean_text(json.loads(rec.FinalResult()).get("text", ""), src)
            if text:
                self.text_queue.put(text)
        self.report("정지했습니다.")

    def _translate_worker(self, src, tgt):
        s, t = LANGS[src][0], LANGS[tgt][0]
        while True:
            text = self.text_queue.get()
            if text is None:
                return
            try:
                out = self.translator.translate(text, s, t)
            except Exception as e:
                out = f"(번역 실패: {e})"
            self.ui.put(("line", (text, out)))


# ---------------- 화면 (tkinter) ----------------

def run_gui():
    import tkinter as tk
    from tkinter import ttk, scrolledtext

    ui_queue = queue.Queue()
    engine = Engine(ui_queue)

    root = tk.Tk()
    root.title("번역기 (한·중·일·영)")
    root.geometry("760x620")
    font = ("Malgun Gothic", 12)
    big = ("Malgun Gothic", 15)

    top = tk.Frame(root, padx=12, pady=10)
    top.pack(fill="x")
    tk.Label(top, text="말하는 언어", font=font).grid(row=0, column=0, sticky="w")
    src_var = tk.StringVar(value="영어")
    src_box = ttk.Combobox(top, textvariable=src_var, values=LANG_NAMES, state="readonly", width=8, font=font)
    src_box.grid(row=0, column=1, padx=6)
    tk.Label(top, text="→  번역할 언어", font=font).grid(row=0, column=2, sticky="w")
    tgt_var = tk.StringVar(value="한국어")
    tgt_box = ttk.Combobox(top, textvariable=tgt_var, values=LANG_NAMES, state="readonly", width=8, font=font)
    tgt_box.grid(row=0, column=3, padx=6)

    mode_var = tk.StringVar(value="lecture")
    mode_frame = tk.Frame(root, padx=12)
    mode_frame.pack(fill="x")
    rb1 = tk.Radiobutton(mode_frame, text="대화 모드 (한 문장)", variable=mode_var, value="talk", font=font)
    rb2 = tk.Radiobutton(mode_frame, text="강의 모드 (계속 듣기)", variable=mode_var, value="lecture", font=font)
    rb1.pack(side="left")
    rb2.pack(side="left", padx=12)

    state = {"running": False}
    start_btn = tk.Button(root, text="시작", font=("Malgun Gothic", 16, "bold"), height=2)
    start_btn.pack(fill="x", padx=12, pady=8)

    status = tk.Label(root, text="언어와 모드를 고르고 시작을 누르세요.", font=font, fg="#444", anchor="w")
    status.pack(fill="x", padx=12)
    partial = tk.Label(root, text="", font=font, fg="#888", anchor="w", wraplength=720, justify="left")
    partial.pack(fill="x", padx=12, pady=4)

    log = scrolledtext.ScrolledText(root, font=big, wrap="word")
    log.pack(fill="both", expand=True, padx=12, pady=(0, 6))
    log.tag_configure("tr", foreground="#0046a0", font=("Malgun Gothic", 15, "bold"))

    bottom = tk.Frame(root, padx=12, pady=6)
    bottom.pack(fill="x")
    tk.Button(bottom, text="기록 지우기", font=font, command=lambda: log.delete("1.0", "end")).pack(side="left")
    tk.Label(bottom, text="모델 저장 위치: " + MODELS_DIR, font=("Malgun Gothic", 9), fg="#888").pack(side="right")

    def set_controls(enabled):
        st = "readonly" if enabled else "disabled"
        src_box.configure(state=st)
        tgt_box.configure(state=st)
        for rb in (rb1, rb2):
            rb.configure(state="normal" if enabled else "disabled")

    def on_start():
        if state["running"]:
            engine.stop()
            start_btn.configure(text="정지하는 중…", state="disabled")
            return
        if src_var.get() == tgt_var.get():
            status.configure(text="말하는 언어와 번역할 언어가 같습니다. 다르게 골라 주세요.")
            return
        state["running"] = True
        start_btn.configure(text="정지")
        set_controls(False)
        engine.start(src_var.get(), tgt_var.get(), mode_var.get() == "lecture")

    start_btn.configure(command=on_start)

    def poll():
        try:
            while True:
                kind, value = ui_queue.get_nowait()
                if kind == "status":
                    status.configure(text=value)
                elif kind == "partial":
                    partial.configure(text=value)
                elif kind == "line":
                    original, translated = value
                    log.insert("end", original + "\n")
                    log.insert("end", "→ " + translated + "\n\n", "tr")
                    log.see("end")
                elif kind == "stopped":
                    state["running"] = False
                    start_btn.configure(text="시작", state="normal")
                    partial.configure(text="")
                    set_controls(True)
        except queue.Empty:
            pass
        root.after(100, poll)

    def on_close():
        engine.stop()
        root.after(300, root.destroy)

    root.protocol("WM_DELETE_WINDOW", on_close)
    poll()
    root.mainloop()


if __name__ == "__main__":
    run_gui()
