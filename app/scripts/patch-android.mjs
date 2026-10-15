// Дополняет сгенерированный `cap add android` манифест:
// приём «Поделиться» (send-intent) и разрешение на уведомления.
import { readFileSync, writeFileSync } from "node:fs";

const path = "android/app/src/main/AndroidManifest.xml";
let xml = readFileSync(path, "utf8");

const shareActivity = `
        <activity
            android:name="de.mindlib.sendIntent.SendIntentActivity"
            android:label="@string/app_name"
            android:exported="true"
            android:theme="@style/AppTheme.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>
`;

if (!xml.includes("SendIntentActivity")) {
  xml = xml.replace("</application>", `${shareActivity}    </application>`);
}

const perm = '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />';
if (!xml.includes("POST_NOTIFICATIONS")) {
  xml = xml.replace("</manifest>", `    ${perm}\n</manifest>`);
}

writeFileSync(path, xml);
console.log("AndroidManifest.xml patched");

// send-intent собран с compileSdk 35, а androidx.core 1.17 требует 36.
// Надёжнее всего поправить сам модуль плагина в node_modules (gradle берёт его оттуда).
const pluginGradlePath = "node_modules/send-intent/android/build.gradle";
const pluginGradle = readFileSync(pluginGradlePath, "utf8");
const fixed = pluginGradle
  .replace(/compileSdkVersion\s+35/, "compileSdkVersion 36")
  .replace(/targetSdkVersion\s+35/, "targetSdkVersion 36");
if (fixed === pluginGradle && !/compileSdkVersion\s+36/.test(pluginGradle)) {
  throw new Error("send-intent: не нашёл compileSdkVersion в build.gradle, патч не применён");
}
writeFileSync(pluginGradlePath, fixed);
console.log("send-intent build.gradle patched");

// Страховка: поднимаем compileSdk у всех библиотечных модулей, где он ниже.
const gradlePath = "android/build.gradle";
let gradle = readFileSync(gradlePath, "utf8");
const marker = "// nexus: raise compileSdk";
if (!gradle.includes(marker)) {
  gradle += `
${marker}
subprojects {
    afterEvaluate { p ->
        if (p.plugins.hasPlugin('com.android.library')) {
            def cur = p.android.compileSdkVersion
            def num = cur ? cur.toString().replace('android-', '') : '0'
            if (num.isInteger() && num.toInteger() < 36) {
                p.android.compileSdkVersion 36
            }
        }
    }
}
`;
  writeFileSync(gradlePath, gradle);
}
console.log("build.gradle patched");
