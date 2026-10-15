import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.nexus.deadlines",
  appName: "Nexus",
  webDir: "dist",
  android: { allowMixedContent: false },
};

export default config;
