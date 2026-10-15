export interface Task {
  text: string;
  done: boolean;
}

export interface Item {
  id: string;
  url: string;
  title: string;
  deadline: string | null; // YYYY-MM-DD
  tasks: Task[];
  addedAt: number;
  state: "loading" | "ok" | "error";
  error?: string;
}

export interface Settings {
  apiKey: string;
  model: string;
}
