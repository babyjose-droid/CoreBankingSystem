import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router';
import { useBusinessDay, useMe } from '../api/hooks';
import { useAuth } from '../auth/AuthProvider';
import { hasPermission } from '../auth/permissions';
import { cx } from '../lib/cx';
import { resolvedApiBaseUrl } from '../config';
import { formatDate } from '../lib/dates';
import { Badge, Button, ErrorBanner, Spinner } from '../ui';
import { branchScopeText } from './branchScope';
import { visibleNav } from './nav';
import { usePendingForMe } from './usePendingForMe';

export function Shell() {
  const me = useMe();
  const [navOpen, setNavOpen] = useState(false);
  const location = useLocation();
  useEffect(() => setNavOpen(false), [location.pathname]);

  if (me.isPending) {
    return (
      <div className="login">
        <Spinner label="Loading your workspace" />
      </div>
    );
  }
  if (me.isError) {
    return (
      <div className="login">
        <div style={{ maxWidth: 520 }}>
          <ErrorBanner error={me.error} />
        </div>
      </div>
    );
  }
  return (
    <div className={cx('app', navOpen && 'nav-open')}>
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      <Header navOpen={navOpen} onToggleNav={() => setNavOpen((o) => !o)} />
      <Sidebar />
      <main id="main" className="main" tabIndex={-1}>
        <Outlet />
        <footer className="app-footer">
          <span>Help:</span>
          <a href={`${resolvedApiBaseUrl()}/developer`} target="_blank" rel="noreferrer">
            Developer portal
          </a>
          <span className="muted">API reference, integration guides and the sandbox</span>
        </footer>
      </main>
    </div>
  );
}

function Header({ navOpen, onToggleNav }: { navOpen: boolean; onToggleNav: () => void }) {
  const me = useMe().data!;
  const day = useBusinessDay();
  const pending = usePendingForMe();
  const tipId = useId();
  const businessDate = day.data?.businessDate ?? me.businessDate;
  return (
    <header className="header">
      <Button variant="ghost" className="menu-toggle" aria-label="Menu" aria-expanded={navOpen} aria-controls="sidebar" onClick={onToggleNav}>
        ☰
      </Button>
      <Link to="/" className="header__brand">
        <span className="header__logo" aria-hidden="true" />
        <span className="header__brand-text">CoreBanking</span>
      </Link>
      <div className="header__tenant">
        <span className="header__tenant-name" data-testid="tenant-name">
          {me.tenantName ?? me.tenant}
        </span>
        <span className="muted" style={{ fontSize: 12 }}>
          Home branch: <strong>{me.homeBranch ?? '—'}</strong>
        </span>
      </div>
      <div className="header__spacer" />
      <div className="bizdate" tabIndex={0} title="Business date changes only through end-of-day" aria-describedby={tipId} data-testid="business-date">
        <span className="bizdate__label">Business date</span>
        <span className="bizdate__value">{formatDate(businessDate)}</span>
        <span id={tipId} className="sr-only">
          Read-only. Changes only through end-of-day.
        </span>
      </div>
      {day.data && day.data.status !== 'OPEN' && <Badge tone={day.data.status === 'EOD_FAILED' ? 'danger' : 'info'}>{day.data.status === 'EOD_RUNNING' ? 'EOD running' : day.data.status === 'EOD_FAILED' ? 'EOD failed' : 'Closed'}</Badge>}
      {pending.canView && (
        <Link to="/approvals" className="header__approvals" aria-label={`Approvals: ${pending.actionable.length} awaiting your decision`}>
          <span className="header__approvals-label">Approvals</span>
          <span className="count-badge" data-testid="approvals-badge">
            {pending.actionable.length}
          </span>
        </Link>
      )}
      <UserMenu />
    </header>
  );
}

function UserMenu() {
  const me = useMe().data!;
  const auth = useAuth();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const menuId = useId();
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        btnRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    ref.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);
  const initials = me.displayName
    .split(/\s+/)
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();
  return (
    <div className="usermenu" ref={ref}>
      <Button ref={btnRef} variant="ghost" className="usermenu__button" aria-haspopup="menu" aria-expanded={open} aria-controls={menuId} onClick={() => setOpen((o) => !o)}>
        <span className="avatar" aria-hidden="true">
          {initials}
        </span>
        <span className="usermenu__name">{me.displayName}</span>
        <span className="sr-only">User menu</span>
      </Button>
      {open && (
        <div className="usermenu__panel" role="menu" id={menuId} aria-label="User menu">
          <div className="usermenu__info">
            <div style={{ fontWeight: 600 }}>{me.displayName}</div>
            <div className="muted mono">{me.userId}</div>
            <div style={{ fontSize: 12, marginTop: 4 }} data-testid="branch-scope">
              {branchScopeText(me)}
            </div>
            <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>
              Tenant <span className="mono">{me.tenant}</span> · {me.permissions.length} permissions
              {auth.mode === 'mock' && ' · mock mode'}
            </div>
          </div>
          <Link to="/sessions" role="menuitem" className="usermenu__item" onClick={() => setOpen(false)}>
            My sessions
          </Link>
          <button
            type="button"
            role="menuitem"
            className="usermenu__item"
            onClick={() => {
              qc.clear();
              void auth.logout();
            }}
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}

function Sidebar() {
  const me = useMe().data;
  const pending = usePendingForMe();
  const groups = visibleNav((p) => hasPermission(me?.permissions, p));
  return (
    <nav id="sidebar" className="sidebar" aria-label="Main">
      {groups.map((g, gi) => (
        <div className="nav__group" key={g.label ?? `g${gi}`}>
          {g.label && (
            <div className="nav__group-label" id={`nav-${g.label}`}>
              {g.label}
            </div>
          )}
          <ul className="nav__list" aria-labelledby={g.label ? `nav-${g.label}` : undefined}>
            {g.items.map((i) => (
              <li key={i.to}>
                <NavLink to={i.to} end={i.end} className="nav__link">
                  <span>{i.label}</span>
                  {i.badge === 'approvals' && pending.actionable.length > 0 && (
                    <span className="count-badge" aria-label={`${pending.actionable.length} pending`}>
                      {pending.actionable.length}
                    </span>
                  )}
                </NavLink>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );
}
