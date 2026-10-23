<template>
  <div class="settings-text-container">
    <!-- 离线提示：后端连不上时一直挂在这里（规格 B4-BE §4.3） -->
    <div v-if="offline" class="offline-tip">离线，改动不会同步</div>

    <MenuPage>
      <MenuItem v-for="category in categories" :key="category.id" :title="category.label">
        <template #header>
          <component :is="iconOf(category.id)" :size="20" />
        </template>

        <div class="flex w-full flex-col gap-5">
          <!-- 第二层：子分类（原版 Category → Subcategory → settings） -->
          <section v-for="sub in category.subcategories" :key="sub.id" class="flex w-full flex-col">
            <h5 class="text-brand mb-3 text-base font-bold">{{ sub.label }}</h5>

            <div v-for="setting in sub.settings" :key="setting.key" class="mb-6 last:mb-0">
              <div class="flex items-center gap-2">
                <span class="text-brand inline-flex items-center font-medium">
                  {{ setting.label }}
                </span>
                <span
                  v-if="setting.extension"
                  class="border-brand/50 text-brand shrink-0 rounded-full border px-1.5 text-[0.65rem]"
                  >本项目扩展</span
                >
                <span class="ml-auto shrink-0 text-xs text-white/35">{{ setting.key }}</span>
              </div>
              <p v-if="setting.description" class="mt-1 mb-2 text-sm text-gray-300">
                {{ setting.description }}
              </p>

              <!-- number → 滑块（范围取 min/max） -->
              <div v-if="isNumber(setting)" class="b2-slider">
                <span class="b2-bound">{{ setting.min ?? 0 }}</span>
                <input
                  class="b2-range"
                  type="range"
                  :min="setting.min ?? 0"
                  :max="setting.max ?? 100"
                  :step="stepOf(setting)"
                  :value="values[setting.key]"
                  :aria-label="setting.label"
                  @input="onNumberInput(setting.key, $event)"
                  @change="commit(setting.key)"
                />
                <span class="b2-bound">{{ setting.max ?? 100 }}</span>
                <span class="b2-value">{{ values[setting.key] }}</span>
              </div>

              <!-- bool → 开关（复用原版 base/widget/Toggle） -->
              <div v-else-if="setting.type === 'bool'" class="flex items-baseline px-1 py-2.5">
                <Toggle
                  :checked="values[setting.key] === 'true'"
                  @change="onBoolChange(setting.key, $event)"
                >
                  <span class="text-sm text-gray-300">{{
                    values[setting.key] === "true" ? "已开启" : "已关闭"
                  }}</span>
                </Toggle>
              </div>

              <!-- 带 options 的 text → 下拉（原版没有 ENUM 类型，合法取值挂在 options 上） -->
              <select
                v-else-if="setting.options && setting.options.length > 0"
                class="w-full cursor-pointer rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white transition-all duration-200 focus:border-brand focus:ring-2 focus:ring-[rgba(121,217,255,0.25)] focus:outline-none"
                style="color-scheme: dark"
                :value="values[setting.key]"
                :aria-label="setting.label"
                @change="onTextChange(setting.key, $event)"
              >
                <option
                  v-for="option in setting.options"
                  :key="option"
                  :value="option"
                  class="bg-slate-800 text-white"
                >
                  {{ option }}
                </option>
              </select>

              <!-- text → 输入框 -->
              <input
                v-else
                class="w-full rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white transition-all duration-200 focus:border-brand focus:ring-2 focus:ring-[rgba(121,217,255,0.25)] focus:outline-none"
                type="text"
                :value="values[setting.key]"
                :placeholder="setting.defaultValue === '' ? '软件默认' : setting.defaultValue"
                :aria-label="setting.label"
                @change="onTextChange(setting.key, $event)"
              />
            </div>
          </section>
        </div>
      </MenuItem>

      <MenuItem v-if="!loading && categories.length === 0" title="设置不可用">
        <template #header>
          <Type :size="20" />
        </template>
        <div class="flex items-center justify-between gap-3">
          <span class="text-sm text-white/70">后端没连上，本地也没有离线缓存。</span>
          <Button type="big" @click="load">重试</Button>
        </div>
      </MenuItem>
    </MenuPage>
  </div>
</template>

