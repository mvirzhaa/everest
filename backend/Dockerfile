FROM node:22-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY src ./src
COPY migrations ./migrations
COPY public ./public
COPY scripts ./scripts
RUN mkdir -p /data/uploads /data/models && chown -R node:node /data
USER node
EXPOSE 8080
CMD ["node", "src/server.js"]
