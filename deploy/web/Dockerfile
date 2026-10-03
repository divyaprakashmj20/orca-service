FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/
ARG RELEASE_SHA
RUN test -n "$RELEASE_SHA" && printf '{"release":"%s"}\n' "$RELEASE_SHA" > /usr/share/nginx/html/release.json
