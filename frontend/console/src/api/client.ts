import createClient, { type Middleware } from 'openapi-fetch';
import type { paths } from './schema';
import { ApiError } from './errors';

export type ApiClient = ReturnType<typeof createClient<paths>>;

export interface ClientOptions {
  baseUrl: string;
  /** Returns the current bearer token (or null when signed out). */
  getToken: () => string | null | Promise<string | null>;
  /** Custom fetch, e.g. the in-memory mock API. */
  fetch?: (request: Request) => Promise<Response>;
  onUnauthorized?: () => void;
}

/** POSTs that do not create a resource and therefore do not carry an Idempotency-Key. */
const NON_CREATING_POST = [/\/approvals\//, /\/dedupe-check$/, /\/loans\/preview$/, /\/amendments\/preview$/, /\/restructure\/simulation$/];

export function needsIdempotencyKey(method: string, url: string): boolean {
  if (method.toUpperCase() !== 'POST') return false;
  const path = new URL(url, 'http://x').pathname;
  return !NON_CREATING_POST.some((re) => re.test(path));
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

export function createApiClient(opts: ClientOptions): ApiClient {
  const client = createClient<paths>({
    baseUrl: opts.baseUrl,
    fetch: opts.fetch,
    headers: { Accept: 'application/json, application/problem+json' },
  });
  const auth: Middleware = {
    async onRequest({ request }) {
      const token = await opts.getToken();
      if (token) request.headers.set('Authorization', `Bearer ${token}`);
      if (needsIdempotencyKey(request.method, request.url) && !request.headers.has('Idempotency-Key')) {
        request.headers.set('Idempotency-Key', newIdempotencyKey());
      }
      return request;
    },
    onResponse({ response }) {
      if (response.status === 401) opts.onUnauthorized?.();
      return undefined;
    },
  };
  client.use(auth);
  return client;
}

type Result<T> = { data?: T; error?: unknown; response: Response };

/** Unwraps an openapi-fetch result: returns data, or throws a typed ApiError built from problem+json. */
export async function unwrap<T>(promise: Promise<Result<T>>): Promise<T> {
  const { data, error, response } = await promise;
  if (!response.ok) throw ApiError.fromResponse(response, error);
  return data as T;
}

/** Like unwrap, but also returns the HTTP status (to distinguish 200 vs 202 on idempotent creates). */
export async function unwrapWithStatus<T>(promise: Promise<Result<T>>): Promise<{ data: T; status: number }> {
  const { data, error, response } = await promise;
  if (!response.ok) throw ApiError.fromResponse(response, error);
  return { data: data as T, status: response.status };
}
