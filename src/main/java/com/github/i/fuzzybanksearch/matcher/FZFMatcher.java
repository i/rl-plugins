package com.github.i.fuzzybanksearch.matcher;

import com.github.i.fuzzybanksearch.matcher.fzf.FuzzyMatcherV1;
import com.github.i.fuzzybanksearch.matcher.fzf.OrderBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public class FZFMatcher extends Matcher {
    private final FuzzyMatcherV1 matcher;

    public FZFMatcher(Collection<String> dictionary) {
        this.matcher = new FuzzyMatcherV1(
                        new ArrayList<>(dictionary),
                        OrderBy.SCORE,
                        true,
                        false);
    }

    @Override
    public Set<String> match(String query, int limit) {
        return this.matcher.match(query)
                .stream()
                .limit(limit)
                .collect(Collectors.toSet());
    }
}
