# Privacy Policy — LingoTrail

**Last updated: 15 November 2026**

LingoTrail ("the app") is an open-source Android application maintained by SF-Zhou
(the "developer"). This policy explains what the app does with your data.

## Summary

- LingoTrail has **no accounts, no analytics, no advertising, and no tracking**.
- Your photos and history stay **on your device** until you ask the app to translate a scan. At that
  moment the photo and the recognised text are sent **directly from your device to the DeepSeek API
  endpoint you configured** (by default `https://api.deepseek.com`).
- Your **DeepSeek API key is stored only in the app's private storage** and is never sent anywhere
  except to the API endpoint as the `Authorization` header of requests you trigger.
- The developer does not operate a server and does not receive your photos, text, or API key.

## Information the app does not collect

The app does not collect or transmit:

- names, email addresses, phone numbers, or any account information;
- location data;
- device identifiers for advertising or analytics;
- usage analytics, crash reports, or behavioural tracking;
- contacts, calendar, SMS, or other personal data.

The app contains no third-party analytics, advertising, or crash-reporting SDKs.

## Photos and text

Photos you take or import are stored in the app's private storage on your device. They are **not**
uploaded anywhere until you request a translation for a scan.

When you translate a scan:

1. The app sends the photo to the DeepSeek OCR API (`/v1/tools/ocr`) to recognise the text.
2. The recognised text is sent to the DeepSeek chat API (`/chat/completions`) to be translated into
   your target language.

These requests go directly from your device to the API endpoint configured in the app; the developer
of LingoTrail is not an intermediary and does not receive a copy. DeepSeek processes the data
according to its own terms and privacy policy, which you should review on the
[DeepSeek Platform](https://platform.deepseek.com). Scanning, translation, and API usage may be
subject to DeepSeek's fees.

If you change the **API endpoint** in the app's settings (for example to a proxy or a self-hosted
gateway), your photo, text, and API key are sent to that endpoint instead, and that provider's
policies apply.

## API key

The DeepSeek API key you enter is stored using Android's app-private `SharedPreferences` storage
(mode `MODE_PRIVATE`). It is:

- not included in Android auto-backup — the app sets `android:allowBackup="false"`;
- not sent to the developer or to any party other than the configured API endpoint;
- never shown in history, logs, or shared images.

Removing the key in Settings, clearing the app's data, or uninstalling the app removes it from the
device.

## History, photos, and deletion

Scan history (original photos, thumbnails, and translation data) is stored in the app's private files
directory. There is no cloud sync.

- Delete an individual scan from its viewer or the history list.
- Use **Settings → Clear history** to delete every scan and translation at once.
- Uninstalling the app removes all data the app stored.

## Permissions and network access

The app requests only:

- `INTERNET` and `ACCESS_NETWORK_STATE` — to call the translation API.

Photos are taken with, or picked through, the system camera and photo-picker interfaces, so the app
does not need the `CAMERA` or storage permissions. Sharing a composited image uses Android's share
sheet; the image is passed only to the app you choose.

## Children's privacy

LingoTrail is not directed at children under 13 and does not knowingly collect personal information
from anyone. Because the app has no accounts and no analytics, it does not collect personal
information from children.

## Data security

Requests to the DeepSeek API use HTTPS. The API key is kept in app-private storage protected by the
Android application sandbox. No method of transmission or storage is 100% secure, but the app is
designed so that your data stays under your control and on your device.

## Changes to this policy

If this policy changes, the updated version will be published in this repository and the "Last
updated" date above will change. Significant changes will be noted in the project's
[CHANGELOG](../CHANGELOG.md).

## Contact

This is an open-source project without a dedicated privacy contact. For privacy questions or
concerns, open an issue at
<https://github.com/SF-Zhou/LingoTrail/issues> and mention that it is about privacy. Do not include
your API key, photos, or other sensitive data in an issue.
