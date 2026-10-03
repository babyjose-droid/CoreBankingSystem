import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from './client';
import type { FileRequest } from './extraHooks';
import type {
  ApiClientInput, ApiClientRecord, ApiClientScopeList, ApiClientSecret, Beneficiary, BeneficiaryInput, CollectionOrder, CollectionOrderInput, Mandate, MandateInput,
  MandateStatusUpdate, MessageLogRow, MessageTemplate, MessageTemplateInput, MessageVariables, NachFile, NachPresentation, Payout, PaymentResolution, ProviderConfig,
  ProviderConfigInput, ProviderSpec, ReconCategory, ReconRow, SettlementUploadResult, SimulatedCallback, SimulatedCallbackResult, WebhookDelivery, WebhookEndpoint,
  WebhookEndpointInput, WebhookEventTypes, WebhookSecret,
} from './integrationTypes';
import type { Approval } from './types';
import { useApiClient } from './useApiClient';

/**
 * The contract types integration responses as open records; `as never` hands the typed result to unwrap<T>, whose T
 * is the shape in integrationTypes.ts.
 */
function useAfterProposal() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: ['approvals'] });
}
const text = (contentType: string) => ({ bodySerializer: (b: string) => b, headers: { 'Content-Type': contentType } });

// ---------- providers ----------
export function useProviderCatalogue() {
  const api = useApiClient();
  return useQuery({ queryKey: ['integrations', 'catalogue'], queryFn: () => unwrap<ProviderSpec[]>(api.GET('/api/v1/integrations/providers/catalogue') as never), staleTime: 60_000 });
}
export function useProviderConfigs(history: boolean) {
  const api = useApiClient();
  return useQuery({ queryKey: ['integrations', 'providers', history], queryFn: () => unwrap<ProviderConfig[]>(api.GET('/api/v1/integrations/providers', { params: { query: { history: history || undefined } } }) as never) });
}
/** Secrets travel in this one request and are not put in any cache: the mutation result is the approval only. */
export function useProposeProviderConfig() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({ mutationFn: (input: ProviderConfigInput) => unwrap<Approval>(api.POST('/api/v1/integrations/providers', { body: input }) as never), onSuccess: after, gcTime: 0 });
}
export function useProposeProviderDeactivation() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({ mutationFn: (kind: string) => unwrap<Approval>(api.POST('/api/v1/integrations/providers/{kind}/deactivate', { params: { path: { kind } } }) as never), onSuccess: after });
}
export function useSimulateCallback() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (cb: SimulatedCallback) => unwrap<SimulatedCallbackResult>(api.POST('/api/v1/integrations/simulator/callbacks', { body: cb }) as never),
    onSuccess: () => void qc.invalidateQueries(),
  });
}

// ---------- payouts ----------
export function usePayouts(f: { status: string; needsAction: boolean }) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['payouts', f.status, f.needsAction],
    queryFn: () => unwrap<Payout[]>(api.GET('/api/v1/payouts', { params: { query: { status: f.status || undefined, needsAction: f.needsAction || undefined } } }) as never),
  });
}
export function usePayout(id: string | null) {
  const api = useApiClient();
  return useQuery({ queryKey: ['payouts', 'one', id], queryFn: () => unwrap<Payout>(api.GET('/api/v1/payouts/{id}', { params: { path: { id: id! } } }) as never), enabled: !!id });
}
export function usePayoutAction() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'retry' | 'refresh' }) =>
      unwrap<Payout>((action === 'retry' ? api.POST('/api/v1/payouts/{id}/retry', { params: { path: { id } } }) : api.POST('/api/v1/payouts/{id}/refresh', { params: { path: { id } } })) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['payouts'] }),
  });
}
export function useBeneficiary(loanId: string | null) {
  const api = useApiClient();
  return useQuery({ queryKey: ['beneficiary', loanId], queryFn: () => unwrap<Beneficiary>(api.GET('/api/v1/loans/{id}/payout-beneficiary', { params: { path: { id: loanId! } } }) as never), enabled: !!loanId, retry: false });
}
/** The account number goes out in this request only; the response and every cache hold the masked form. */
export function useSetBeneficiary() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ loanId, input }: { loanId: string; input: BeneficiaryInput }) => unwrap<Beneficiary>(api.PUT('/api/v1/loans/{id}/payout-beneficiary', { params: { path: { id: loanId } }, body: input }) as never),
    onSuccess: (_d, v) => {
      void qc.invalidateQueries({ queryKey: ['beneficiary', v.loanId] });
      void qc.invalidateQueries({ queryKey: ['payouts'] });
    },
    gcTime: 0,
  });
}

