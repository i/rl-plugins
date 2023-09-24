package com.github.i.fuzzybanksearch.matcher;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public abstract class Matcher {
    protected Map<Integer, String> itemIdsToNames = null;

   public abstract Set<String> match(String query, int limit);
}
