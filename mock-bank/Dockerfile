FROM python:3.12-slim
WORKDIR /srv/mock-bank
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
ENV PROMETHEUS_MULTIPROC_DIR=/tmp/payfold-bank-metrics
RUN mkdir -p /tmp/payfold-bank-metrics
EXPOSE 8080
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port 8080 --workers ${BANK_WORKERS:-1}"]
