"""Tiny EN->SV translation sidecar (Helsinki-NLP/opus-mt-en-sv).

The recipe pipeline uses the household LLM for structure and this for the words —
the spike showed opus-mt is markedly better at Swedish than the general model,
while staying small (~300 MB) and fast (sub-second per batch on CPU).

Stdlib HTTP only (no web framework) to keep the image lean. One endpoint:
  POST /translate  {"texts": ["..."]}  -> {"translations": ["..."]}
  GET  /health                          -> {"ok": true}
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from transformers import MarianMTModel, MarianTokenizer

MODEL_NAME = "Helsinki-NLP/opus-mt-en-sv"
print(f"[translate] loading {MODEL_NAME}…", flush=True)
_tok = MarianTokenizer.from_pretrained(MODEL_NAME)
_model = MarianMTModel.from_pretrained(MODEL_NAME)
print("[translate] ready", flush=True)


def translate(texts):
    if not texts:
        return []
    batch = _tok(texts, return_tensors="pt", padding=True, truncation=True)
    out = _model.generate(**batch, max_length=512)
    return [_tok.decode(o, skip_special_tokens=True) for o in out]


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            return self._send(200, {"ok": True})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/translate":
            return self._send(404, {"error": "not found"})
        try:
            length = int(self.headers.get("Content-Length", 0))
            data = json.loads(self.rfile.read(length) or b"{}")
            texts = data.get("texts", [])
            if not isinstance(texts, list):
                return self._send(400, {"error": "texts must be a list"})
            self._send(200, {"translations": translate([str(t) for t in texts])})
        except Exception as exc:  # noqa: BLE001 — degrade, never crash the worker
            self._send(500, {"error": str(exc)})

    def log_message(self, *args):  # silence per-request logging
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 7000), Handler).serve_forever()
