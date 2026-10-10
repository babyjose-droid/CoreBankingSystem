import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { Route, Routes } from 'react-router';
import { ApiProvider } from './api/ApiProvider';
import { isApiError } from './api/errors';
import { AuthProvider, useAuth } from './auth/AuthProvider';
import { RequireAuth, RequirePermission } from './auth/Guards';
import { P } from './auth/permissions';
import { Shell } from './layout/Shell';
import { ApprovalsPage } from './pages/ApprovalsPage';
import { AuditPage } from './pages/AuditPage';
import { AuthCallbackPage, SilentRenewPage } from './pages/AuthCallbackPage';
import { CustomerDetailPage } from './pages/customers/CustomerDetailPage';
import { CustomersPage } from './pages/customers/CustomersPage';
import { NewCustomerPage } from './pages/customers/NewCustomerPage';
import { EodRunDetailPage } from './pages/eod/EodRunDetailPage';
import { EodRunsPage } from './pages/eod/EodRunsPage';
import { EodSchedulePage } from './pages/eod/EodSchedulePage';
import { NotFoundPage } from './pages/ForbiddenPage';
import { HomePage } from './pages/HomePage';
import { LoanDetailPage } from './pages/lending/LoanDetailPage';
import { LoanProductDetailPage } from './pages/lending/LoanProductDetailPage';
import { LoanProductFormPage } from './pages/lending/LoanProductFormPage';
import { LoanProductsPage } from './pages/lending/LoanProductsPage';
import { LoansPage } from './pages/lending/LoansPage';
import { NewLoanPage } from './pages/lending/NewLoanPage';
import { ApiClientsPage } from './pages/integrations/ApiClientsPage';
import { CollectionsPage } from './pages/integrations/CollectionsPage';
import { MandatesPage } from './pages/integrations/MandatesPage';
import { MessagesPage } from './pages/integrations/MessagesPage';
import { NachPage } from './pages/integrations/NachPage';
import { PayoutsPage } from './pages/integrations/PayoutsPage';
import { ProvidersPage } from './pages/integrations/ProvidersPage';
import { SimulatorPage } from './pages/integrations/SimulatorPage';
import { WebhooksPage } from './pages/integrations/WebhooksPage';
import { AccountsPage } from './pages/ledger/AccountsPage';
import { BalanceSheetPage } from './pages/ledger/BalanceSheetPage';
import { ProfitLossPage } from './pages/ledger/ProfitLossPage';
import { TrialBalancePage } from './pages/ledger/TrialBalancePage';
import { VoucherFormPage } from './pages/ledger/VoucherFormPage';
import { VouchersPage } from './pages/ledger/VouchersPage';
import { LoginPage } from './pages/LoginPage';
import { AmountLimitsPage } from './pages/masters/AmountLimitsPage';
import { BranchesPage } from './pages/masters/BranchesPage';
import { BranchSetsPage } from './pages/masters/BranchSetsPage';
import { EnumerationsPage, EnumerationValuesPage } from './pages/masters/EnumerationsPage';
import { StaffPage } from './pages/masters/StaffPage';
import { SystemPropertiesPage } from './pages/masters/SystemPropertiesPage';
import { TerritoryPage } from './pages/masters/TerritoryPage';
import { CustomFieldsPage } from './pages/masters/CustomFieldsPage';
import { DeferredReceiptsPage } from './pages/platform/DeferredReceiptsPage';
import { RateResetsPage } from './pages/lending/rateResets';
import { JobsPage } from './pages/platform/JobsPage';
import { SessionsPage } from './pages/platform/SessionsPage';
import { SupportAccessPage } from './pages/platform/SupportAccessPage';
import { ReportsPage } from './pages/reports/ReportsPage';
import { HolidaysPage } from './pages/masters/HolidaysPage';
import { TaxRatesPage } from './pages/masters/TaxRatesPage';
import { BenchmarksPage } from './pages/masters/BenchmarksPage';
import { InterestTablesPage } from './pages/masters/InterestTablesPage';
import { ToastProvider } from './ui';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Do not retry client errors (403/404/422...); retry transient ones once.
        retry: (count, err) => !(isApiError(err) && err.status < 500) && count < 1,
        refetchOnWindowFocus: false,
        staleTime: 5_000,
      },
      mutations: { retry: false },
    },
  });
}

