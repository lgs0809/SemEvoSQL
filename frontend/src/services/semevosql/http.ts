/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import axios from "axios";

export const operationsBase = "/api/semevosql/operations";
export const apiBase = "/api/semevosql";

axios.interceptors.response.use(
  (response) => response,
  (error) => {
    const serverMessage = error?.response?.data?.message;
    if (serverMessage) error.message = serverMessage;
    return Promise.reject(error);
  },
);

export const governedMutationHeaders = (operation: string) => {
  const requestId = crypto.randomUUID();
  return {
    "X-Request-ID": requestId,
    "Idempotency-Key": `${operation}:${requestId}`,
  };
};

export { axios };
