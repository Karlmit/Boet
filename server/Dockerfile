# Playwright's official image (Node + npm + prebuilt Chromium/Firefox/WebKit +
# all OS deps) instead of node:20-alpine — needed for the URL-scrape feature's
# headless-render fallback (scrape.js). This is a meaningfully heavier base
# (~1.5-2GB vs alpine's ~150MB); see docker-compose.prod.yml for the tradeoff
# note. The `playwright` npm dependency version MUST match this image's tag —
# the image ships prebuilt browser binaries for one exact Playwright version.
FROM mcr.microsoft.com/playwright:v1.61.1-jammy

WORKDIR /app

# Install deps first for better layer caching.
COPY package.json package-lock.json* ./
RUN npm install --omit=dev

COPY src ./src

ENV NODE_ENV=production
ENV PORT=3020
EXPOSE 3020

CMD ["node", "src/index.js"]
