package kit.penny.clientbus.crm.api;

import java.time.Instant;
import java.util.List;

/** Completeness applies to this exact dataset/scope, never to all provider data. */
public record CrmReadResult<T>(List<T> items, Instant observedAt, Completeness completeness, String scope) {
    public enum Completeness { COMPLETE, PARTIAL, UNKNOWN }
    public CrmReadResult { items = List.copyOf(items); }
}

