FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1
ENV SOCK_PATH=/var/run/function.sock

WORKDIR /var/task

COPY bootstrap.py /var/runtime/bootstrap.py

RUN mkdir -p /var/run

CMD ["python", "/var/runtime/bootstrap.py"]