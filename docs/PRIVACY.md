# Privacy

Video Editor works without an account. It does not request INTERNET permission and has no analytics SDK, advertising SDK, or AI service. The app does not send media, project names, recordings, or editing activity to a server.

Imported photos, videos, and audio are opened through Android's privacy-preserving picker or document-provider grants. The app requests microphone access when you choose to record a voiceover. Camera capture uses the installed Android camera application and an app-owned output URI. Denying microphone access leaves ordinary editing and audio import available.

Project documents, preferences, generated caches, camera captures, voiceovers, and rendered exports are stored locally. Deleting a project retains its imported source media. Cache clearing removes generated data, while temporary-file cleanup retains projects, source media, and completed exports.

Saving to Photos, choosing a Files provider, sharing a video, or sharing diagnostic details is an explicit action. Android backup, gallery synchronization, cloud document providers, and receiving apps follow their own policies. Android system backup is enabled for the application's local data; provider permissions and external media availability may require relinking after restoration.

Diagnostic sharing opens Android's chooser with app version, Android version, device manufacturer/model, and editable reproduction prompts. It does not automatically send a message or include project names, paths, or media. No feedback recipient is hard-coded.

Release verification keeps recordings, screenshots, MP4s, local test-signed APKs
and backups outside the source repository. Test fixtures remove only their owned
projects/media. No application-data clear or destructive repository reset was
used in the resumed verification.

The verified merged release manifest includes dependency-provided
ACCESS_NETWORK_STATE permission. This permits inspecting connection state; it
does not grant INTERNET access. The legacy WRITE_EXTERNAL_STORAGE declaration
is limited to Android API 28 and below for gallery publication; modern imports
use scoped provider grants. The release bundle is unsigned, and emulator APK
verification uses a local debug certificate rather than production credentials.
