package org.las.sso.account;

import java.time.OffsetDateTime;

public class UsernameCooldownException extends RuntimeException {

    private final OffsetDateTime availableAt;

    public UsernameCooldownException(OffsetDateTime availableAt) {
        super("Username change cooldown active");
        this.availableAt = availableAt;
    }

    public OffsetDateTime availableAt() {
        return availableAt;
    }
}
