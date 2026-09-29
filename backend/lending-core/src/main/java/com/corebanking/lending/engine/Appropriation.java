package com.corebanking.lending.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Splits a receipt across outstanding dues in the product's order (US-055). Two modes:
 * <ul>
 *   <li>BY_DEMAND: settle the oldest demand fully (components in sequence) before the next demand.</li>
 *   <li>BY_COMPONENT: settle one component across all demands (oldest first) before the next component.</li>
 * </ul>
 * Anything left over is excess (advance / refundable), never silently dropped.
 */
public final class Appropriation {

    public enum Component { PENAL, FEE, INTEREST, PRINCIPAL }
    public enum Mode { BY_DEMAND, BY_COMPONENT }

    /** One unpaid component of one demand (or charge). {@code ref} identifies the demand/charge row. */
    public record Due(String ref, LocalDate dueDate, Component component, BigDecimal outstanding) {
        public Due {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(dueDate);
            Objects.requireNonNull(component);
            if (outstanding == null || outstanding.signum() < 0) throw new IllegalArgumentException("outstanding must be >= 0");
        }
    }

    public record Allocation(String ref, LocalDate dueDate, Component component, BigDecimal amount) {}

    public record Result(List<Allocation> allocations, BigDecimal excess) {
        public BigDecimal total(Component c) {
            return allocations.stream().filter(a -> a.component() == c).map(Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** RBI-friendly default: interest before principal, charges last, so charges never push an account into NPA. */
    public static final List<Component> DEFAULT_SEQUENCE =
            List.of(Component.INTEREST, Component.PRINCIPAL, Component.PENAL, Component.FEE);

    private Appropriation() {}

    public static Result allocate(List<Due> dues, BigDecimal amount, List<Component> sequence, Mode mode) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        if (sequence.size() != Component.values().length || !sequence.containsAll(List.of(Component.values()))) {
            throw new IllegalArgumentException("sequence must list every component exactly once");
        }
        Map<Component, Integer> rank = new java.util.EnumMap<>(Component.class);
        for (int i = 0; i < sequence.size(); i++) rank.put(sequence.get(i), i);
        Comparator<Due> byDate = Comparator.comparing(Due::dueDate).thenComparing(Due::ref);
        Comparator<Due> byComponent = Comparator.comparing(d -> rank.get(d.component()));
        Comparator<Due> order = mode == Mode.BY_DEMAND ? byDate.thenComparing(byComponent) : byComponent.thenComparing(byDate);

        List<Due> sorted = new ArrayList<>(dues);
        sorted.sort(order);
        BigDecimal left = amount;
        List<Allocation> out = new ArrayList<>();
        for (Due d : sorted) {
            if (left.signum() == 0) break;
            if (d.outstanding().signum() == 0) continue;
            BigDecimal take = left.min(d.outstanding());
            out.add(new Allocation(d.ref(), d.dueDate(), d.component(), take));
            left = left.subtract(take);
        }
        return new Result(List.copyOf(out), left);
    }
}
