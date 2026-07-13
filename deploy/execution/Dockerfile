# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.

FROM python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7

ARG NUMPY_VERSION=2.3.2
ARG PANDAS_VERSION=2.3.1
ARG PYTHON_DATEUTIL_VERSION=2.9.0.post0
ARG PYTZ_VERSION=2026.3.post1
ARG TZDATA_VERSION=2026.3
ARG SIX_VERSION=1.17.0

RUN python -m pip install --no-cache-dir \
      "numpy==${NUMPY_VERSION}" \
      "pandas==${PANDAS_VERSION}" \
      "python-dateutil==${PYTHON_DATEUTIL_VERSION}" \
      "pytz==${PYTZ_VERSION}" \
      "tzdata==${TZDATA_VERSION}" \
      "six==${SIX_VERSION}" \
    && python -m pip check \
    && mkdir -p /app \
    && chmod 0755 /app

WORKDIR /app
USER 65534:65534

CMD ["python3", "-c", "import numpy, pandas; print('SemEvoSQL Python runner ready')"]
