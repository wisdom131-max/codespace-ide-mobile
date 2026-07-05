# ---- build stage ----
FROM node:20-bookworm AS build
WORKDIR /app
# node-pty requires python3 + build tools to compile native binaries
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && apt-get clean
COPY package*.json ./
# Install deps — node-pty is optional, so don't fail the build if it can't compile
RUN npm install --ignore-scripts
# Try to build native modules. If it fails, continue — they're optional.
RUN npm rebuild 2>/dev/null || true
COPY . .
RUN npm run build

# ---- runtime stage ----
FROM node:20-bookworm-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
EXPOSE 8080
CMD ["node", "dist/main.js"]
