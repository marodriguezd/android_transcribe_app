package dev.notune.transcribe;

/**
 * Resolves the <em>source</em> language of a finalized subtitle segment so the
 * on-device translator knows what to translate from. The live-subtitle path
 * only delivers text (not the detected-language tag the ASR model produces
 * internally), and ML Kit needs an explicit source language, so we resolve it
 * here.
 *
 * <p>Order of precedence:</p>
 * <ol>
 *   <li>A fixed language chosen in the Speech models screen
 *       ({@code model_language} marker holding a concrete tag). The user knows
 *       what they are watching, so this always wins.</li>
 *   <li>Script detection of the segment text itself: kana → Japanese (kana
 *       beats CJK because Japanese text is mostly CJK + kana), hangul → Korean,
 *       CJK ideographs → Chinese, Cyrillic → Russian, plus a conservative
 *       Latin diacritic heuristic (German/French/Italian/Portuguese; Spanish is
 *       detected through its unambiguous markers ñ/¿/¡).</li>
 *   <li>{@code null} → no translation for that segment (the original text is
 *       shown, which is the safe fallback).</li>
 * </ol>
 *
 * <p>Only <em>distinctive</em> diacritics decide Latin results: accents shared
 * by several languages (é, à, ü, á, …) are deliberately not scored, because a
 * single ambiguous accent must never pick a language and produce a wrong
 * translation.</p>
 *
 * <p>Returns only ML Kit language codes the translator supports.</p>
 */
public final class SourceLanguageResolver {
    private SourceLanguageResolver() {}

    /** Resolves the ML Kit source code for a segment. */
    public static String resolve(String text, String modelLanguageTag) {
        String fromModel = primaryCode(modelLanguageTag);
        if (fromModel != null) return fromModel;
        return detectFromText(text);
    }

    /**
     * Maps a BCP-47 {@code model_language} tag to an ML Kit code; {@code null}
     * for auto/blank/unsupported tags (falls through to script detection).
     */
    public static String primaryCode(String tag) {
        String primary = SubtitleTranslationTargets.primaryTag(tag);
        if (primary == null) return null;
        switch (primary) {
            case "en": case "es": case "fr": case "de": case "it": case "pt":
            case "ru": case "zh": case "ja": case "ko":
                return primary;
            default:
                return null;
        }
    }

    /**
     * Detects the language of a segment from its text alone. Non-Latin
     * scripts are authoritative; the Latin heuristic only fires on strong,
     * unambiguous evidence so a wrong-language translation never appears for
     * plain ASCII text.
     */
    public static String detectFromText(String text) {
        if (text == null || text.isEmpty()) return null;

        boolean hasKana = false, hasHangul = false, hasCjk = false, hasCyrillic = false;
        boolean sawLatinLetter = false;
        int de = 0, fr = 0, it = 0, pt = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);

            if (isKana(cp)) { hasKana = true; continue; }
            if (isHangul(cp)) { hasHangul = true; continue; }
            if (isCjkIdeograph(cp)) { hasCjk = true; continue; }
            if (isCyrillic(cp)) { hasCyrillic = true; continue; }

            if (!sawLatinLetter && (isAsciiLetter(cp) || isLatinSupplementLetter(cp))) {
                sawLatinLetter = true;
            }
            // Strong markers: unambiguous on their own.
            if (cp == 'ñ' || cp == 'Ñ' || cp == '¿' || cp == '¡') return "es";
            if (cp == 'ß') return "de";
            if (cp == 'œ' || cp == 'Œ') return "fr";

            // Distinctive diacritics only (é/à/ü/á are shared by several
            // languages and would cause wrong guesses).
            if (cp == 'ä' || cp == 'ö') de++;
            if (isFren(cp)) fr++;
            if (isItal(cp)) it++;
            if (isPort(cp)) pt++;
        }

        // Non-Latin scripts are authoritative. Kana beats CJK (Japanese text
        // is mostly CJK + kana); hangul beats CJK (Korean may embed hanja).
        if (hasKana) return "ja";
        if (hasHangul) return "ko";
        if (hasCjk) return "zh";
        if (hasCyrillic) return "ru";

        if (!sawLatinLetter) return null;

        int best = Math.max(Math.max(de, fr), Math.max(it, pt));
        if (best < 1) return null;
        int winners = 0;
        String winLang = null;
        if (de == best) { winners++; winLang = "de"; }
        if (fr == best) { winners++; winLang = "fr"; }
        if (it == best) { winners++; winLang = "it"; }
        if (pt == best) { winners++; winLang = "pt"; }
        return winners == 1 ? winLang : null;
    }

    private static boolean isAsciiLetter(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
    }

    private static boolean isLatinSupplementLetter(int cp) {
        return cp >= 0x00C0 && cp <= 0x024F;
    }

    private static boolean isKana(int cp) {
        return (cp >= 0x3040 && cp <= 0x309F) || (cp >= 0x30A0 && cp <= 0x30FF);
    }

    private static boolean isHangul(int cp) {
        return cp >= 0xAC00 && cp <= 0xD7AF;
    }

    private static boolean isCjkIdeograph(int cp) {
        return cp >= 0x4E00 && cp <= 0x9FFF;
    }

    private static boolean isCyrillic(int cp) {
        return cp >= 0x0400 && cp <= 0x04FF;
    }

    /** Distinctive French diacritics (é/è/à are shared with other languages). */
    private static boolean isFren(int cp) {
        return cp == 'â' || cp == 'ê' || cp == 'ë' || cp == 'î' || cp == 'ï'
                || cp == 'ô' || cp == 'û' || cp == 'ç';
    }

    /** Distinctive Italian diacritics (à/è/é are shared with French/Portuguese). */
    private static boolean isItal(int cp) {
        return cp == 'ì' || cp == 'ò' || cp == 'ù';
    }

    /** Distinctive Portuguese diacritics (á/â/ç/ê/ô are shared with others). */
    private static boolean isPort(int cp) {
        return cp == 'ã' || cp == 'õ';
    }
}
