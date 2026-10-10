import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from './useApiClient';
import { unwrap } from './client';
import type {
  Approval,
  BranchSet,
  EnumValue,
  EnumValueInput,
  EnumerationType,
  PincodePlace,
  PropertyChange,
  Staff,
  State,
  SystemProperty,
  VoucherUploadResult,
} from './types';

function useAfterProposal() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: ['approvals'] });
}

/** CSV bodies go as text/csv exactly as read from the file (no JSON encoding). */
const csv = { bodySerializer: (b: string) => b, headers: { 'Content-Type': 'text/csv' } };

// ---------- staff ----------
export function useStaff() {
  const api = useApiClient();
  return useQuery({ queryKey: ['staff'], queryFn: () => unwrap<Staff[]>(api.GET('/api/v1/staff')) });
}

export function useProposeStaff() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (s: Staff) => unwrap<Approval>(api.POST('/api/v1/staff', { body: s }) as never),
    onSuccess: after,
  });
}

// ---------- branch sets ----------
export function useBranchSets(enabled = true) {
  const api = useApiClient();
  return useQuery({ queryKey: ['branch-sets'], queryFn: () => unwrap<BranchSet[]>(api.GET('/api/v1/branch-sets')), enabled });
}

export function useProposeBranchSet() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (s: BranchSet) => unwrap<Approval>(api.POST('/api/v1/branch-sets', { body: s }) as never),
    onSuccess: after,
  });
}

// ---------- system properties ----------
export function useSystemProperties(enabled = true) {
  const api = useApiClient();
  return useQuery({ enabled, queryKey: ['system-properties'], queryFn: () => unwrap<SystemProperty[]>(api.GET('/api/v1/system-properties')) });
}

export function useProposeSystemProperty() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: ({ key, change }: { key: string; change: PropertyChange }) =>
      unwrap<Approval>(api.PUT('/api/v1/system-properties/{key}', { params: { path: { key } }, body: change }) as never),
    onSuccess: after,
  });
}

// ---------- enumerations ----------
export function useEnumerationTypes() {
  const api = useApiClient();
  return useQuery({ queryKey: ['enum-types'], queryFn: () => unwrap<EnumerationType[]>(api.GET('/api/v1/enumerations')) });
}

/** Like useEnumeration but always fresh (the edit screen must not show a cached list). */
export function useEnumerationValues(type: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['enum', type, 'edit'],
    queryFn: () => unwrap<EnumValue[]>(api.GET('/api/v1/enumerations/{type}', { params: { path: { type } } })),
  });
}

export function useProposeEnumeration() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: ({ type, values }: { type: string; values: EnumValueInput[] }) =>
      unwrap<Approval>(api.POST('/api/v1/enumerations/{type}', { params: { path: { type } }, body: values }) as never),
    onSuccess: after,
  });
}

// ---------- territory ----------
export function useStates() {
  const api = useApiClient();
  return useQuery({ queryKey: ['states'], queryFn: () => unwrap<State[]>(api.GET('/api/v1/states')), staleTime: 60_000 });
}

export function usePincodeLookup(pincode: string | null) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['pincode', pincode],
    queryFn: () => unwrap<PincodePlace[]>(api.GET('/api/v1/pincodes/{pincode}', { params: { path: { pincode: pincode! } } })),
    enabled: !!pincode,
  });
}

// ---------- uploads ----------
export function useUploadHolidays() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (text: string) => unwrap<Approval>(api.POST('/api/v1/holidays/upload', { body: text, ...csv }) as never),
    onSuccess: after,
  });
}

export function useUploadTerritory() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (text: string) => unwrap<Approval>(api.POST('/api/v1/territory/upload', { body: text, ...csv }) as never),
    onSuccess: after,
  });
}

export function useUploadVouchers() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (text: string) => unwrap<VoucherUploadResult>(api.POST('/api/v1/gl/vouchers/upload', { body: text, ...csv }) as never),
    onSuccess: after,
  });
}
