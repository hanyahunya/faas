FROM node:18-alpine

ENV NODE_ENV=production
ENV SOCK_PATH=/var/run/function.sock

WORKDIR /var/task

COPY bootstrap.js /var/runtime/bootstrap.js

RUN mkdir -p /var/run && chown node:node /var/run

CMD ["node", "/var/runtime/bootstrap.js"]