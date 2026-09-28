import type { Approval } from '../api/types';
import { useToast } from '../ui';

/** Standard toast after any maker-checker proposal (202). */
export function useProposalToast() {
  const toast = useToast();
  return (a: Approval, what = 'Change') =>
    toast({
      tone: 'success',
      message: `${what} sent for approval.`,
      link: { to: `/approvals?id=${encodeURIComponent(a.id)}`, label: 'View request' },
    });
}
