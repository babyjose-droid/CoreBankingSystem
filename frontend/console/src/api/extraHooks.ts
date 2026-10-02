import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { config } from '../config';
import { fileNameFromDisposition, saveBlob } from '../lib/download';
import { unwrap, type ApiClient } from './client';
import { ApiError } from './errors';
import type {
  AmountLimit,
  AmountLimitInput,
  Approval,
  Consent,
  ConsentInput,
  CustomerExposure,
  CustomerRelationship,
  Dashboard,
  KycDocument,
  LoanParty,
  Money,
  RelationshipInput,
  ReportDefinition,
  ReportRun,
} from './types';
import { useApiClient } from './useApiClient';

const live = config.isTest ? false : 60_000;

// ---------- files ----------
type BlobResult = { data?: unknown; error?: unknown; response: Response };
export type FileRequest = { request: (api: ApiClient) => Promise<BlobResult>; fallbackName: string };

/**
 * Fetches a file with the bearer token in the Authorization header (never in a URL) and saves it from a blob.
 * Problem+json errors (403, 404, 409 …) come back as ApiError like any other call.
 */
export function useFileDownload() {
  const api = useApiClient();
  return useMutation({
    mutationFn: async ({ request, fallbackName }: FileRequest) => {
      const { data, error, response } = await request(api);
      if (!response.ok) throw ApiError.fromResponse(response, error);
      const fileName = fileNameFromDisposition(response.headers.get('Content-Disposition'), fallbackName);
      saveBlob(data as Blob, fileName);
      return fileName;
    },
  });
}

export const loanDocument = {
  kfs: (id: string): FileRequest => ({ request: (api) => api.GET('/api/v1/loans/{id}/documents/kfs.pdf', { params: { path: { id } }, parseAs: 'blob' }), fallbackName: 'kfs.pdf' }),
  schedule: (id: string): FileRequest => ({ request: (api) => api.GET('/api/v1/loans/{id}/documents/schedule.pdf', { params: { path: { id } }, parseAs: 'blob' }), fallbackName: 'schedule.pdf' }),
  noc: (id: string): FileRequest => ({ request: (api) => api.GET('/api/v1/loans/{id}/documents/noc.pdf', { params: { path: { id } }, parseAs: 'blob' }), fallbackName: 'noc.pdf' }),
  statement: (id: string, from?: string, to?: string): FileRequest => ({
    request: (api) => api.GET('/api/v1/loans/{id}/documents/statement.pdf', { params: { path: { id }, query: { from: from || undefined, to: to || undefined } }, parseAs: 'blob' }),
    fallbackName: 'statement.pdf',
  }),
  invoice: (id: string, chargeId: string): FileRequest => ({
    request: (api) => api.GET('/api/v1/loans/{id}/charges/{chargeId}/invoice.pdf', { params: { path: { id, chargeId } }, parseAs: 'blob' }),
    fallbackName: `invoice-${chargeId}.pdf`,
  }),
};

export const reportFile = (id: string, part?: 'rejections'): FileRequest => ({
  request: (api) => api.GET('/api/v1/reports/runs/{id}/download', { params: { path: { id }, query: { part } }, parseAs: 'blob' }),
  fallbackName: part ? 'rejections.csv' : 'report.csv',
});

export const kycFile = (id: string, docId: string): FileRequest => ({
  request: (api) => api.GET('/api/v1/customers/{id}/kyc-documents/{docId}/content', { params: { path: { id, docId } }, parseAs: 'blob' }),
  fallbackName: 'kyc-document',
});

// ---------- dashboard ----------
export function useDashboard(enabled: boolean) {
  const api = useApiClient();
  return useQuery({ queryKey: ['dashboard'], queryFn: () => unwrap<Dashboard>(api.GET('/api/v1/dashboard')), enabled, refetchInterval: live });
}

// ---------- reports ----------
export function useReports() {
  const api = useApiClient();
  return useQuery({ queryKey: ['reports'], queryFn: () => unwrap<ReportDefinition[]>(api.GET('/api/v1/reports')), staleTime: 60_000 });
}

export function useReportRuns(page: number, size = 20) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['report-runs', page, size],
    queryFn: () => unwrap<ReportRun[]>(api.GET('/api/v1/reports/runs', { params: { query: { page, size } } })),
    refetchInterval: (q) => (q.state.data?.some((r) => r.status === 'RUNNING') ? 2000 : false),
  });
}

export function useRunReport() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ code, parameters }: { code: string; parameters: Record<string, string> }) =>
      unwrap<ReportRun>(api.POST('/api/v1/reports/{code}/runs', { params: { path: { code } }, body: { parameters } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['report-runs'] }),
  });
}

// ---------- role amount limits ----------
export function useAmountLimits(currentOnly: boolean) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['amount-limits', currentOnly],
    queryFn: () => unwrap<AmountLimit[]>(api.GET('/api/v1/amount-limits', { params: { query: { currentOnly: currentOnly || undefined } } })),
  });
}

function useAfterProposal() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: ['approvals'] });
}

