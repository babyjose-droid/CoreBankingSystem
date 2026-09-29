package com.corebanking.platform;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Branch scope as a data filter (US-020). A staff user sees data of the branches in
 * {@code platform.visible_branches(user)}: every branch for head-office users, otherwise the home branch, the
 * branches granted directly and the members of the branch sets granted. A user without an active staff profile
 * sees nothing.
 *
 * <ul>
 *   <li>Lists add {@link #SQL_VISIBLE} to their WHERE clause with {@link #user()} as the parameter.</li>
 *   <li>A record fetched by id outside the scope is reported as not found, so its existence does not leak.</li>
 *   <li>A branch named explicitly in a request (a filter, a voucher line) outside the scope is 403.</li>
 *   <li>Consolidated views (all-branch trial balance, P&amp;L, balance sheet) need all-branch access.</li>
 * </ul>
 * Batch work (EOD, appliers replaying an approved change) does not go through this filter.
 */
@Component
public class BranchScope {

    /** Append as {@code AND <branch column> } + SQL_VISIBLE, binding {@link #user()}. */
    public static final String SQL_VISIBLE = " IN (SELECT branch_code FROM platform.visible_branches(?))";

    private final JdbcTemplate jdbc;

    public BranchScope(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The user the filter applies to (the Keycloak username). */
    public String user() {
        return CurrentUser.username();
    }

    public boolean seesAll() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT platform.sees_all_branches(?)", Boolean.class, user()));
    }

    public Set<String> branches() {
        List<String> list = jdbc.queryForList("SELECT branch_code FROM platform.visible_branches(?)", String.class, user());
        return Set.copyOf(list);
    }

    public boolean canSee(String branch) {
        return branch != null
                && Boolean.TRUE.equals(jdbc.queryForObject("SELECT platform.can_see_branch(?, ?)", Boolean.class, user(), branch));
    }

    /** For a branch the caller named: 403 when it is outside the scope. */
    public void require(String branch) {
        if (!canSee(branch)) throw ApiException.forbidden("branch " + branch + " is outside your branch scope");
    }

    /** For a record looked up by id: 404 when its branch is outside the scope. */
    public void requireRecord(String branch, String what) {
        if (!canSee(branch)) throw ApiException.notFound(what);
    }

    /** For consolidated (all-branch) views. */
    public void requireAll() {
        if (!seesAll()) throw ApiException.forbidden("this view covers every branch; ask for all-branch access or choose a branch");
    }
}