<script setup lang="ts">
import type { Component } from "vue";
import { onMounted, reactive, ref } from "vue";
import { Earth, MessageSquareText, Star, Type, WrapText } from "@lucide/vue";
import { Button } from "../../base";
import Toggle from "../../base/widget/Toggle.vue";
import { MenuItem, MenuPage } from "../../ui";
import { notify } from "../../../store/appStore";
import {
  ConfigApiError,
  fetchConfigTree,
  saveConfig,
  type Category,
  type ConfigSetting,
  type ConfigTree,
} from "../../../api/settings";

/**
 * 通用设置页（规格 B4-BE §4.2）：不再硬编码表单，改成**按后端配置树渲染**。
 *
 * - 分类 → 一个 MenuItem；分类下再按子分类分组（原版 Category → Subcategory → settings）
 * - 类型 → 滑块 / 开关 / 输入框（带 options 的 text 给下拉）
 * - 改完值 → PUT /api/settings；失败把后端的 error 文案原样弹出来
 * - 后端连不上 → 回落 localStorage 缓存并显示「离线，改动不会同步」
 *
 * V4 观感对齐（只改皮，读写逻辑不变）：分组标题 / 单项行 / 控件三类样式照抄原版
 * base/items/SettingItem.vue，开关直接复用 base/widget/Toggle.vue；行内类名与本仓库
 * 已对齐的 SettingsAdvanceOther.vue 保持同一套，避免两个配置树页面漂移。
 *
 * 其它 9 个设置页这一批不动，仍读 appStore 的 localStorage。
 */

/** 离线缓存：整棵树的定义 + 最后一次的值（后端通了就以树为准）。 */
const OFFLINE_CACHE_KEY = "interview-lab:config";

interface ConfigCache {
  categories: Category[];
  values: Record<string, string>;
}

const GROUP_ICONS: Record<string, Component> = {
  llm: MessageSquareText,
  features: Star,
  chat: WrapText,
  display: Type,
  audio: Earth,
};

const categories = ref<Category[]>([]);
/** key → 当前值（一律字符串，与后端一致；控件自己按 type 转换） */
const values = reactive<Record<string, string>>({});
const offline = ref(false);
const loading = ref(true);

function iconOf(categoryId: string): Component {
  return GROUP_ICONS[categoryId] ?? Type;
}

function isNumber(setting: ConfigSetting): boolean {
  return setting.type === "number";
}

/**
 * 滑块的步长：后端 number 不再区分整数/小数，用默认值里有没有小数点判断
 * （`llm.temperature` 的 "1.0" → 0.1，`llm.timeout_secs` 的 "60" → 1）。
 */
function stepOf(setting: ConfigSetting): number {
  return String(setting.defaultValue).includes(".") ? 0.1 : 1;
}

/** 把树里的值灌进 values（树是唯一真相源）。 */
function applyTree(tree: ConfigTree) {
  categories.value = tree.categories;
  for (const category of tree.categories) {
    for (const sub of category.subcategories) {
      for (const setting of sub.settings) {
        values[setting.key] = setting.value;
      }
    }
  }
}