export function useProposeAmountLimit() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (l: AmountLimitInput) => unwrap<Approval>(api.POST('/api/v1/amount-limits', { body: l }) as never),
    onSuccess: after,
  });
}

// ---------- loan parties ----------
export function useLoanParties(id: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loan', id, 'parties'],
    queryFn: () => unwrap<LoanParty[]>(api.GET('/api/v1/loans/{id}/parties', { params: { path: { id: id! } } })),
    enabled: !!id,
  });
}

// ---------- customer: relationships and exposure ----------
export function useCustomerRelationships(id: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['customer', id, 'relationships'],
    queryFn: () => unwrap<CustomerRelationship[]>(api.GET('/api/v1/customers/{id}/relationships', { params: { path: { id } } })),
  });
}

export function useProposeRelationships(id: string) {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (relationships: RelationshipInput[]) =>
      unwrap<Approval>(api.POST('/api/v1/customers/{id}/relationships', { params: { path: { id } }, body: { relationships } }) as never),
    onSuccess: after,
  });
}

export function useCustomerExposure(id: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['customer', id, 'exposure'],
    queryFn: () => unwrap<CustomerExposure>(api.GET('/api/v1/customers/{id}/exposure', { params: { path: { id } } })),
  });
}

export function useProposeExposureLimit(id: string) {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (b: { exposureLimit: Money | null; reason: string }) =>
      unwrap<Approval>(api.POST('/api/v1/customers/{id}/exposure-limit', { params: { path: { id } }, body: b }) as never),
    onSuccess: after,
  });
}

// ---------- customer: consents ----------
export function useConsents(id: string, enabled: boolean) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['customer', id, 'consents'],
    queryFn: () => unwrap<Consent[]>(api.GET('/api/v1/customers/{id}/consents', { params: { path: { id } } })),
    enabled,
  });
}

export function useRecordConsent(id: string) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (c: ConsentInput) => unwrap<Consent>(api.POST('/api/v1/customers/{id}/consents', { params: { path: { id } }, body: c })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['customer', id, 'consents'] }),
  });
}

export function useWithdrawConsent(id: string) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ consentId, reason }: { consentId: string; reason: string }) =>
      unwrap<Consent>(api.POST('/api/v1/customers/{id}/consents/{consentId}/withdraw', { params: { path: { id, consentId } }, body: { reason } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['customer', id, 'consents'] }),
  });
}

// ---------- customer: KYC documents ----------
export function useKycDocuments(id: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['customer', id, 'kyc-documents'],
    queryFn: () => unwrap<KycDocument[]>(api.GET('/api/v1/customers/{id}/kyc-documents', { params: { path: { id } } })),
  });
}

export type KycContentType = KycDocument['contentType'];
export const KYC_CONTENT_TYPES: KycContentType[] = ['application/pdf', 'image/jpeg', 'image/png'];
export const KYC_MAX_BYTES = 5 * 1024 * 1024;

export interface KycUpload {
  file: File;
  docType: string;
  issueDate?: string;
  expiryDate?: string;
  /** Sent only in the X-Document-Number header; never logged, cached or put in a URL. */
  documentNumber?: string;
}

function useAfterKycChange(id: string) {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: ['customer', id] });
    void qc.invalidateQueries({ queryKey: ['customers'] });
  };
}

export function useUploadKycDocument(id: string) {
  const api = useApiClient();
  const after = useAfterKycChange(id);
  return useMutation({
    // The file is the raw request body (no multipart); metadata travels as query parameters.
    mutationFn: async (u: KycUpload) => {
      const bytes = new Uint8Array(await u.file.arrayBuffer());
      return unwrap<KycDocument>(
        api.POST('/api/v1/customers/{id}/kyc-documents', {
          params: {
            path: { id },
            query: { docType: u.docType, issueDate: u.issueDate || undefined, expiryDate: u.expiryDate || undefined },
            header: u.documentNumber ? { 'X-Document-Number': u.documentNumber } : undefined,
          },
          body: bytes as never,
          bodySerializer: (b: unknown) => b as BodyInit,
          headers: { 'Content-Type': u.file.type },
        }),
      );
    },
    // Keep nothing of the request (it holds the document number) in the mutation cache.
    gcTime: 0,
    onSuccess: after,
  });
}

export function useVerifyKycDocument(id: string) {
  const api = useApiClient();
  const after = useAfterKycChange(id);
  return useMutation({
    mutationFn: ({ docId, maskingConfirmed, note }: { docId: string; maskingConfirmed?: boolean; note?: string }) =>
      unwrap<KycDocument>(api.POST('/api/v1/customers/{id}/kyc-documents/{docId}/verify', { params: { path: { id, docId } }, body: { maskingConfirmed, note } })),
    onSuccess: after,
  });
}

export function useRejectKycDocument(id: string) {
  const api = useApiClient();
  const after = useAfterKycChange(id);
  return useMutation({
    mutationFn: ({ docId, reason }: { docId: string; reason: string }) =>
      unwrap<KycDocument>(api.POST('/api/v1/customers/{id}/kyc-documents/{docId}/reject', { params: { path: { id, docId } }, body: { reason } })),
    onSuccess: after,
  });
}