// ---------- collections ----------
export function useCollectionOrders(loanId: string) {
  const api = useApiClient();
  return useQuery({ queryKey: ['collection-orders', loanId], queryFn: () => unwrap<CollectionOrder[]>(api.GET('/api/v1/loans/{id}/collection-orders', { params: { path: { id: loanId } } }) as never), enabled: !!loanId });
}
export function useCreateCollectionOrder() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ loanId, input }: { loanId: string; input: CollectionOrderInput }) => unwrap<CollectionOrder>(api.POST('/api/v1/loans/{id}/collection-orders', { params: { path: { id: loanId } }, body: input }) as never),
    onSuccess: (_d, v) => void qc.invalidateQueries({ queryKey: ['collection-orders', v.loanId] }),
  });
}
export function useReconciliation(category: ReconCategory | '') {
  const api = useApiClient();
  return useQuery({ queryKey: ['reconciliation', category], queryFn: () => unwrap<ReconRow[]>(api.GET('/api/v1/gateway-payments/reconciliation', { params: { query: { category: category || undefined } } }) as never) });
}
export function useResolvePayment() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: PaymentResolution }) => unwrap<unknown>(api.POST('/api/v1/gateway-payments/{id}/resolve', { params: { path: { id } }, body: input }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['reconciliation'] }),
  });
}
export function useUploadSettlements() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ fileRef, csv }: { fileRef: string; csv: string }) => unwrap<SettlementUploadResult>(api.POST('/api/v1/gateway-settlements/upload', { params: { query: { fileRef } }, body: csv, ...text('text/csv') }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['reconciliation'] }),
  });
}

// ---------- mandates and NACH ----------
export function useMandates(status: string) {
  const api = useApiClient();
  return useQuery({ queryKey: ['mandates', status], queryFn: () => unwrap<Mandate[]>(api.GET('/api/v1/mandates', { params: { query: { status: status || undefined } } }) as never) });
}
export function useMandate(id: string | null) {
  const api = useApiClient();
  return useQuery({ queryKey: ['mandates', 'one', id], queryFn: () => unwrap<Mandate>(api.GET('/api/v1/mandates/{id}', { params: { path: { id: id! } } }) as never), enabled: !!id });
}
export function useLoanPresentations(loanId: string | undefined) {
  const api = useApiClient();
  return useQuery({ queryKey: ['nach', 'presentations', loanId], queryFn: () => unwrap<NachPresentation[]>(api.GET('/api/v1/loans/{id}/nach-presentations', { params: { path: { id: loanId! } } }) as never), enabled: !!loanId });
}
export function useRegisterMandate() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ loanId, input }: { loanId: string; input: MandateInput }) => unwrap<Mandate>(api.POST('/api/v1/loans/{id}/mandates', { params: { path: { id: loanId } }, body: input }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['mandates'] }),
    gcTime: 0,
  });
}
export function useUpdateMandateStatus() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: MandateStatusUpdate }) => unwrap<Mandate>(api.POST('/api/v1/mandates/{id}/status', { params: { path: { id } }, body: input }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['mandates'] }),
  });
}
export function useNachFiles(direction: string) {
  const api = useApiClient();
  return useQuery({ queryKey: ['nach', 'files', direction], queryFn: () => unwrap<NachFile[]>(api.GET('/api/v1/nach/files', { params: { query: { direction: direction || undefined } } }) as never) });
}
export function usePendingPresentations() {
  const api = useApiClient();
  return useQuery({ queryKey: ['nach', 'pending'], queryFn: () => unwrap<NachPresentation[]>(api.GET('/api/v1/nach/presentations/pending') as never) });
}
function useNachInvalidate() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: ['nach'] });
    void qc.invalidateQueries({ queryKey: ['loan'] });
  };
}
export function useGenerateNach() {
  const api = useApiClient();
  const done = useNachInvalidate();
  return useMutation({ mutationFn: () => unwrap<NachFile[]>(api.POST('/api/v1/nach/presentations/generate') as never), onSuccess: done });
}
export function useUploadNachResponse() {
  const api = useApiClient();
  const done = useNachInvalidate();
  return useMutation({ mutationFn: (content: string) => unwrap<NachFile>(api.POST('/api/v1/nach/responses', { body: content, ...text('text/plain') }) as never), onSuccess: done });
}
export function useSimulateNachResponse() {
  const api = useApiClient();
  const done = useNachInvalidate();
  return useMutation({ mutationFn: (id: string) => unwrap<NachFile>(api.POST('/api/v1/nach/files/{id}/simulate-response', { params: { path: { id } } }) as never), onSuccess: done });
}
/** Downloaded with the bearer token in the header and saved from a blob: the file holds account numbers. */
export const nachFileContent = (id: string, fileRef: string): FileRequest => ({
  request: (api) => api.GET('/api/v1/nach/files/{id}/content', { params: { path: { id } }, parseAs: 'blob' }),
  fallbackName: `${fileRef}.txt`,
});

