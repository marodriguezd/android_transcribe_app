package dev.notune.transcribe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class WordCorrector {
    private static final Pattern ES_FILLER_PATTERN = Pattern.compile(
            "\\b(?:ehm|mmm|este|o sea|eh|ah|pues|bueno)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EN_FILLER_PATTERN = Pattern.compile(
            "\\b(?:uh|um|ah|er|like|hmm|you know|i mean)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REPEATED_WORD_PATTERN = Pattern.compile(
            "\\b(\\w+)(?:\\s+\\1){2,}\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("\\s{2,}");
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9&]");
    private static final Pattern NON_ALNUM_NOAMP_PATTERN = Pattern.compile("[^a-z0-9]");
    private final List<NormalizedEntry> entries;
    private final double threshold;

    public WordCorrector(List<String> customWords, double threshold) {
        this.threshold = threshold;
        this.entries = new ArrayList<>();
        for (String word : customWords) {
            if (word == null || word.isEmpty()) continue;
            String normalized = normalize(word);
            if (!normalized.isEmpty()) {
                entries.add(new NormalizedEntry(normalized, word, soundex(normalized)));
            }
            if (word.contains("&")) {
                String expanded = normalizeExpanded(word);
                if (!expanded.isEmpty() && !expanded.equals(normalized)) {
                    entries.add(new NormalizedEntry(expanded, word, soundex(expanded)));
                }
            }
        }
    }

    public String applyCustomWords(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] tokens = text.trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < tokens.length) {
            if (result.length() > 0) result.append(' ');
            boolean matched = false;
            for (int n = 3; n >= 1; n--) {
                if (i + n > tokens.length) continue;
                String joined = joinWithoutPunct(tokens, i, n);
                BestMatch best = findBestMatch(joined);
                if (best != null) {
                    StringBuilder raw = new StringBuilder(tokens[i]);
                    for (int j = i + 1; j < i + n; j++) {
                        raw.append(' ').append(tokens[j]);
                    }
                    String replaced = applyCaseAndPunctuation(best.original, raw.toString());
                    result.append(replaced);
                    i += n;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                result.append(tokens[i]);
                i++;
            }
        }
        return result.toString();
    }

    private String normalize(String word) {
        return word.toLowerCase().replaceAll("[^a-z0-9&]", "");
    }

    private String normalizeExpanded(String word) {
        return word.toLowerCase().replace("&", "and").replaceAll("[^a-z0-9]", "");
    }

    // Package-private for testing
    static Pattern getNonAlnumPattern() {
        return NON_ALNUM_PATTERN;
    }

    private String joinWithoutPunct(String[] tokens, int start, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < start + n; i++) {
            sb.append(tokens[i]);
        }
        return sb.toString().toLowerCase().replaceAll("[^a-z0-9&]", "");
    }

    private BestMatch findBestMatch(String joined) {
        BestMatch best = null;
        double bestScore = threshold;
        String joinedSoundex = soundex(joined);
        for (NormalizedEntry entry : entries) {
            int maxLen = Math.max(joined.length(), entry.length);
            if (maxLen == 0) continue;
            double lenDiff = Math.abs(joined.length() - entry.length) / (double) maxLen;
            if (lenDiff >= threshold) continue;
            double levDist = levenshtein(joined, entry.normalized);
            double levScore = levDist / maxLen;
            double combinedScore;
            if (!joinedSoundex.isEmpty() && joinedSoundex.equals(entry.soundexCode)) {
                combinedScore = levScore * 0.3;
            } else {
                combinedScore = levScore;
            }
            if (combinedScore < bestScore) {
                bestScore = combinedScore;
                best = new BestMatch(entry.original, combinedScore);
            }
        }
        return best;
    }

    private double levenshtein(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) dp[i][0] = i;
        for (int j = 0; j <= n; j++) dp[0][j] = j;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(dp[i - 1][j] + 1, Math.min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost));
            }
        }
        return dp[m][n];
    }

    private String soundex(String s) {
        if (s == null || s.isEmpty()) return "";
        char[] result = new char[4];
        result[0] = Character.toUpperCase(s.charAt(0));
        int resultIdx = 1;
        char prevCode = '0';
        for (int i = 1; i < s.length() && resultIdx < 4; i++) {
            char c = Character.toUpperCase(s.charAt(i));
            char code = soundexCode(c);
            if (code != '0') {
                if (code != prevCode) {
                    result[resultIdx++] = code;
                    prevCode = code;
                }
            } else {
                prevCode = '0';
            }
        }
        while (resultIdx < 4) {
            result[resultIdx++] = '0';
        }
        return new String(result);
    }

    private char soundexCode(char c) {
        switch (c) {
            case 'B': case 'F': case 'P': case 'V': return '1';
            case 'C': case 'G': case 'J': case 'K': case 'Q': case 'S': case 'X': case 'Z': return '2';
            case 'D': case 'T': return '3';
            case 'L': return '4';
            case 'M': case 'N': return '5';
            case 'R': return '6';
            default: return '0';
        }
    }

    private String applyCaseAndPunctuation(String replacement, String original) {
        String lead = "";
        String trail = "";
        int start = 0;
        int end = original.length();
        while (start < end && !Character.isLetterOrDigit(original.charAt(start))) {
            lead += original.charAt(start);
            start++;
        }
        while (end > start && !Character.isLetterOrDigit(original.charAt(end - 1))) {
            trail = original.charAt(end - 1) + trail;
            end--;
        }
        String core = original.substring(start, end);
        String result;
        if (isAllUpperCase(core)) {
            result = replacement.toUpperCase();
        } else if (isCapitalized(core)) {
            result = Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        } else {
            result = replacement.toLowerCase();
        }
        return lead + result + trail;
    }

    private boolean isAllUpperCase(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i)) && !Character.isUpperCase(s.charAt(i))) return false;
        }
        return true;
    }

    private boolean isCapitalized(String s) {
        if (s.isEmpty()) return false;
        return Character.isUpperCase(s.charAt(0));
    }

    public static String filterTranscriptionOutput(String text, String lang) {
        if (text == null || text.isEmpty()) return text;

        String result = removeFillerWords(text, lang);

        result = REPEATED_WORD_PATTERN.matcher(result).replaceAll("$1");

        result = MULTI_SPACE_PATTERN.matcher(result).replaceAll(" ").trim();

        return result;
    }

    private static String removeFillerWords(String text, String lang) {
        Pattern pattern = "es".equals(lang) ? ES_FILLER_PATTERN : EN_FILLER_PATTERN;
        return pattern.matcher(text).replaceAll("");
    }

    private static class NormalizedEntry {
        String normalized;
        String original;
        int length;
        String soundexCode;
        NormalizedEntry(String normalized, String original, String soundexCode) {
            this.normalized = normalized;
            this.original = original;
            this.length = normalized.length();
            this.soundexCode = soundexCode;
        }
    }

    private static class BestMatch {
        String original;
        double score;
        BestMatch(String original, double score) {
            this.original = original;
            this.score = score;
        }
    }
}
