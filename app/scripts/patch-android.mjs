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
