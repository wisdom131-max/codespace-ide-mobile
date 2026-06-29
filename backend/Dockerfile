# ---- build stage ----
FROM node:20-bookworm AS build
WORKDIR /app
# node-pty requires python3 + build tools to compile native binaries
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && apt-get clean
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# ---- runtime stage ----
FROM node:20-bookworm-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production
# Copy pre-built node_modules (includes compiled node-pty .node binary)
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
EXPOSE 8080
CMD ["node", "dist/main.js"]
