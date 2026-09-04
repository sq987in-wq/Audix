# Candela — Two-device physical test

Install the same debug APK on two phones (API 26+). No network, Bluetooth, or Wi‑Fi Direct is used.

APK (after `./gradlew :app:assembleDebug`):

`app/build/outputs/apk/debug/app-debug.apk`

## 1. Install

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Repeat on the second device. Launch **Candela**.

## 2. Place the phones

- Sender screen facing receiver camera, 15–40 cm, angle under 20°.
- Both phones on a desk or elbows planted. Indoor / indirect light.
- Clean glass. No privacy film. No direct sun on the sender.

## 3. Sender

1. Tap **Send a file**.
2. Tap **Choose file** and pick a file ≤ 1 MB (100–500 KB recommended).
3. Leave density on **standard** (160 B / 160 ms) unless the link is poor (use **robust**).
4. Tap **Prepare**. A QR appears. An 8-digit SAS is shown.
5. Do **not** tap **SAS matches — start data** until the receiver shows the same digits.

## 4. Receiver

1. Tap **Receive**.
2. Allow **Camera**. On API 28 and below also allow storage if prompted.
3. Tap **Open camera** if preview is dark.
4. Point at the sender QR. Wait for AE/AF lock (hint: “AE/AF locked”).
5. When the 8-digit SAS appears, **read it aloud** and compare with the sender.
6. If they match, tap **SAS matches — receive data** on **both** phones (receiver first is fine; sender confirmation starts the data plane).

## 5. Transfer

- Sender HUD: `SENDING` and `symbol i / n`.
- Receiver HUD: recovered `k` count climbs. Gate meters show blur / contrast.
- On SHA-256 match the receiver writes to **Downloads** via MediaStore. No file is written on mismatch.

## 6. Pass criteria

- SAS digits identical on both screens.
- Receiver state `COMPLETE`.
- File in Downloads, same name, same bytes (compare SHA-256 with any file manager).
- Abort / Back never leaves a partial file.

## 7. If it fails

- Contrast floor message → shade the screen or remove privacy film.
- Gate closed / blur → hold still, shorten distance.
- Thermal severe/critical → wait, then resume with **robust**.
- Camera denied → grant Camera in system settings.
