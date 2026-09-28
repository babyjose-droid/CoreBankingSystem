import { useMe } from '../api/hooks';
import { hasPermission } from './permissions';

/** Permission checks against /api/v1/me (server truth). Returns false while loading. */
export function useCan(): (perm: string) => boolean {
  const me = useMe();
  return (perm: string) => hasPermission(me.data?.permissions, perm);
}
