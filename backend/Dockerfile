# ---- build stage ----
FROM node:20-bookworm AS build
WORKDIR /app
# node-pty requires python3 + build tools to compile native binaries
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && apt-get clean
# Railway uses repo root as build context, so paths must be prefixed with backend/
COPY backend/package*.json ./
# Use npm ci for reproducible builds from lockfile.
# --ignore-scripts skips native compilation; node-pty is optional.
RUN npm ci --ignore-scripts
# Try to build native modules (node-pty, argon2). If it fails, continue.
RUN npm rebuild 2>/dev/null || echo "Some native modules skipped (optional)"
# Copy backend source code
COPY backend/ .
RUN npm run build

# ---- runtime stage ----
FROM node:20-bookworm-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production
# Copy pre-built node_modules (includes compiled native binaries if available)
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
EXPOSE 8080
CMD ["node", "dist/main.js"]
