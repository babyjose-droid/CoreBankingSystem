import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from './client';
import type { Approval, CustomField, CustomFieldInput, CustomValues, DeferredReceipt, Job, JobRun, JobScheduleInput, LoginSession, SupportAccess } from './types';
import { useApiClient } from './useApiClient';

function useAfterProposal() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: ['approvals'] });
}

// ---------- custom fields ----------
export type CustomEntity = CustomFieldInput['entity'];

export function useCustomFields(entity: CustomEntity | undefined, enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['custom-fields', entity ?? null],
    queryFn: () => unwrap<CustomField[]>(api.GET('/api/v1/custom-fields', { params: { query: { entity } } })),
    enabled,
    staleTime: 60_000,
  });
}

export function useProposeCustomField() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (f: CustomFieldInput) => unwrap<Approval>(api.POST('/api/v1/custom-fields', { body: f }) as never),
    onSuccess: after,
  });
}

export function useLoanProductCustom(code: string | undefined, enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loan-product', code, 'custom'],
    queryFn: () => unwrap<{ productCode?: string; custom?: CustomValues }>(api.GET('/api/v1/loan-products/{code}/custom', { params: { path: { code: code! } } })),
    enabled: enabled && !!code,
  });
}

export function useProposeLoanProductCustom(code: string) {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (custom: CustomValues) => unwrap<Approval>(api.PUT('/api/v1/loan-products/{code}/custom', { params: { path: { code } }, body: { custom } }) as never),
    onSuccess: after,
  });
}

// ---------- deferred receipts ----------
export function useDeferredReceipts(status: DeferredReceipt['status'] | '') {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deferred-receipts', status],
    queryFn: () => unwrap<DeferredReceipt[]>(api.GET('/api/v1/deferred-receipts', { params: { query: { status: status || undefined } } })),
  });
}

export function useResolveDeferredReceipt() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, note }: { id: string; note?: string }) =>
      unwrap<DeferredReceipt>(
        note === undefined
          ? api.POST('/api/v1/deferred-receipts/{id}/retry', { params: { path: { id } } })
          : api.POST('/api/v1/deferred-receipts/{id}/cancel', { params: { path: { id } }, body: { note } }),
      ),
    onSuccess: () => void qc.invalidateQueries(),
  });
}

// ---------- sessions ----------
export function useSessions(user: string | null) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['sessions', user],
    queryFn: () => unwrap<LoginSession[]>(api.GET('/api/v1/sessions', { params: { query: { user: user || undefined } } })),
  });
}

export function useEndSession() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, user }: { id: string; user: string | null }) => unwrap<void>(api.DELETE('/api/v1/sessions/{id}', { params: { path: { id }, query: { user: user || undefined } } }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['sessions'] }),
  });
}

// ---------- jobs ----------
export function useJobs() {
  const api = useApiClient();
  return useQuery({ queryKey: ['jobs'], queryFn: () => unwrap<Job[]>(api.GET('/api/v1/jobs')) });
}

export function useJobRuns(job: string, page: number, size = 20) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['job-runs', job, page, size],
    queryFn: () => unwrap<JobRun[]>(api.GET('/api/v1/jobs/runs', { params: { query: { job: job || undefined, page, size } } })),
  });
}

export function useProposeJobSchedule() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: ({ code, input }: { code: string; input: JobScheduleInput }) => unwrap<Approval>(api.PUT('/api/v1/jobs/{code}', { params: { path: { code } }, body: input }) as never),
    onSuccess: after,
  });
}

export function useRunJob() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => unwrap<JobRun>(api.POST('/api/v1/jobs/{code}/run', { params: { path: { code } } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['jobs'] });
      void qc.invalidateQueries({ queryKey: ['job-runs'] });
      void qc.invalidateQueries({ queryKey: ['deferred-receipts'] });
    },
  });
}

// ---------- support access ----------
export function useSupportAccess(status: SupportAccess['status'] | '') {
  const api = useApiClient();
  return useQuery({
    queryKey: ['support-access', status],
    queryFn: () => unwrap<SupportAccess[]>(api.GET('/api/v1/support-access', { params: { query: { status: (status || undefined) as never } } })),
  });
}

export type SupportDecision = 'approve' | 'reject' | 'revoke';
export function useDecideSupportAccess() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, decision, note }: { id: string; decision: SupportDecision; note?: string }) => {
      const opts = { params: { path: { id } }, body: note ? { note } : {} };
      return unwrap<SupportAccess>(
        decision === 'approve' ? api.POST('/api/v1/support-access/{id}/approve', opts) : decision === 'reject' ? api.POST('/api/v1/support-access/{id}/reject', opts) : api.POST('/api/v1/support-access/{id}/revoke', opts),
      );
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['support-access'] }),
  });
}