// ---------- webhooks ----------
export function useWebhookEventTypes() {
  const api = useApiClient();
  return useQuery({ queryKey: ['webhooks', 'event-types'], queryFn: () => unwrap<WebhookEventTypes>(api.GET('/api/v1/webhooks/event-types') as never), staleTime: 60_000 });
}
export function useWebhookEndpoints() {
  const api = useApiClient();
  return useQuery({ queryKey: ['webhooks', 'endpoints'], queryFn: () => unwrap<WebhookEndpoint[]>(api.GET('/api/v1/webhooks/endpoints') as never) });
}
export function useProposeWebhookEndpoint() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: ({ id, input }: { id?: string; input: WebhookEndpointInput }) =>
      unwrap<Approval>((id ? api.PUT('/api/v1/webhooks/endpoints/{id}', { params: { path: { id } }, body: input }) : api.POST('/api/v1/webhooks/endpoints', { body: input })) as never),
    onSuccess: after,
  });
}
export function useProposeWebhookAction() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({ mutationFn: ({ id, action }: { id: string; action: string }) => unwrap<Approval>(api.POST('/api/v1/webhooks/endpoints/{id}/actions', { params: { path: { id } }, body: { action } }) as never), onSuccess: after });
}
/**
 * One-time secrets: gcTime 0 and no query cache. The value lives only in the mutation result while the dialog is
 * open; the dialog resets the mutation when it closes.
 */
export function useCollectWebhookSecret() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => unwrap<WebhookSecret>(api.POST('/api/v1/webhooks/endpoints/{id}/secret', { params: { path: { id } } }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['webhooks', 'endpoints'] }),
    gcTime: 0,
  });
}
export function useWebhookDeliveries(f: { endpointId: string; status: string }) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['webhooks', 'deliveries', f.endpointId, f.status],
    queryFn: () => unwrap<WebhookDelivery[]>(api.GET('/api/v1/webhooks/deliveries', { params: { query: { endpointId: f.endpointId || undefined, status: f.status || undefined } } }) as never),
  });
}
export function useWebhookDelivery(id: string | null) {
  const api = useApiClient();
  return useQuery({ queryKey: ['webhooks', 'deliveries', 'one', id], queryFn: () => unwrap<WebhookDelivery>(api.GET('/api/v1/webhooks/deliveries/{id}', { params: { path: { id: id! } } }) as never), enabled: !!id });
}
export function useReplayDelivery() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => unwrap<WebhookDelivery>(api.POST('/api/v1/webhooks/deliveries/{id}/replay', { params: { path: { id } } }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['webhooks', 'deliveries'] }),
  });
}
export function useReplayDead() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => unwrap<{ replayed?: number }>(api.POST('/api/v1/webhooks/endpoints/{id}/replay-dead', { params: { path: { id } } }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['webhooks', 'deliveries'] }),
  });
}

// ---------- API clients ----------
export function useApiClients() {
  const api = useApiClient();
  return useQuery({ queryKey: ['api-clients'], queryFn: () => unwrap<ApiClientRecord[]>(api.GET('/api/v1/api-clients') as never) });
}
export function useApiClientScopes() {
  const api = useApiClient();
  return useQuery({ queryKey: ['api-clients', 'scopes'], queryFn: () => unwrap<ApiClientScopeList>(api.GET('/api/v1/api-clients/scopes') as never), staleTime: 60_000 });
}
export function useProposeApiClient() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({ mutationFn: (input: ApiClientInput) => unwrap<Approval>(api.POST('/api/v1/api-clients', { body: input }) as never), onSuccess: after });
}
export function useProposeApiClientScopes() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: ({ clientId, scopes }: { clientId: string; scopes: string[] }) => unwrap<Approval>(api.PUT('/api/v1/api-clients/{clientId}/scopes', { params: { path: { clientId } }, body: { scopes } }) as never),
    onSuccess: after,
  });
}
export function useProposeApiClientAction() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: ({ clientId, action }: { clientId: string; action: string }) => unwrap<Approval>(api.POST('/api/v1/api-clients/{clientId}/actions', { params: { path: { clientId } }, body: { action } }) as never),
    onSuccess: after,
  });
}
export function useCollectApiClientSecret() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (clientId: string) => unwrap<ApiClientSecret>(api.POST('/api/v1/api-clients/{clientId}/secret', { params: { path: { clientId } } }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['api-clients'] }),
    gcTime: 0,
  });
}

// ---------- messages ----------
export function useMessageTemplates() {
  const api = useApiClient();
  return useQuery({ queryKey: ['messages', 'templates'], queryFn: () => unwrap<MessageTemplate[]>(api.GET('/api/v1/message-templates') as never) });
}
export function useMessageVariables() {
  const api = useApiClient();
  return useQuery({ queryKey: ['messages', 'variables'], queryFn: () => unwrap<MessageVariables>(api.GET('/api/v1/message-templates/variables') as never), staleTime: 60_000 });
}
export function useProposeMessageTemplate() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({ mutationFn: (input: MessageTemplateInput) => unwrap<Approval>(api.POST('/api/v1/message-templates', { body: input }) as never), onSuccess: after });
}
export function useMessageLog(status: string) {
  const api = useApiClient();
  return useQuery({ queryKey: ['messages', 'log', status], queryFn: () => unwrap<MessageLogRow[]>(api.GET('/api/v1/messages', { params: { query: { status: status || undefined } } }) as never) });
}
