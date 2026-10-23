import { reactive } from "vue";

type DialogType = "alert" | "confirm";

interface DialogState {
  open: boolean;
  type: DialogType;
  title: string;
  message: string;
  resolve: ((ok: boolean) => void) | null;
}

export const dialog = reactive<DialogState>({
  open: false,
  type: "alert",
  title: "",
  message: "",
  resolve: null,
});

function open(type: DialogType, title: string, message: string): Promise<boolean> {
  // 同时只允许一个对话框，旧的按取消处理
  dialog.resolve?.(false);
  dialog.type = type;
  dialog.title = title;
  dialog.message = message;
  dialog.open = true;
  return new Promise<boolean>((resolve) => {
    dialog.resolve = resolve;
  });
}

export const alertDialog = (title: string, message: string) => open("alert", title, message).then(() => undefined);

export const confirmDialog = (title: string, message: string) => open("confirm", title, message);

export function settle(result: boolean) {
  const r = dialog.resolve;
  dialog.resolve = null;
  dialog.open = false;
  r?.(result);
}
