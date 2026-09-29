import type { Problem } from './types';

/** Field-level problem (Problem.errors items in the contract). */
export type FieldError = NonNullable<Problem['errors']>[number];

/** RFC 9457 problem details, typed. `errors` is an optional extension member used for field validation. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;

  constructor(status: number, problem: Problem) {
    super(problem.detail || problem.title || `Request failed (${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }

  get title(): string {
    return this.problem.title ?? `Error ${this.status}`;
  }

  get fieldErrors(): FieldError[] {
    return this.problem.errors ?? [];
  }

  static fromResponse(response: Response, body: unknown): ApiError {
    if (body && typeof body === 'object') {
      const p = body as Problem;
      return new ApiError(response.status, { status: response.status, ...p });
    }
    return new ApiError(response.status, {
      status: response.status,
      title: response.statusText || 'Request failed',
      detail: typeof body === 'string' && body ? body.slice(0, 300) : undefined,
    });
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

export function errorMessage(e: unknown): string {
  if (isApiError(e)) return e.problem.detail ? `${e.title}: ${e.problem.detail}` : e.title;
  if (e instanceof Error) return e.message;
  return 'Something went wrong';
}
