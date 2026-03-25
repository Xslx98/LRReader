# Privacy Policy — LR Reader

**Effective Date:** March 25, 2026

LR Reader ("the App") is an open-source Android client for LANraragi, a self-hosted manga/archive management server. This privacy policy explains how the App handles your data.

## Data Collection

**LR Reader does not collect, transmit, or store any personal data on external servers.**

All data remains on your device or on the LANraragi server that you configure.

## Data Stored Locally

The App stores the following data **only on your device**:

- **Server connection details** (URL, API key) — encrypted via Android EncryptedSharedPreferences
- **Reading history and preferences** — stored in a local SQLite database
- **Cached images** — temporary files for performance, automatically cleaned up
- **Avatar and background images** — user-customized profile images

## Network Communication

The App communicates **only** with the LANraragi server(s) you explicitly configure. No data is sent to any third-party services, analytics platforms, or advertising networks.

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Connect to your LANraragi server |
| `CAMERA` | Take photos for avatar (optional, user-initiated) |
| `FOREGROUND_SERVICE` | Download archives in background |

## Third-Party Services

LR Reader does **not** integrate any third-party analytics, crash reporting, or advertising SDKs.

## Children's Privacy

The App is not directed at children under the age of 13. We do not knowingly collect information from children.

## Open Source

LR Reader is open-source software licensed under the GNU General Public License v3.0 (GPLv3).
Source code: [https://github.com/xiaojieonly/Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ)

## Changes to This Policy

Any changes will be reflected in this document with an updated effective date.

## Contact

For privacy-related questions, please open an issue on the project's GitHub repository.
