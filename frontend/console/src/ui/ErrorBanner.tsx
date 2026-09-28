import { isApiError } from '../api/errors';
import { Banner } from './Display';

export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null;
  if (isApiError(error)) {
    return (
      <Banner tone="danger">
        <span>
          <strong>{error.title}</strong>
          {error.problem.detail ? ` — ${error.problem.detail}` : ''}
        </span>
      </Banner>
    );
  }
  return <Banner tone="danger">{error instanceof Error ? error.message : 'Something went wrong'}</Banner>;
}
