/*
 * Copyright (c) 2020 Jan Graßegger
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.i.fuzzybanksearch.matcher.fzf;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Normalizer {

    private static final Pattern diacriticalMarksPattern = Pattern
            .compile("\\p{Block=CombiningDiacriticalMarks}+");

    /**
     * Normalizes a string by applying NFKD normalization and removing all diacritical marks
     * afterwards.
     *
     * @param string to be normalized
     * @return normalized string
     */
    public static String normalize(final String string) {
        final var normalizedString = java.text.Normalizer.normalize(
                string, java.text.Normalizer.Form.NFKD);
        return diacriticalMarksPattern.matcher(normalizedString).replaceAll("");
    }

    public static List<String> normalize(final List<String> strings) {
        return strings.parallelStream().map(Normalizer::normalize).collect(Collectors.toList());
    }
}
