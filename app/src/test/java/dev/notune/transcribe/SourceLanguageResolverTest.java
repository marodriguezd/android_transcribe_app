package dev.notune.transcribe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SourceLanguageResolverTest {

    // --- Script detection ---------------------------------------------------

    @Test
    public void chineseIdeographsDetectZh() {
        assertEquals("zh", SourceLanguageResolver.detectFromText("今天我们学习中文。"));
    }

    @Test
    public void mixedCjkWithLatinStillDetectsZh() {
        assertEquals("zh", SourceLanguageResolver.detectFromText("Red Note 的评论区"));
    }

    @Test
    public void japaneseKanaDetectsJa() {
        assertEquals("ja", SourceLanguageResolver.detectFromText("今日は日本語を勉強します。"));
    }

    @Test
    public void koreanHangulDetectsKo() {
        assertEquals("ko", SourceLanguageResolver.detectFromText("오늘은 한국어를 공부합니다."));
    }

    @Test
    public void cyrillicDetectsRu() {
        assertEquals("ru", SourceLanguageResolver.detectFromText("Сегодня мы учим русский язык."));
    }

    // --- Latin heuristic ----------------------------------------------------

    @Test
    public void spanishTildeDetectsEs() {
        assertEquals("es", SourceLanguageResolver.detectFromText("mañana niño"));
    }

    @Test
    public void spanishInvertedMarksDetectEs() {
        assertEquals("es", SourceLanguageResolver.detectFromText("¿Qué tal estás?"));
    }

    @Test
    public void germanSharpSDDetectsDe() {
        assertEquals("de", SourceLanguageResolver.detectFromText("Straße Fußball"));
    }

    @Test
    public void germanUmlautsDetectDe() {
        assertEquals("de", SourceLanguageResolver.detectFromText("über morgen Mädchen grüße"));
    }

    @Test
    public void frenchAccentsDetectFr() {
        // ê is a distinctive French diacritic (é/è/à are shared with Italian
        // and Portuguese and deliberately never decide on their own).
        assertEquals("fr", SourceLanguageResolver.detectFromText("L'être est dans la forêt"));
    }

    @Test
    public void italianDistinctiveAccentsDetectIt() {
        assertEquals("it", SourceLanguageResolver.detectFromText("più o meno"));
    }

    @Test
    public void portugueseTildeDetectsPt() {
        assertEquals("pt", SourceLanguageResolver.detectFromText("não são pão coração"));
    }

    @Test
    public void plainAsciiTextReturnsNull() {
        assertNull(SourceLanguageResolver.detectFromText("this is plain english text"));
    }

    @Test
    public void singleAmbiguousAccentReturnsNull() {
        // "café" — the é is shared by fr/it/pt; one occurrence must not decide.
        assertNull(SourceLanguageResolver.detectFromText("café"));
    }

    @Test
    public void emptyTextReturnsNull() {
        assertNull(SourceLanguageResolver.detectFromText(""));
        assertNull(SourceLanguageResolver.detectFromText(null));
    }

    @Test
    public void punctuationOnlyReturnsNull() {
        assertNull(SourceLanguageResolver.detectFromText("…"));
    }

    // --- model_language precedence ------------------------------------------

    @Test
    public void fixedModelLanguageOverridesDetection() {
        // Chinese speech with a fixed es-ES hint: the user says the audio is
        // Spanish, so the translator uses es regardless of the text's script.
        assertEquals("es", SourceLanguageResolver.resolve("今天我们学习中文。", "es-ES"));
    }

    @Test
    public void autoModelLanguageFallsBackToDetection() {
        assertEquals("zh", SourceLanguageResolver.resolve("今天我们学习中文。", "auto"));
        assertEquals("zh", SourceLanguageResolver.resolve("今天我们学习中文。", ""));
        assertEquals("zh", SourceLanguageResolver.resolve("今天我们学习中文。", null));
    }

    @Test
    public void unsupportedModelLanguageFallsBackToDetection() {
        assertEquals("zh", SourceLanguageResolver.resolve("今天我们学习中文。", "xx-XX"));
    }

    @Test
    public void autoModelLanguageWithLatinTextReturnsNull() {
        assertNull(SourceLanguageResolver.resolve("this is plain english text", "auto"));
    }

    // --- primaryCode mapping ------------------------------------------------

    @Test
    public void primaryCodeMapsSupportedTags() {
        assertEquals("en", SourceLanguageResolver.primaryCode("en-US"));
        assertEquals("es", SourceLanguageResolver.primaryCode("es-ES"));
        assertEquals("zh", SourceLanguageResolver.primaryCode("zh-CN"));
        assertEquals("pt", SourceLanguageResolver.primaryCode("pt-PT"));
    }

    @Test
    public void primaryCodeNullForAutoOrBlank() {
        assertNull(SourceLanguageResolver.primaryCode("auto"));
        assertNull(SourceLanguageResolver.primaryCode(""));
        assertNull(SourceLanguageResolver.primaryCode(null));
        assertNull(SourceLanguageResolver.primaryCode("  "));
    }

    @Test
    public void primaryCodeNullForUnsupported() {
        assertNull(SourceLanguageResolver.primaryCode("xx-XX"));
    }
}
