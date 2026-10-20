# Immich Cloud Photos

An Android Cloud Media Provider that integrates your self-hosted [Immich](https://immich.app/) server with the system photo picker. Browse and select your Immich photos and videos directly from any app that uses Android's built-in photo picker.

<img height="720" alt="image" src="https://github.com/user-attachments/assets/df0bb0b6-d024-4592-99da-a2624c4cb46b" />

## Installation

[<img src="https://github.com/ImranR98/Obtainium/raw/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="56">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22codes.dreaming.cloudmedia%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FDreaming-Codes%2Fimmich-cloud-media%22%2C%22author%22%3A%22Dreaming-Codes%22%2C%22name%22%3A%22Immich%20Cloud%20Photos%22%2C%22additionalSettings%22%3A%22%7B%5C%22about%5C%22%3A%5C%22Cloud%20Media%20Provider%20for%20Immich%5C%22%7D%22%7D)

Or download the latest APK from the [Releases](https://github.com/Dreaming-Codes/immich-cloud-media/releases) page.

## Requirements

- Android 14+ (API 34)
- An [Immich](https://immich.app/) server instance

## Setup

1. Install the app and log in with your Immich server URL and credentials (email/password or API key). If you use an API key, give it the [six permissions below](#an-api-key-needs-six-permissions).
2. Enable the cloud media provider via ADB. The app shows the right command for your Android version, ready to copy, and can also run it through Shizuku.

```sh
# Android 15 and later
adb shell device_config override mediaprovider allowed_cloud_providers codes.dreaming.cloudmedia

# Android 14
adb shell device_config put mediaprovider allowed_cloud_providers codes.dreaming.cloudmedia
```

For the debug build, use `codes.dreaming.cloudmedia.debug`. Android 14 has no `override` command. A server flag sync can reset a value set with `put`, so run it again if Immich disappears from the picker. If the picker does not show Immich right away, reboot the phone.

3. Open any app's photo picker. Your Immich library appears as a cloud source.

## An API key needs six permissions

If you log in with an API key, give the key these permissions in Immich under **Account Settings** > **API Keys**. Email/password and SSO logins do not use them. Endpoint paths are relative to your server's `/api`.

| Permission | Endpoints the app calls | Used for |
| --- | --- | --- |
| `user.read` | `GET /users/me` | Checking the key at login and showing the account name |
| `album.read` | `GET /albums` | Listing albums |
| `asset.read` | `GET /timeline/buckets`, `POST /search/metadata`, `POST /search/smart` | Listing the timeline, album contents, and search results, and detecting new and deleted photos |
| `asset.view` | `GET /assets/{id}/thumbnail`, `GET /assets/{id}/video/playback` | Thumbnails, previews, and video playback |
| `asset.download` | `GET /assets/{id}/original` | Full-resolution files |
| `person.read` | `GET /people`, `GET /people/{id}/thumbnail` | People and their face thumbnails |

A key with `all` also works. The key does not need `sync.stream`. With an API key, the app checks `GET /timeline/buckets` for changes and never calls the sync endpoints, because Immich 3 rejects API keys there.

## Features

- Browse all photos and videos from your Immich server in the system photo picker
- Album support with full asset browsing
- People/face recognition categories
- Smart search across your library
- Video playback with streaming support
- Thumbnail previews and full-resolution downloads on demand

## Building from source

```sh
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

Requires JDK 17.

## License

This project is not affiliated with or endorsed by Immich.
