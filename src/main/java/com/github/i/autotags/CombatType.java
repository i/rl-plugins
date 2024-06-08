package com.github.i.autotags;

import java.util.List;

public enum CombatType {
    UNKNOWN,
    IGNORE,
    SPECIAL,
    MELEE,
    RANGE,
    MAGIC;

    static final List<CombatType> CHOICE_LIST = List.of(
            MELEE,
            RANGE,
            MAGIC,
            SPECIAL,
            IGNORE
    );

    static CombatType fromString(String str) {
        return CHOICE_LIST.stream()
                .filter(choice -> str.endsWith(choice.toString()))
                .findFirst()
                .get();
    }
}
