# Changelog

Change log of **android_transcribe_app** (fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app)).

# v0.1.34

Refined and enhanced default AI post-processing prompt:

- **Enhanced Markdown Structure & Spacing:** Restructured the built-in system prompt with clear markdown headings, horizontal dividers, and clean vertical rhythm for maximum readability and LLM parsing accuracy.
- **Structured Rule Categorization:** Reorganized editing rules (oral clutter removal, on-the-fly self-correction resolution, phonetic reconstruction, language consistency, dictation commands, and technical casing preservation) with explicit examples and formatting tags.
- **Atomic Technical Token Handling:** Explicit guidelines for preserving `camelCase`, `PascalCase`, `snake_case`, `kebab-case`, `SCREAMING_SNAKE_CASE`, CLI flags (`--force`), URLs, and code identifiers.
- **Strict Output Constraints:** Formatted constraints ensuring zero conversational filler, greetings, or unwanted markdown code block wrapping in model responses.