function readCache(): ConfigCache | null {
  try {
    const raw = localStorage.getItem(OFFLINE_CACHE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<ConfigCache>;
    // 旧版本缓存里是 groups（一层结构），结构对不上就整份丢弃，等后端回来重新拉
    if (!Array.isArray(parsed.categories)) return null;
    return { categories: parsed.categories, values: (parsed.values ?? {}) as Record<string, string> };
  } catch {
    return null;
  }
}

/** 写离线缓存：保存成功后双写一份，后端挂了下次进来还能渲染。 */
function writeCache() {
  if (categories.value.length === 0) return;
  const cache: ConfigCache = { categories: categories.value, values: { ...values } };
  try {
    localStorage.setItem(OFFLINE_CACHE_KEY, JSON.stringify(cache));
  } catch {
    // 配额满了也不该挡住保存流程，忽略
  }
}

/** 进页面：先问后端，连不上才用本地缓存（规格 §4.3）。 */
async function load() {
  loading.value = true;
  try {
    const tree = await fetchConfigTree();
    applyTree(tree);
    offline.value = false;
    writeCache();
  } catch (error) {
    const cache = readCache();
    if (cache) {
      categories.value = cache.categories;
      for (const category of cache.categories) {
        for (const sub of category.subcategories) {
          for (const setting of sub.settings) {
            values[setting.key] = cache.values[setting.key] ?? setting.defaultValue;
          }
        }
      }
    }
    offline.value = true;
    console.warn("[settings] 后端不可用，已切换到离线缓存", error);
  } finally {
    loading.value = false;
  }
}

/**
 * 提交一项：后端成功 → toast；后端 400 → 弹后端文案；
 * 网络层失败 → 标记离线 + 提示「离线，改动不会同步」（改动仍留在本地缓存里）。
 */
async function commit(key: string) {
  if (offline.value) {
    writeCache();
    notify("离线，改动不会同步");
    return;
  }
  try {
    await saveConfig({ [key]: values[key] });
    writeCache();
    notify("已保存");
  } catch (error) {
    if (error instanceof ConfigApiError && error.status === undefined) {
      // 连不上：转入离线，值留在本地
      offline.value = true;
      writeCache();
      notify("离线，改动不会同步");
      return;
    }
    const message = error instanceof Error ? error.message : "保存失败";
    notify(message);
  }
}

function onNumberInput(key: string, event: Event) {
  values[key] = (event.target as HTMLInputElement).value;
}

function onBoolChange(key: string, checked: boolean) {
  values[key] = checked ? "true" : "false";
  void commit(key);
}

function onTextChange(key: string, event: Event) {
  const target = event.target as HTMLInputElement | HTMLSelectElement;
  values[key] = target.value;
  void commit(key);
}

onMounted(load);
</script>

<style scoped>
.settings-text-container {
  position: relative;
  width: 100%;
  height: 100%;
}

.offline-tip {
  position: sticky;
  top: 0;
  z-index: 10;
  margin: 0 auto;
  width: fit-content;
  border-radius: 9999px;
  border: 1px solid rgba(255, 196, 0, 0.5);
  background: rgba(120, 80, 0, 0.75);
  padding: 0.25rem 0.9rem;
  font-size: 0.8rem;
  color: #ffe9b0;
}

/* ===== 滑块：原生 range + 原版自定义轨道/滑块头 ===== */
.b2-slider {
  display: flex;
  align-items: center;
  gap: 15px;
  color: rgba(255, 255, 255, 0.9);
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
}

.b2-slider > span {
  flex: none;
  white-space: nowrap;
}

.b2-bound {
  font-size: 0.7rem;
  color: rgba(255, 255, 255, 0.45);
}

.b2-value {
  min-width: 2.5rem;
  text-align: right;
}

.b2-range {
  flex-grow: 1;
  min-width: 0;
  outline: none;
  margin: 10px 0;
  appearance: none;
  -webkit-appearance: none;
  position: relative;
  background-color: transparent;
}

.b2-range::-webkit-slider-runnable-track {
  width: 100%;
  height: 8px;
  border-radius: 4px;
  transition: all 0.3s ease;
  background: rgba(255, 255, 255, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.2);
}

.b2-range:hover::-webkit-slider-runnable-track {
  height: 10px;
  background: rgba(255, 255, 255, 0.25);
}

.b2-range::-webkit-slider-thumb {
  z-index: 2;
  width: 22px;
  height: 22px;
  cursor: grab;
  appearance: none;
  -webkit-appearance: none;
  margin-top: -7px;
  position: relative;
  border-radius: 50%;
  transform-origin: center;
  border: 2px solid rgba(255, 255, 255, 0.8);
  transition: all 0.2s cubic-bezier(0.25, 0.8, 0.25, 1);
  background: linear-gradient(135deg, var(--accent-color), #64b5f6);
  box-shadow:
    0 4px 12px rgba(121, 217, 255, 0.4),
    0 2px 4px rgba(0, 0, 0, 0.2);
}

.b2-range::-webkit-slider-thumb:hover,
.b2-range::-webkit-slider-thumb:active {
  transform: scale(1.15);
  background: linear-gradient(135deg, #64b5f6, var(--accent-color));
  box-shadow:
    0 6px 20px rgba(121, 217, 255, 0.6),
    0 4px 8px rgba(0, 0, 0, 0.3);
}

.b2-range:active::-webkit-slider-thumb {
  cursor: grabbing;
}

.b2-range::-moz-range-track {
  width: 100%;
  height: 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.b2-range::-moz-range-thumb {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.8);
  background: linear-gradient(135deg, var(--accent-color), #64b5f6);
}
</style>
