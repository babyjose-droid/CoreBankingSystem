import { useApprovals, useMe } from '../api/hooks';
import type { Approval, Me } from '../api/types';
import { P, hasPermission } from '../auth/permissions';

export function canActOn(a: Approval, me: Me | undefined): boolean {
  return !!me && a.status === 'PENDING' && hasPermission(me.permissions, P.approvalApprove) && a.maker !== me.userId;
}

/** PENDING approvals the current user can decide (has approval:approve and is not the maker). */
export function usePendingForMe() {
  const me = useMe();
  const canView = hasPermission(me.data?.permissions, P.approvalView);
  const q = useApprovals({ status: 'PENDING' }, canView);
  const actionable = (q.data ?? []).filter((a) => canActOn(a, me.data));
  return { canView, actionable, all: q.data ?? [], isLoading: q.isLoading };
}
