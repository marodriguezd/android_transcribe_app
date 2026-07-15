# What's New in v0.7.0 🚀

🧠 **Smarter AI Post-Processing**
- **Enhanced Post-Processing Prompt**: The default post-processing prompt has been completely redesigned with a comprehensive system for speech repair, disfluency removal, inverse text normalization, and context-aware formatting. The new prompt intelligently handles filler words, self-corrections, numbered lists, developer jargon, and programmatic syntax while suppressing invalid input.
- **Improved Custom Words Description**: Updated the Custom Words section to better explain the fuzzy matching system — just add words and adjust the correction threshold, no need for manual replacements.

🛠 **Bug Fixes**
- **Fixed Double Download on First Launch**: Resolved an issue where the model download would restart when the foreground notification service started, causing duplicate downloads and confusing progress updates.
- **UI Thread Safety Fix**: Resolved an issue that prevented the UI from updating gracefully during background model downloads. Previously, you had to restart the app to see progress updates, but now everything updates dynamically and safely without freezing or crashing!

*(Note: If you've previously customized your AI prompt in the app settings, you might want to clear it so the app loads the new default template to enjoy these language-preservation benefits!)*
