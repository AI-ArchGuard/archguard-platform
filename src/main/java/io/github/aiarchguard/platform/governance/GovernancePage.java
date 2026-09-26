package io.github.aiarchguard.platform.governance;

import java.util.List;

public record GovernancePage<T>(List<T> items, int page, int size, boolean hasMore) {
    public GovernancePage { items = List.copyOf(items); }

    public static <T> GovernancePage<T> of(List<T> fetched, int page, int size) {
        boolean more = fetched.size() > size;
        return new GovernancePage<>(more ? fetched.subList(0, size) : fetched, page, size, more);
    }
}
