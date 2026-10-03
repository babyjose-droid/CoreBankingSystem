package com.corebanking.integration.core.provider;

import java.util.List;
import java.util.Set;

/**
 * The providers the product knows, per kind: which settings and secrets each takes, and whether the adapter has
 * been verified against the provider. Configuration is validated against this list, so a secret can never be
 * stored as a plain setting by mistake and an unknown key is refused.
 */
public final class ProviderCatalog {

    /**
     * @param settings        names of plain settings (shown in the API)
     * @param secrets         names of secrets (encrypted; the API shows only "set" and the last four characters)
     * @param requiredSecrets secrets without which the provider cannot be activated
     * @param verified        false for adapters never run against the provider (UNVERIFIED-AGAINST-PROVIDER)
     */
    public record Spec(ProviderKind kind, String code, Set<String> settings, Set<String> secrets, Set<String> requiredSecrets,
                       boolean verified, String note) {}

    private static final String UNVERIFIED = "UNVERIFIED-AGAINST-PROVIDER: written from public documentation as recalled,"
            + " never run against the provider; confirm in the provider's sandbox before use.";
    private static final String SIM = "Built-in simulator: no network, no money. Never for production tenants.";

    private static final List<Spec> SPECS = List.of(
            new Spec(ProviderKind.PAYOUT, Simulator.CODE, Set.of(), Set.of(Simulator.SECRET), Set.of(), true, SIM),
            new Spec(ProviderKind.COLLECTION, Simulator.CODE, Set.of(), Set.of(Simulator.SECRET), Set.of(), true, SIM),
            new Spec(ProviderKind.MANDATE, Simulator.CODE, Set.of(), Set.of(Simulator.SECRET), Set.of(), true, SIM),
            new Spec(ProviderKind.SMS, Simulator.CODE, Set.of(), Set.of(), Set.of(), true, SIM),
            new Spec(ProviderKind.EMAIL, Simulator.CODE, Set.of("fromAddress"), Set.of(), Set.of(), true, SIM),
            new Spec(ProviderKind.PAYOUT, EasebuzzSpec.CODE, Set.of("wireBaseUrl", "mode"), Set.of("key", "salt"),
                    Set.of("key", "salt"), false, UNVERIFIED),
            new Spec(ProviderKind.COLLECTION, EasebuzzSpec.CODE, Set.of("environment"), Set.of("key", "salt"),
                    Set.of("key", "salt"), false, UNVERIFIED),
            new Spec(ProviderKind.SMS, GenericHttpSmsSender.CODE, Set.of("url", "authHeader", "idField"), Set.of("apiKey"),
                    Set.of("apiKey"), false, UNVERIFIED));

    private ProviderCatalog() {}

    public static List<Spec> all() {
        return SPECS;
    }

    /** @throws IllegalArgumentException when the product has no such provider for the kind */
    public static Spec spec(ProviderKind kind, String code) {
        for (Spec s : SPECS) {
            if (s.kind() == kind && s.code().equals(code)) return s;
        }
        throw new IllegalArgumentException("no provider " + code + " for " + kind);
    }

    public static PayoutGateway payout(String code, ProviderSettings config, HttpTransport http) {
        spec(ProviderKind.PAYOUT, code);
        return code.equals(Simulator.CODE) ? new Simulator.Payout() : new EasebuzzPayoutGateway(config, http);
    }

    public static CollectionGateway collection(String code, ProviderSettings config, HttpTransport http) {
        spec(ProviderKind.COLLECTION, code);
        return code.equals(Simulator.CODE) ? new Simulator.Collection() : new EasebuzzCollectionGateway(config, http);
    }

    public static MandateProvider mandate(String code) {
        spec(ProviderKind.MANDATE, code);
        return new Simulator.Mandate();
    }

    public static SmsSender sms(String code, ProviderSettings config, HttpTransport http) {
        spec(ProviderKind.SMS, code);
        return code.equals(Simulator.CODE) ? new Simulator.Sms() : new GenericHttpSmsSender(config, http);
    }

    public static EmailSender email(String code) {
        spec(ProviderKind.EMAIL, code);
        return new Simulator.Email();
    }

    /** The parser of a provider's callbacks for a kind. */
    public static InboundWebhookParser inbound(ProviderKind kind, String code, ProviderSettings config, HttpTransport http) {
        spec(kind, code);
        if (code.equals(Simulator.CODE)) return new Simulator.Webhooks();
        if (code.equals(EasebuzzSpec.CODE) && kind == ProviderKind.COLLECTION) return new EasebuzzCollectionGateway(config, http);
        if (code.equals(EasebuzzSpec.CODE) && kind == ProviderKind.PAYOUT) return new EasebuzzPayoutGateway(config, http);
        throw new WebhookRejectedException("provider " + code + " sends no callbacks for " + kind);
    }
}
