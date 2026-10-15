import { Capacitor } from "@capacitor/core";
import { App } from "@capacitor/app";
import { SendIntent } from "send-intent";

const URL_RE = /https?:\/\/[^\s"'<>]+/i;

export function findUrl(text: string | undefined | null): string | null {
  const m = text?.match(URL_RE);
  return m ? m[0] : null;
}

async function check(onUrl: (url: string) => void): Promise<void> {
  try {
    const res = await SendIntent.checkSendIntentReceived();
    const url = findUrl(res?.url) ?? findUrl(res?.description) ?? findUrl(res?.title);
    if (url) {
      onUrl(url);
      SendIntent.finish();
    }
  } catch {
    /* нет входящего intent */
  }
}

/** Подписывается на «Поделиться» из других приложений (только Android). */
export function listenForShares(onUrl: (url: string) => void): void {
  if (!Capacitor.isNativePlatform()) return;
  void check(onUrl);
  window.addEventListener("sendIntentReceived", () => void check(onUrl));
  void App.addListener("appStateChange", (s) => {
    if (s.isActive) void check(onUrl);
  });
}
