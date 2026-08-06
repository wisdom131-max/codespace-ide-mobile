# ---- build stage ----
FROM node:20-slim AS build
WORKDIR /app
COPY package*.json ./
# Install ALL deps (including dev for @nestjs/cli) — skip native module postinstall scripts
RUN npm install --ignore-scripts --no-audit --no-fund
# Build the NestJS app
RUN npx nest build

# ---- runtime stage ----
FROM node:20-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
EXPOSE 8080
CMD ["node", "dist/main.js"]
