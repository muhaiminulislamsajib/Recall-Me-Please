# Firebase Setup Guide for Recall Me Please

To enable cloud synchronization, user accounts, and real-time cross-device memory backups, follow these steps to connect your Firebase project:

### 1. Create a Firebase Project
1. Go to the [Firebase Console](https://console.firebase.google.com/).
2. Click **Add project** and name it `Recall Me Please`.
3. Disable Google Analytics (optional) or leave it enabled, then click **Create project**.

### 2. Register Android App
1. In project settings, click **Add app** and choose the **Android** icon.
2. Package name: `com.aistudio.recallmeplease.rmpm`
3. App nickname: `Recall Me Please`
4. Click **Register app**.

### 3. Download and Add `google-services.json`
1. Download `google-services.json` from the Firebase Console.
2. Place `google-services.json` into the `app/` folder of this project (`app/google-services.json`).

### 4. Enable Firebase Authentication
1. In the Firebase console left navigation, open **Authentication** -> **Get Started**.
2. Under the **Sign-in method** tab, enable **Email/Password**.
3. Save the changes.

### 5. Enable Cloud Firestore
1. In the Firebase console left navigation, click **Firestore Database** -> **Create database**.
2. Select your preferred server location and start in **Production mode**.
3. In the **Rules** tab, paste the contents of `firestore.rules` located in the root of this project:
```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{userId}/memories/{memoryId} {
      allow read, write, delete: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```
4. Click **Publish**.

### 6. Gemini API Key Configuration
Configure your Gemini API key in the **Secrets panel** in Google AI Studio or directly in `.env`:
```
GEMINI_API_KEY=your_gemini_api_key_here
```

Your cloud memory sync and continuous AI memory assistant are now fully operational!
