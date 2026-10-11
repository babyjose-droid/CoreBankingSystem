package com.corebanking.deposits;

import java.util.List;

/** A deposit the product or the rule set does not allow, with every reason. */
public final class DepositRefusal extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    public DepositRefusal(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() { return problems == null ? List.of(getMessage()) : problems; }
}
