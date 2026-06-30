# EN->SV translation sidecar (opus-mt). CPU-only torch to keep the image as small
# as a torch image gets. The model is baked in at build time so the container
# needs no network at startup (matches the "works at home, no cloud" promise).
FROM python:3.11-slim

WORKDIR /app

# CPU torch from the dedicated index, then the (small) transformers stack.
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
 && pip install --no-cache-dir transformers sentencepiece sacremoses

# Pre-download the model into the image layer.
RUN python -c "from transformers import MarianMTModel, MarianTokenizer; \
    MarianTokenizer.from_pretrained('Helsinki-NLP/opus-mt-en-sv'); \
    MarianMTModel.from_pretrained('Helsinki-NLP/opus-mt-en-sv')"

COPY app.py .

EXPOSE 7000
CMD ["python", "app.py"]
