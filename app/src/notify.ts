import { Capacitor } from "@capacitor/core";
import { LocalNotifications } from "@capacitor/local-notifications";
import type { Item } from "./types";

const DAYS_BEFORE = [7, 3, 1, 0];
const HOUR = 9;

function dueDate(deadline: string, daysBefore: number): Date {
  const [y, m, d] = deadline.split("-").map(Number);
  return new Date(y, m - 1, d - daysBefore, HOUR, 0, 0);
}

function isFinished(it: Item): boolean {
  return it.tasks.length > 0 && it.tasks.every((t) => t.done);
}

export async function requestPermission(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  try {
    await LocalNotifications.requestPermissions();
  } catch {
    /* пользователь отказал: напоминаний не будет */
  }
}

/** Пересоздаёт все напоминания по текущему списку. */
export async function syncReminders(items: Item[]): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  try {
    const pending = await LocalNotifications.getPending();
    if (pending.notifications.length) {
      await LocalNotifications.cancel({ notifications: pending.notifications });
    }

    const now = Date.now();
    const notifications: { id: number; title: string; body: string; schedule: { at: Date } }[] = [];
    let id = 1;
    for (const it of items) {
      if (!it.deadline || it.state !== "ok" || isFinished(it)) continue;
      for (const n of DAYS_BEFORE) {
        const at = dueDate(it.deadline, n);
        if (at.getTime() <= now) continue;
        notifications.push({
          id: id++,
          title: n === 0 ? "Дедлайн сегодня" : `Дедлайн через ${n} дн.`,
          body: it.title,
          schedule: { at },
        });
      }
    }
    if (notifications.length) await LocalNotifications.schedule({ notifications });
  } catch {
    /* без разрешения на уведомления приложение работает как обычно */
  }
}
