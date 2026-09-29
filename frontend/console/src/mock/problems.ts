/** RFC 9457 problem helpers shared by the mock API modules. */
export const PROBLEM_BASE = 'https://corebanking.example/problems/';

export class HttpProblem extends Error {
  constructor(
    readonly status: number,
    readonly title: string,
    readonly detail?: string,
    readonly extra: Record<string, unknown> = {},
    readonly type = 'about:blank',
  ) {
    super(detail ?? title);
  }
}

export type FieldProblem = { field: string; message: string };

export const bad = (detail: string, errors?: FieldProblem[]) =>
  new HttpProblem(422, 'Validation failed', detail, errors ? { errors } : {}, PROBLEM_BASE + 'validation');
export const conflict = (title: string, detail?: string, extra: Record<string, unknown> = {}) =>
  new HttpProblem(409, title, detail, extra, PROBLEM_BASE + 'conflict');
export const notFound = (what: string) => new HttpProblem(404, 'Not found', `${what} not found`, {}, PROBLEM_BASE + 'not-found');
