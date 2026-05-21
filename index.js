// functions/index.js
// Deploy with: firebase deploy --only functions
//
// This Cloud Function receives a target FCM token + message from the app
// and forwards it via FCM Admin SDK.  The app never needs to hold a
// server key — only this function does.

const functions = require("firebase-functions");
const admin     = require("firebase-admin");
admin.initializeApp();

/**
 * Callable function: notifyPartner
 * Called from the Android app via FirebaseFunctions.getHttpsCallable("notifyPartner")
 *
 * Expected data: { token: string, title: string, body: string }
 */
exports.notifyPartner = functions.https.onCall(async (data, context) => {
  const { token, title, body } = data;

  if (!token || !title || !body) {
    throw new functions.https.HttpsError("invalid-argument", "token, title, body required");
  }

  const message = {
    token,
    notification: { title, body },
    android: {
      priority: "high",
      notification: { channelId: "fg_partner", sound: "default" }
    }
  };

  try {
    const response = await admin.messaging().send(message);
    return { success: true, messageId: response };
  } catch (err) {
    throw new functions.https.HttpsError("internal", err.message);
  }
});

/**
 * Scheduled function: daily digest
 * Every day at 8 AM UTC sends the partner a summary of the previous day
 * (number of blocks, unlock attempts).
 */
exports.dailyDigest = functions.pubsub
  .schedule("0 8 * * *")
  .timeZone("UTC")
  .onRun(async (_context) => {
    // In a real app you'd query Firestore for each user's partner token
    // and their previous day's stats, then send personalised digests.
    // Placeholder:
    console.log("Daily digest job ran.");
    return null;
  });
