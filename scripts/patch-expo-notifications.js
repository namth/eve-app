const fs = require('fs');
const path = require('path');

const baseDir = path.join(__dirname, '../node_modules/expo-notifications/build');

function patchFile(relativePath, search, replace) {
  const filePath = path.join(baseDir, relativePath);
  if (fs.existsSync(filePath)) {
    let content = fs.readFileSync(filePath, 'utf8');
    if (content.includes(search)) {
      content = content.replace(search, replace);
      fs.writeFileSync(filePath, content, 'utf8');
      console.log(`[patch] Patched ${relativePath}`);
    }
  }
}

// 1. Patch warnOfExpoGoPushUsage so it does not throw on Android Expo Go
patchFile(
  'warnOfExpoGoPushUsage.js',
  'throw new Error(message);',
  'didWarn = true;\n            console.warn(message);'
);

// 2. Patch TopicSubscriptionModule.android.js to use requireOptionalNativeModule
const topicSubPath = path.join(baseDir, 'TopicSubscriptionModule.android.js');
if (fs.existsSync(topicSubPath)) {
  const content = `import { requireOptionalNativeModule } from 'expo-modules-core';
const nativeModule = requireOptionalNativeModule('ExpoTopicSubscriptionModule');
export default nativeModule ?? {
    addListener: () => {},
    removeListeners: () => {},
    subscribeToTopicAsync: () => Promise.resolve(null),
    unsubscribeFromTopicAsync: () => Promise.resolve(null),
};
`;
  fs.writeFileSync(topicSubPath, content, 'utf8');
  console.log('[patch] Patched TopicSubscriptionModule.android.js');
}

// 3. Patch PushTokenManager.native.js to use requireOptionalNativeModule
const pushTokenPath = path.join(baseDir, 'PushTokenManager.native.js');
if (fs.existsSync(pushTokenPath)) {
  const content = `import { requireOptionalNativeModule } from 'expo-modules-core';
const nativeModule = requireOptionalNativeModule('ExpoPushTokenManager');
export default nativeModule ?? {
    addListener: () => ({ remove: () => {} }),
    removeListener: () => {},
    removeAllListeners: () => {},
    emit: () => {},
    listenerCount: () => 0,
};
`;
  fs.writeFileSync(pushTokenPath, content, 'utf8');
  console.log('[patch] Patched PushTokenManager.native.js');
}

// 4. Patch ServerRegistrationModule.native.js to use requireOptionalNativeModule
const serverRegPath = path.join(baseDir, 'ServerRegistrationModule.native.js');
if (fs.existsSync(serverRegPath)) {
  const content = `import { requireOptionalNativeModule } from 'expo-modules-core';
const nativeModule = requireOptionalNativeModule('NotificationsServerRegistrationModule');
export default nativeModule ?? {
    addListener: () => {},
    removeListeners: () => {},
};
`;
  fs.writeFileSync(serverRegPath, content, 'utf8');
  console.log('[patch] Patched ServerRegistrationModule.native.js');
}

// 5. Patch BackgroundNotificationTasksModule.native.js to use requireOptionalNativeModule
const bgTasksPath = path.join(baseDir, 'BackgroundNotificationTasksModule.native.js');
if (fs.existsSync(bgTasksPath)) {
  const content = `import { requireOptionalNativeModule } from 'expo-modules-core';
const nativeModule = requireOptionalNativeModule('ExpoBackgroundNotificationTasksModule');
export default nativeModule ?? {
    async registerTaskAsync(taskName) { return null; },
    async unregisterTaskAsync(taskName) { return null; },
};
`;
  fs.writeFileSync(bgTasksPath, content, 'utf8');
  console.log('[patch] Patched BackgroundNotificationTasksModule.native.js');
}

console.log('[postinstall] All expo-notifications patches applied successfully.');

// 6. Patch NotificationCategoriesModule.native.js to use requireOptionalNativeModule
const catModulePath = path.join(baseDir, 'NotificationCategoriesModule.native.js');
if (fs.existsSync(catModulePath)) {
  const content = `import { requireOptionalNativeModule } from 'expo-modules-core';
const nativeModule = requireOptionalNativeModule('ExpoNotificationCategoriesModule');
export default nativeModule ?? {
    async getNotificationCategoriesAsync() { return []; },
    async setNotificationCategoryAsync() { return []; },
    async deleteNotificationCategoryAsync() { return false; },
    addListener() {},
    removeListeners() {},
};
`;
  fs.writeFileSync(catModulePath, content, 'utf8');
  console.log('[patch] Patched NotificationCategoriesModule.native.js');
}
