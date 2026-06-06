# Privacy Policy

**Last Updated: June 6, 2026**

This Privacy Policy explains how the **Pzync Android Application** ("we," "our," or "us") handles, processes, and protects your information.

We take your privacy seriously. The Pzync Android app is designed as a local-only peer-to-peer (P2P) synchronization utility. All core data transmissions occur entirely within your own local private network directly between the app and your paired desktop client.

---

## 1. Information We Collect and Process

### A. Locally Transmitted Data
To provide syncing capabilities, the app processes and transmits the following types of data directly to your paired desktop computer over your local Wi-Fi or Ethernet network. This data is **never** sent to or stored on any remote servers:
- **Files**: Selected files are transferred directly to your paired desktop. We do not inspect, cache, or store the contents of your files.
- **Clipboard Content**: System clipboard text is transferred to your paired desktop when you trigger clipboard synchronization.
- **System Commands**: If terminal access is enabled, terminal inputs and command results are transmitted directly to your paired desktop.
- **Media Playback and Volume Status**: Media metadata (like track titles) and volume status are synchronized locally to allow control.

### B. Device Information
We process basic device identifiers (e.g., device name, operating system type, and a randomly generated unique device ID) locally on your private network for device discovery, identification, and pairing purposes.

### C. Secure Local Storage
We store pairing authorization tokens and trusted device details on your Android device. This information is secured using Android's Keystore-backed encryption (`EncryptedSharedPreferences` / AES-256) to prevent access by other applications on the device.

---

## 2. Third-Party Services and Telemetry

We do not sell, trade, or rent your personal information. To help us monitor stability and improve reliability, the app uses:

### Google Firebase Analytics & Crashlytics
- **Purpose**: To collect anonymous diagnostic information, crash logs, and basic usage telemetry (e.g., app launches, button interactions).
- **Data Collected**: Non-personally identifiable information, such as device model, OS version, and crash stack traces.
- **Privacy Policy**: You can read more about Google's privacy practices by visiting [Google's Privacy & Terms Page](https://policies.google.com/privacy).

---

## 3. Data Security

Since the app transmits data directly over your local network:
- Pair keys are protected on-device via Android's secure Keystore.
- All file transfers, clipboard syncs, and terminal data remain within your local network firewall.
- **Important**: Because transmissions occur over your local network, you should ensure that your local network (e.g., home Wi-Fi) is secured with a strong password.

---

## 4. Children's Privacy

Our application does not target and is not intended for anyone under the age of 13. We do not knowingly collect personally identifiable information from children.

---

## 5. Changes to This Privacy Policy

We may update our Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page and updating the "Last Updated" date at the top.

---

## 6. Contact Us

If you have any questions or suggestions about this Privacy Policy, please contact us at:
- **Email**: support@pzync.example.com
