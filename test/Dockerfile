FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    REQUEST_TIMEOUT=60 \
    REQUEST_RETRIES=2

WORKDIR /tests
COPY requirements.txt /tmp/requirements.txt
RUN python -m pip install --no-cache-dir --upgrade pip \
    && python -m pip install --no-cache-dir -r /tmp/requirements.txt

COPY . /tests
RUN chmod +x /tests/run_all.sh

CMD ["/tests/run_all.sh"]
