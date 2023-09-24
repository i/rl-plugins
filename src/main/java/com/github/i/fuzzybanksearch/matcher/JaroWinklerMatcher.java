package com.github.i.fuzzybanksearch.matcher;

import org.apache.commons.text.similarity.JaroWinklerDistance;
import org.apache.commons.text.similarity.SimilarityScore;

import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

public class JaroWinklerMatcher extends Matcher {
    private final SimilarityScore<Double> baseAlgorithm = new JaroWinklerDistance();
    private final Collection<String> dictionary;

    public JaroWinklerMatcher( Collection<String> dictionary) {
        this.dictionary = dictionary;
    }

    public Set<String> match(String query, int limit) {
        return this.dictionary
                .stream()
                .map(term -> new MatchResult(term, this.score(query, term)))
                .sorted(Comparator.comparingDouble(res -> (-res.getScore())))
                .map(MatchResult::getTerm)
                .limit(limit)
                .collect(Collectors.toSet());
    }

    private Double score(String query, String itemName) {
        query = query.toLowerCase().replace('-', ' ');
        itemName = itemName.toLowerCase().replace('-', ' ');

        // we raise the score for items that share a prefix with the query
        int prefixLen = 0;
        int maxLen = Math.min(query.length(), itemName.length());
        while (prefixLen < maxLen && query.charAt(prefixLen) == itemName.charAt(prefixLen))
        {
            prefixLen++;
        }
        double prefixScore = ((double) prefixLen) / query.length() - 0.25;

        // and also raise the score for string "closeness"
        double proximityScore = baseAlgorithm.apply(query, itemName) - 0.25;
        return prefixScore + proximityScore;
    }

    private static class MatchResult {
        private final String term;
        private final double score;

        public MatchResult(String term, double score) {
            this.term = term;
            this.score = score;
        }

        public double getScore() {
            return this.score;
        }

        public String getTerm() {
            return this.term;
        }
    }
}
