import { describe, expect, it } from 'vitest';
import { createApiClient, needsIdempotencyKey, unwrap } from './client';
import { ApiError } from './errors';

function recorder(response: () => Response) {
  const requests: Request[] = [];
  return {
    requests,
    fetch: async (r: Request) => {
      requests.push(r);
      return response();
    },
  };
}

describe('api client', () => {
  it('decides which requests carry an Idempotency-Key', () => {
    expect(needsIdempotencyKey('POST', 'http://x/api/v1/customers')).toBe(true);
    expect(needsIdempotencyKey('POST', 'http://x/api/v1/gl/vouchers/abc/reverse')).toBe(true);
    expect(needsIdempotencyKey('POST', 'http://x/api/v1/approvals/abc/approve')).toBe(false);
    expect(needsIdempotencyKey('POST', 'http://x/api/v1/customers/dedupe-check')).toBe(false);
    expect(needsIdempotencyKey('GET', 'http://x/api/v1/customers')).toBe(false);
  });

  it('attaches the bearer token and a UUID Idempotency-Key on creating POSTs', async () => {
    const rec = recorder(() => new Response(JSON.stringify({ id: 'a' }), { status: 202, headers: { 'Content-Type': 'application/json' } }));
    const api = createApiClient({ baseUrl: 'http://localhost', getToken: () => 'tok123', fetch: rec.fetch });
    await api.POST('/api/v1/branches', { body: { code: 'TST', name: 'Test', stateCode: '32' } });
    await api.GET('/api/v1/branches');
    expect(rec.requests[0].headers.get('Authorization')).toBe('Bearer tok123');
    expect(rec.requests[0].headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(rec.requests[1].headers.get('Idempotency-Key')).toBeNull();
  });

  it('turns problem+json into a typed ApiError', async () => {
    const rec = recorder(
      () =>
        new Response(JSON.stringify({ type: 'x', title: 'Maker cannot approve own request', status: 409, detail: 'nope' }), {
          status: 409,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    );
    const api = createApiClient({ baseUrl: 'http://localhost', getToken: () => null, fetch: rec.fetch });
    const err = await unwrap(api.POST('/api/v1/approvals/{id}/approve', { params: { path: { id: '1' } }, body: {} })).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(409);
    expect((err as ApiError).title).toBe('Maker cannot approve own request');
    expect(rec.requests[0].headers.get('Authorization')).toBeNull();
  });
});
