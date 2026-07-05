# ---- build stage ----
FROM node:20-bookworm AS build
WORKDIR /app
# node-pty requires python3 + build tools to compile native binaries
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && apt-get clean
COPY package*.json ./
# Use npm ci for reproducible builds from lockfile.
# --ignore-scripts skips native compilation (node-pty); it's optional.
RUN npm ci --ignore-scripts
# Now try to build native modules (node-pty). If it fails, continue — it's optional.
RUN npm rebuild node-pty 2>/dev/null || echo "node-pty native build skipped (optional)"
COPY . .
RUN npm run build

# ---- runtime stage ----
FROM node:20-bookworm-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production
# Copy pre-built node_modules (includes compiled node-pty .node binary if available)
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
EXPOSE 8080
CMD ["node", "dist/main.js"]
