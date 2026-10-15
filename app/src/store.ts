import type { Item, Settings } from "./types";

const ITEMS_KEY = "nexus.items";
const SETTINGS_KEY = "nexus.settings";

export const DEFAULT_MODEL = "gemini-2.5-flash";

function read<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}

function write(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // хранилище недоступно — работаем только в памяти
  }
}

export const loadItems = (): Item[] => read<Item[]>(ITEMS_KEY, []);
export const saveItems = (items: Item[]): void => write(ITEMS_KEY, items);

export const loadSettings = (): Settings => ({
  apiKey: "",
  model: DEFAULT_MODEL,
  ...read<Partial<Settings>>(SETTINGS_KEY, {}),
});
export const saveSettings = (s: Settings): void => write(SETTINGS_KEY, s);