/** Everything except the router, so tests can wrap it in a MemoryRouter. */
export function AppProviders({
  children,
  queryClient,
  initialMockUser,
  fetchImpl,
}: {
  children: ReactNode;
  queryClient?: QueryClient;
  initialMockUser?: string;
  fetchImpl?: (r: Request) => Promise<Response>;
}) {
  const [qc] = useState(() => queryClient ?? createQueryClient());
  return (
    <QueryClientProvider client={qc}>
      <AuthProvider initialMockUser={initialMockUser}>
        <ApiProvider fetchImpl={fetchImpl}>
          <ToastProvider>{children}</ToastProvider>
        </ApiProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}

function guard(perm: string, el: ReactNode) {
  return <RequirePermission perm={perm}>{el}</RequirePermission>;
}

/** Remount the shell per user so no state leaks between demo users. */
function KeyedShell() {
  const { session } = useAuth();
  return <Shell key={session?.username ?? 'anon'} />;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/auth/callback" element={<AuthCallbackPage />} />
      <Route path="/auth/silent" element={<SilentRenewPage />} />
      <Route
        element={
          <RequireAuth>
            <KeyedShell />
          </RequireAuth>
        }
      >
        <Route index element={<HomePage />} />
        <Route path="approvals" element={guard(P.approvalView, <ApprovalsPage />)} />
        <Route path="customers" element={guard(P.customerView, <CustomersPage />)} />
        <Route path="customers/new" element={guard(P.customerCreate, <NewCustomerPage />)} />
        <Route path="customers/:id" element={guard(P.customerView, <CustomerDetailPage />)} />
        <Route path="loans" element={guard(P.loanView, <LoansPage />)} />
        <Route path="loans/new" element={guard(P.loanCreate, <NewLoanPage />)} />
        <Route path="loans/:id" element={guard(P.loanView, <LoanDetailPage />)} />
        <Route path="loan-products" element={guard(P.productView, <LoanProductsPage />)} />
        <Route path="loan-products/new" element={guard(P.productPropose, <LoanProductFormPage />)} />
        <Route path="loan-products/:code" element={guard(P.productView, <LoanProductDetailPage />)} />
        <Route path="loan-products/:code/edit" element={guard(P.productPropose, <LoanProductFormPage />)} />
        <Route path="ledger/accounts" element={guard(P.glView, <AccountsPage />)} />
        <Route path="ledger/vouchers" element={guard(P.glView, <VouchersPage />)} />
        <Route path="ledger/vouchers/new" element={guard(P.voucherCreate, <VoucherFormPage />)} />
        <Route path="ledger/trial-balance" element={guard(P.glView, <TrialBalancePage />)} />
        <Route path="ledger/profit-and-loss" element={guard(P.glView, <ProfitLossPage />)} />
        <Route path="ledger/balance-sheet" element={guard(P.glView, <BalanceSheetPage />)} />
        <Route path="eod/runs" element={guard(P.eodView, <EodRunsPage />)} />
        <Route path="eod/runs/:runId" element={guard(P.eodView, <EodRunDetailPage />)} />
        <Route path="eod/schedule" element={guard(P.eodView, <EodSchedulePage />)} />
        <Route path="masters/branches" element={guard(P.branchView, <BranchesPage />)} />
        <Route path="masters/holidays" element={guard(P.holidayView, <HolidaysPage />)} />
        <Route path="masters/tax-rates" element={guard(P.taxRateView, <TaxRatesPage />)} />
        <Route path="masters/interest-tables" element={guard(P.productView, <InterestTablesPage />)} />
        <Route path="masters/benchmarks" element={guard(P.benchmarkView, <BenchmarksPage />)} />
        <Route path="masters/branch-sets" element={guard(P.branchView, <BranchSetsPage />)} />
        <Route path="masters/staff" element={guard(P.staffView, <StaffPage />)} />
        <Route path="masters/territory" element={guard(P.masterView, <TerritoryPage />)} />
        <Route path="masters/system-properties" element={guard(P.masterView, <SystemPropertiesPage />)} />
        <Route path="masters/enumerations" element={guard(P.masterView, <EnumerationsPage />)} />
        <Route path="masters/enumerations/:type" element={guard(P.masterView, <EnumerationValuesPage />)} />
        <Route path="masters/amount-limits" element={guard(P.limitView, <AmountLimitsPage />)} />
        <Route path="masters/custom-fields" element={guard(P.customFieldView, <CustomFieldsPage />)} />
        <Route path="deferred-receipts" element={guard(P.loanView, <DeferredReceiptsPage />)} />
        <Route path="rate-resets" element={guard(P.loanView, <RateResetsPage />)} />
        <Route path="jobs" element={guard(P.jobView, <JobsPage />)} />
        <Route path="support-access" element={guard(P.supportAccessApprove, <SupportAccessPage />)} />
        <Route path="sessions" element={<SessionsPage />} />
        <Route path="integrations/providers" element={guard(P.integrationView, <ProvidersPage />)} />
        <Route path="integrations/payouts" element={guard(P.payoutView, <PayoutsPage />)} />
        <Route path="integrations/collections" element={guard(P.collectionView, <CollectionsPage />)} />
        <Route path="integrations/mandates" element={guard(P.mandateView, <MandatesPage />)} />
        <Route path="integrations/nach" element={guard(P.nachAdmin, <NachPage />)} />
        <Route path="integrations/webhooks" element={guard(P.webhookView, <WebhooksPage />)} />
        <Route path="integrations/api-clients" element={guard(P.apiclientView, <ApiClientsPage />)} />
        <Route path="integrations/messages" element={guard(P.messageView, <MessagesPage />)} />
        <Route path="integrations/simulator" element={guard(P.integrationSimulate, <SimulatorPage />)} />
        <Route path="reports" element={guard(P.reportRun, <ReportsPage />)} />
        <Route path="audit" element={guard(P.auditView, <AuditPage />)} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
