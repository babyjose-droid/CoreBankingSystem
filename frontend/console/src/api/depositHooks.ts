import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from './client';
import type {
  Approval,
  CasaAccountInput,
  DepositAccount,
  DepositAccountSummary,
  DepositFamily,
  DepositProduct,
  DepositProductInput,
  DepositProductVersion,
  DepositRuleSet,
  TermDepositInput,
  TermDepositPreview,
} from './types';
import { useApiClient } from './useApiClient';

/** The deposit rule set in force. `family` picks the path, so a tenant with only one of the modules can read it. */
export function useDepositRules(family: DepositFamily, enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-rules'],
    queryFn: () => unwrap<DepositRuleSet>(family === 'CASA' ? api.GET('/api/v1/casa/rules') : api.GET('/api/v1/deposits/rules')),
    staleTime: 60_000,
    enabled,
  });
}

export function useDepositProducts(family: DepositFamily, enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-products', family],
    queryFn: () => unwrap<DepositProduct[]>(family === 'CASA' ? api.GET('/api/v1/casa/products') : api.GET('/api/v1/deposits/products')),
    enabled,
  });
}

export function useDepositProduct(family: DepositFamily, code: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-product', family, code],
    queryFn: () =>
      unwrap<DepositProduct>(
        family === 'CASA'
          ? api.GET('/api/v1/casa/products/{code}', { params: { path: { code: code! } } })
          : api.GET('/api/v1/deposits/products/{code}', { params: { path: { code: code! } } }),
      ),
    enabled: !!code,
  });
}

export function useDepositProductHistory(family: DepositFamily, code: string | undefined, enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-product-history', family, code],
    queryFn: () =>
      unwrap<DepositProductVersion[]>(
        family === 'CASA'
          ? api.GET('/api/v1/casa/products/{code}/history', { params: { path: { code: code! } } })
          : api.GET('/api/v1/deposits/products/{code}/history', { params: { path: { code: code! } } }),
      ),
    enabled: !!code && enabled,
  });
}

/** What stops the product as entered from being approved; nothing is stored. */
export function useCheckDepositProduct(family: DepositFamily) {
  const api = useApiClient();
  return useMutation({
    mutationFn: (body: DepositProductInput) =>
      unwrap<{ problems?: string[] }>(family === 'CASA' ? api.POST('/api/v1/casa/products/check', { body }) : api.POST('/api/v1/deposits/products/check', { body })),
  });
}

export function useProposeDepositProduct(family: DepositFamily) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: DepositProductInput) =>
      unwrap<Approval>((family === 'CASA' ? api.POST('/api/v1/casa/products', { body }) : api.POST('/api/v1/deposits/products', { body })) as never),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

export function useWithdrawDepositProduct(family: DepositFamily, code: string) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (reason: string) =>
      unwrap<Approval>(
        (family === 'CASA'
          ? api.POST('/api/v1/casa/products/{code}/withdraw', { params: { path: { code } }, body: { reason } })
          : api.POST('/api/v1/deposits/products/{code}/withdraw', { params: { path: { code } }, body: { reason } })) as never,
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

export function useDepositAccounts(family: DepositFamily, filter: { customerId?: string; status?: string } = {}, enabled = true) {
  const api = useApiClient();
  const query = { customerId: filter.customerId || undefined, status: (filter.status || undefined) as DepositAccountSummary['status'] };
  return useQuery({
    queryKey: ['deposit-accounts', family, filter.customerId ?? null, filter.status ?? null],
    queryFn: () =>
      unwrap<DepositAccountSummary[]>(
        family === 'CASA' ? api.GET('/api/v1/casa/accounts', { params: { query } }) : api.GET('/api/v1/deposits/accounts', { params: { query } }),
      ),
    enabled,
  });
}

export function useDepositAccount(family: DepositFamily, id: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-account', family, id],
    queryFn: () =>
      unwrap<DepositAccount>(
        family === 'CASA'
          ? api.GET('/api/v1/casa/accounts/{id}', { params: { path: { id: id! } } })
          : api.GET('/api/v1/deposits/accounts/{id}', { params: { path: { id: id! } } }),
      ),
    enabled: !!id,
  });
}

export function useOpenCasaAccount() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CasaAccountInput) => unwrap<Approval>(api.POST('/api/v1/casa/accounts', { body }) as never),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

/** The figures of a deposit before it is booked: the same calculation as the booking. */
export function usePreviewTermDeposit() {
  const api = useApiClient();
  return useMutation({
    mutationFn: (body: TermDepositInput) => unwrap<TermDepositPreview>(api.POST('/api/v1/deposits/preview', { body })),
  });
}

export function useBookTermDeposit() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: TermDepositInput) => unwrap<Approval>(api.POST('/api/v1/deposits/accounts', { body }) as never),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}
