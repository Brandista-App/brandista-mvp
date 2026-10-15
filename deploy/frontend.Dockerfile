FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY src ./src
COPY index.html vite.config.ts tsconfig.json tsconfig.app.json tsconfig.node.json ./
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM nginx:stable-alpine
COPY --from=build /app/dist /usr/share/nginx/html
# Mounted from this backend repository by compose.demo.yaml.
EXPOSE 80
