# Stage 1: build the Vite SPA.
FROM node:20-alpine AS spa-build
WORKDIR /spa
COPY app/package.json app/package-lock.json* ./
RUN npm install
COPY app ./
RUN npm run build

# Stage 2: slim runtime — the BFF serving itself + the built SPA. Mirrors the
# repo's own web/server + web/app layout so index.js's relative path to the
# SPA's dist/ (../../app/dist from web/server/src) resolves identically
# whether run from the repo directly or from this image.
FROM node:20-alpine
WORKDIR /app
COPY server/package.json server/package-lock.json* ./server/
RUN cd server && npm install --omit=dev
COPY server/src ./server/src
COPY server/public ./server/public
COPY --from=spa-build /spa/dist ./app/dist

ENV NODE_ENV=production
ENV PORT=3021
EXPOSE 3021

CMD ["node", "server/src/index.js"]
