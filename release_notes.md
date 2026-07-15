# What's New in v0.7.0 🚀

🧠 **Smarter AI Post-Processing**
- **Enhanced Dictionary & Processing Prompt**: Completely redesigned the AI post-processing prompt with a comprehensive system for speech repair, disfluency removal, inverse text normalization, and context-aware formatting. The new prompt intelligently handles filler words, self-corrections, numbered lists, developer jargon, and programmatic syntax while suppressing invalid input.

🛠 **Under the Hood Fixes**
- **UI Thread Safety Fix**: Resolved an issue that prevented the UI from updating gracefully during background model downloads. Previously, you had to restart the app to see progress updates, but now everything updates dynamically and safely without freezing or crashing!
- **Model Selection Overhaul**: To streamline the experience while we build our new cutting-edge C++/Rust inference engine for Canary, we have temporarily hidden the "Precise" model option. The app now defaults directly to our highly efficient "Fast" model (Parakeet 0.6B) right out of the box.

*(Note: If you've previously customized your AI prompt in the app settings, you might want to clear it so the app loads the new default template to enjoy these language-preservation benefits!)*
